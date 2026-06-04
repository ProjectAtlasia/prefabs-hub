/*
 * PrefabsUploader — sends a player's local prefabs to the Hytale server.
 * Copyright (C) 2026 ProjectAtlasia
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package dev.atlasia.prefabuploader.service.hub;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.auth.ServerAuthManager;
import dev.atlasia.prefabuploader.config.PluginConfig;
import dev.atlasia.prefabuploader.grpc.ConfigState;
import dev.atlasia.prefabuploader.grpc.GetPendingRequest;
import dev.atlasia.prefabuploader.grpc.GetPendingResponse;
import dev.atlasia.prefabuploader.grpc.HubMessage;
import dev.atlasia.prefabuploader.grpc.ListPendingRequest;
import dev.atlasia.prefabuploader.grpc.ListPendingResponse;
import dev.atlasia.prefabuploader.grpc.PlayerImportRequest;
import dev.atlasia.prefabuploader.grpc.PlayerImportResponse;
import dev.atlasia.prefabuploader.grpc.PlayerLinked;
import dev.atlasia.prefabuploader.grpc.PluginMessage;
import dev.atlasia.prefabuploader.grpc.PrefabsUploaderGrpc;
import dev.atlasia.prefabuploader.grpc.Register;
import dev.atlasia.prefabuploader.grpc.ResolvePendingRequest;
import dev.atlasia.prefabuploader.grpc.ResolvePendingResponse;
import dev.atlasia.prefabuploader.grpc.SetupRequest;
import dev.atlasia.prefabuploader.grpc.SetupResponse;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * gRPC client for the plugin (lazy, windowed connections). There is no permanent {@code Connect}
 * stream: the channel uses an idle timeout so the transport closes when no RPC runs, and the unary
 * RPCs ({@code RequestSetup}, {@code PlayerImport}, {@code ListPending}/{@code GetPending}/ {@code
 * ResolvePending}) run on demand. The {@code Connect} stream is only opened briefly — at boot to
 * fetch the initial {@link ConfigState}, and inside short listening windows (setup/link) that wait
 * for hub events. Also downloads directly from the Discord CDN.
 */
public final class Client {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  private static final long MAX_MSG = 16L << 20;

  /** Upper bound on a hub-supplied pairing token persisted to config (sanity guard). */
  private static final int MAX_HUB_TOKEN_LEN = 4096;

  /** Per-call deadline applied to every blocking unary RPC. */
  private static final long RPC_DEADLINE_SEC = 10;

  private static final Duration CDN_TIMEOUT = Duration.ofSeconds(15);

  /** Overall wall-clock deadline for streaming a CDN body, guarding against slow-trickle reads. */
  private static final Duration CDN_READ_DEADLINE = Duration.ofSeconds(30);

  /** When no RPC is in flight, gRPC tears down the transport after this many seconds. */
  private static final long IDLE_TIMEOUT_SEC = 30;

  private static final long BOOT_BACKOFF_MIN_SEC = 2;
  private static final long BOOT_BACKOFF_MAX_SEC = 60;

  /** How long the boot stream stays open to receive the initial ConfigState before closing. */
  private static final long BOOT_WINDOW_SEC = 10;

  private static final long AUTH_POLL_SEC = 2;
  private static final long MAX_AUTH_WAIT_SEC = 60;
  private long authWaitedSec = 0;
  private long bootBackoffSec = BOOT_BACKOFF_MIN_SEC;
  private volatile boolean bootDone = false;

  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  // Identification headers sent on EVERY RPC: plugin version + Hytale server name.
  private static final Metadata.Key<String> PLUGIN_VERSION_HEADER =
      Metadata.Key.of("x-plugin-version", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> SERVER_NAME_HEADER =
      Metadata.Key.of("x-server-name", Metadata.ASCII_STRING_MARSHALLER);

  private final PluginConfig config;

  private ManagedChannel channel;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "PrefabsUploader-Hub");
            t.setDaemon(true);
            return t;
          });

  private final ExecutorService ioExecutor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "PrefabsUploader-IO");
            t.setDaemon(true);
            return t;
          });

  private volatile HttpClient httpClient;

  // Shared windowed stream. Guarded by streamLock for open/close transitions.
  private final Object streamLock = new Object();
  private final Object sendLock = new Object();
  private StreamObserver<PluginMessage> outbound;
  private int activeWindows = 0;

  // Live waiters dispatched from the stream observer.
  // configuredWaiters fire on ConfigState(configured=true); stateWaiters fire on ANY ConfigState.
  private final List<Runnable> configuredWaiters = new CopyOnWriteArrayList<>();
  private final List<Runnable> stateWaiters = new CopyOnWriteArrayList<>();
  private final List<Consumer<PlayerLinked>> linkWaiters = new CopyOnWriteArrayList<>();

  private volatile boolean running = false;

  private final AtomicReference<HubState> state = new AtomicReference<>(new HubState(false, ""));

  private record HubState(boolean configured, String inviteUrl) {}

  public Client(PluginConfig config) {
    this.config = config;
  }

  /**
   * Returns the client's I/O executor (blocking RPCs + CDN downloads), used to avoid blocking the
   * world thread. The pool uses daemon threads.
   */
  public ExecutorService io() {
    return ioExecutor;
  }

  /** The owner-configured maximum prefab upload size in bytes. */
  public int maxPrefabBytes() {
    return config.maxPrefabBytes();
  }

  /** The owner-configured maximum number of approved prefabs per player. */
  public int maxPrefabsPerPlayer() {
    return config.maxPrefabsPerPlayer();
  }

  public void start() {
    if (running) {
      return;
    }
    running = true;
    try {
      String[] hostPort = parseAddress(config.hubAddress());
      InetSocketAddress address = new InetSocketAddress(hostPort[0], Integer.parseInt(hostPort[1]));
      NettyChannelBuilder builder =
          NettyChannelBuilder.forAddress(address)
              .maxInboundMessageSize((int) MAX_MSG)
              .idleTimeout(IDLE_TIMEOUT_SEC, TimeUnit.SECONDS);
      configureTransportSecurity(builder, hostPort[0]);
      builder.intercept(identityInterceptor());
      this.channel = builder.build();
    } catch (Throwable t) {
      LOG.at(Level.SEVERE).log(
          "[PrefabsUploader] failed to create gRPC channel to %s: %s",
          config.hubAddress(), t.getMessage());
      running = false;
      return;
    }
    awaitAuthThenConnect();
  }

  /**
   * Applies the configured transport security to the channel builder: plaintext when TLS is off,
   * standard validated TLS, or — only in dev builds that permit it ({@link
   * PluginConfig#insecureTlsAllowed()}) — insecure TLS with no certificate validation. Official and
   * release builds always validate the certificate even if {@code hub.insecure=true} is set. The
   * channel closes when idle and gRPC reopens it transparently on the next RPC.
   */
  private void configureTransportSecurity(NettyChannelBuilder builder, String host)
      throws javax.net.ssl.SSLException {
    if (!config.hubTls()) {
      builder.usePlaintext();
      return;
    }
    if (config.hubInsecure() && config.insecureTlsAllowed()) {
      var ssl =
          GrpcSslContexts.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
      builder.sslContext(ssl).overrideAuthority(host);
      LOG.at(Level.WARNING).log(
          "[PrefabsUploader] INSECURE TLS (no certificate validation) enabled -- dev build");
      return;
    }
    if (config.hubInsecure()) {
      LOG.at(Level.WARNING).log(
          "[PrefabsUploader] hub.insecure=true ignored: this build enforces TLS certificate"
              + " validation. Use a dev build to disable it.");
    }
    builder.useTransportSecurity().overrideAuthority(host);
  }

  /**
   * Gates the first connection until the server holds a Hytale identity token (authenticated).
   * Polls briefly up to {@link #MAX_AUTH_WAIT_SEC}; proceeds anyway once that limit is reached.
   */
  private void awaitAuthThenConnect() {
    if (!running) {
      return;
    }
    if (serverHasIdentityToken()) {
      LOG.at(Level.INFO).log(
          "[PrefabsUploader] fetching initial hub state from %s", config.hubAddress());
      bootFetchConfigState();
      return;
    }
    if (authWaitedSec >= MAX_AUTH_WAIT_SEC) {
      LOG.at(Level.WARNING).log(
          "[PrefabsUploader] no Hytale identity token after %ds — proceeding anyway "
              + "(the hub rejects if the server is not authenticated; run /auth login).",
          MAX_AUTH_WAIT_SEC);
      bootFetchConfigState();
      return;
    }
    if (authWaitedSec == 0) {
      LOG.at(Level.INFO).log(
          "[PrefabsUploader] waiting for the server to authenticate with Hytale before contacting the hub…");
    }
    authWaitedSec += AUTH_POLL_SEC;
    scheduler.schedule(this::awaitAuthThenConnect, AUTH_POLL_SEC, TimeUnit.SECONDS);
  }

  /**
   * Boot step: open the {@code Connect} stream briefly to receive the initial {@link ConfigState}
   * (configured + invite url + auth token), then close it. Retries with backoff if the hub is
   * unreachable. Runs on the scheduler thread.
   */
  private void bootFetchConfigState() {
    if (!running || bootDone) {
      return;
    }
    Window w;
    try {
      w = openWindow(BOOT_WINDOW_SEC);
    } catch (Throwable t) {
      LOG.at(Level.INFO).log("[PrefabsUploader] initial state unavailable: %s", t.getMessage());
      scheduleBootRetry();
      return;
    }
    // Close as soon as the first ConfigState (configured or not) arrives; else the deadline closes
    // it.
    Runnable[] holder = new Runnable[1];
    holder[0] =
        () -> {
          if (!stateWaiters.remove(holder[0])) {
            return;
          }
          bootDone = true;
          bootBackoffSec = BOOT_BACKOFF_MIN_SEC;
          w.close();
          LOG.at(Level.INFO).log("[PrefabsUploader] initial hub state received");
        };
    stateWaiters.add(holder[0]);
    // If the window times out without a ConfigState, retry boot.
    w.onExpire(
        () -> {
          stateWaiters.remove(holder[0]);
          if (!bootDone) {
            LOG.at(Level.INFO).log(
                "[PrefabsUploader] hub did not respond with initial state — retrying");
            scheduleBootRetry();
          }
        });
  }

  private void scheduleBootRetry() {
    if (!running || bootDone) {
      return;
    }
    long delay = bootBackoffSec;
    bootBackoffSec = Math.min(bootBackoffSec * 2, BOOT_BACKOFF_MAX_SEC);
    scheduler.schedule(this::bootFetchConfigState, delay, TimeUnit.SECONDS);
  }

  /** Returns true if the server is already authenticated with Hytale (holds an identity token). */
  private static boolean serverHasIdentityToken() {
    try {
      return ServerAuthManager.getInstance().hasIdentityToken();
    } catch (Throwable t) {
      return false;
    }
  }

  /**
   * Returns an interceptor that adds {@code authorization: Bearer <identityToken>} to every call.
   * The token is read at call time, so the current one is always sent.
   */
  private ClientInterceptor identityInterceptor() {
    return new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(
            next.newCall(method, callOptions)) {
          @Override
          public void start(Listener<RespT> responseListener, Metadata headers) {
            String token = serverIdentityToken();
            if (token != null && !token.isEmpty()) {
              headers.put(AUTHORIZATION, "Bearer " + token);
            } else {
              LOG.at(Level.WARNING).log(
                  "[PrefabsUploader] no Hytale identity token — the hub will reject. "
                      + "Authenticate the server with Hytale (/auth login).");
            }
            headers.put(PLUGIN_VERSION_HEADER, PluginConfig.PLUGIN_VERSION);
            String name = serverName();
            if (!name.isEmpty()) {
              headers.put(SERVER_NAME_HEADER, name);
            }
            super.start(responseListener, headers);
          }
        };
      }
    };
  }

  /**
   * Reads the server identity token via the engine's ServerAuthManager.
   *
   * @return the identity token, or null if not authenticated
   */
  private static String serverIdentityToken() {
    try {
      return ServerAuthManager.getInstance().getIdentityToken();
    } catch (Throwable t) {
      LOG.at(Level.WARNING).log(
          "[PrefabsUploader] failed to obtain Hytale identity token: %s", t.getMessage());
      return null;
    }
  }

  /** Hytale server name (best-effort; empty if unavailable), sanitized for an ASCII header. */
  private static String serverName() {
    try {
      return asciiHeader(HytaleServer.get().getServerName());
    } catch (Throwable t) {
      return "";
    }
  }

  /** Keeps only printable ASCII and caps at 64 chars (gRPC metadata requires ASCII). */
  private static String asciiHeader(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder b = new StringBuilder(Math.min(s.length(), 64));
    for (int i = 0; i < s.length() && b.length() < 64; i++) {
      char c = s.charAt(i);
      if (c >= 0x20 && c <= 0x7E) {
        b.append(c);
      }
    }
    return b.toString();
  }

  // ---------------------------------------------------------------------------
  // Windowed Connect stream
  // ---------------------------------------------------------------------------

  /**
   * A listening window. While at least one window is open the shared {@code Connect} stream stays
   * up; when all windows close (deadline or explicit {@link #close()}) the stream is torn down. A
   * window auto-closes after its duration; an optional {@link #onExpire(Runnable)} hook fires only
   * when it closes by deadline (not by an explicit {@code close()}).
   */
  public final class Window {
    private final ScheduledFuture<?> deadline;
    private volatile Runnable onExpire;
    private volatile boolean closed = false;

    private Window(long seconds) {
      this.deadline = scheduler.schedule(this::expire, seconds, TimeUnit.SECONDS);
    }

    /** Registers a callback fired only if the window closes by reaching its deadline. */
    public void onExpire(Runnable r) {
      this.onExpire = r;
    }

    private void expire() {
      Runnable hook = null;
      synchronized (streamLock) {
        if (closed) {
          return;
        }
        closed = true;
        hook = onExpire;
        releaseWindowLocked();
      }
      if (hook != null) {
        runSafe(hook);
      }
    }

    /** Closes the window explicitly (e.g. the awaited event arrived). */
    public void close() {
      synchronized (streamLock) {
        if (closed) {
          return;
        }
        closed = true;
        deadline.cancel(false);
        releaseWindowLocked();
      }
    }
  }

  /**
   * Opens a listening window of {@code seconds}, ensuring the shared {@code Connect} stream is up.
   * Must be called off the world thread.
   *
   * @throws IllegalStateException if the client is not running or the channel is unavailable
   */
  public Window openWindow(long seconds) {
    if (!running || channel == null) {
      throw new IllegalStateException("integration not initialized (gRPC channel unavailable)");
    }
    synchronized (streamLock) {
      if (outbound == null) {
        openStreamLocked();
      }
      activeWindows++;
      return new Window(seconds);
    }
  }

  /** Decrements the window count and closes the stream once no window remains. Holds streamLock. */
  private void releaseWindowLocked() {
    activeWindows = Math.max(0, activeWindows - 1);
    if (activeWindows == 0) {
      closeStreamLocked();
    }
  }

  /** Opens the shared Connect stream and sends Register. Holds streamLock. */
  private void openStreamLocked() {
    PrefabsUploaderGrpc.PrefabsUploaderStub stub = PrefabsUploaderGrpc.newStub(channel);
    StreamObserver<PluginMessage> out = stub.connect(new HubResponseObserver());
    outbound = out;
    synchronized (sendLock) {
      out.onNext(
          PluginMessage.newBuilder()
              .setRegister(
                  Register.newBuilder()
                      .setServerId(config.serverId())
                      .setPluginVersion(PluginConfig.PLUGIN_VERSION)
                      .setAuthToken(config.authToken())
                      .build())
              .build());
    }
    LOG.at(Level.FINE).log("[PrefabsUploader] window opened — Connect stream active");
  }

  /** Completes and drops the shared Connect stream. Holds streamLock. */
  private void closeStreamLocked() {
    StreamObserver<PluginMessage> out = outbound;
    outbound = null;
    if (out == null) {
      return;
    }
    try {
      synchronized (sendLock) {
        out.onCompleted();
      }
    } catch (Throwable t) {
      LOG.at(Level.FINE).log("[PrefabsUploader] closing stream failed: %s", t.getMessage());
    }
    LOG.at(Level.FINE).log("[PrefabsUploader] all windows closed — Connect stream shut down");
  }

  private void runSafe(Runnable r) {
    try {
      r.run();
    } catch (Throwable t) {
      LOG.at(Level.WARNING).log("[PrefabsUploader] window callback failed: %s", t.getMessage());
    }
  }

  /**
   * Registers a one-shot setup waiter, fired when a {@link ConfigState} with {@code
   * configured=true} arrives on the stream. The caller is responsible for opening the window. The
   * waiter removes itself on fire.
   *
   * @return a handle to deregister the waiter manually (e.g. on window expiry)
   */
  public Runnable awaitConfigured(Runnable onConfigured) {
    Runnable[] holder = new Runnable[1];
    holder[0] =
        () -> {
          if (configuredWaiters.remove(holder[0])) {
            runSafe(onConfigured);
          }
        };
    configuredWaiters.add(holder[0]);
    return () -> configuredWaiters.remove(holder[0]);
  }

  /**
   * Registers a one-shot link waiter, fired when a {@link PlayerLinked} whose {@code player_uuid}
   * equals {@code playerUuid} arrives on the stream. The caller opens the window.
   *
   * @return a handle to deregister the waiter manually (e.g. on window expiry)
   */
  public Runnable awaitPlayerLinked(String playerUuid, Consumer<PlayerLinked> onLinked) {
    Consumer<PlayerLinked>[] holder = newConsumerArray();
    holder[0] =
        linked -> {
          if (playerUuid != null && playerUuid.equals(linked.getPlayerUuid())) {
            if (linkWaiters.remove(holder[0])) {
              try {
                onLinked.accept(linked);
              } catch (Throwable t) {
                LOG.at(Level.WARNING).log(
                    "[PrefabsUploader] link callback failed: %s", t.getMessage());
              }
            }
          }
        };
    linkWaiters.add(holder[0]);
    return () -> linkWaiters.remove(holder[0]);
  }

  @SuppressWarnings("unchecked")
  private static Consumer<PlayerLinked>[] newConsumerArray() {
    return new Consumer[1];
  }

  // ---------------------------------------------------------------------------
  // Unary RPCs
  // ---------------------------------------------------------------------------

  /**
   * Returns a blocking stub bound to the live channel with the standard per-call deadline.
   *
   * @throws IllegalStateException if the channel has not been initialized
   */
  private PrefabsUploaderGrpc.PrefabsUploaderBlockingStub blockingStub() {
    if (channel == null) {
      throw new IllegalStateException("integration not initialized (gRPC channel unavailable)");
    }
    return PrefabsUploaderGrpc.newBlockingStub(channel)
        .withDeadlineAfter(RPC_DEADLINE_SEC, TimeUnit.SECONDS);
  }

  /** Called by {@code /prefabs-uploader config setup}. Blocking — run off the game thread. */
  public SetupResponse requestSetup(String requestedBy) {
    return blockingStub()
        .requestSetup(
            SetupRequest.newBuilder()
                .setServerId(config.serverId())
                .setRequestedBy(requestedBy == null ? "" : requestedBy)
                .build());
  }

  /**
   * Called by {@code /prefabs-uploader import}/{@code link}. Blocking — run off the game thread.
   */
  public PlayerImportResponse playerImport(String playerUuid, String playerName) {
    return blockingStub()
        .playerImport(
            PlayerImportRequest.newBuilder()
                .setServerId(config.serverId())
                .setPlayerUuid(playerUuid == null ? "" : playerUuid)
                .setPlayerName(playerName == null ? "" : playerName)
                .build());
  }

  /**
   * Lists the hub's pending entries (metadata/pointers only). Blocking — run off the world thread
   * (use {@link #io()}).
   */
  public ListPendingResponse listPending() {
    return blockingStub()
        .listPending(
            ListPendingRequest.newBuilder()
                .setServerId(config.serverId())
                .setAuthToken(config.authToken())
                .build());
  }

  /**
   * Requests a fresh download URL (Discord CDN) for a pending entry. Blocking — run off the world
   * thread (use {@link #io()}). The URL is ephemeral and must not be persisted.
   */
  public GetPendingResponse getPending(String id) {
    return blockingStub()
        .getPending(
            GetPendingRequest.newBuilder()
                .setServerId(config.serverId())
                .setId(id == null ? "" : id)
                .setAuthToken(config.authToken())
                .build());
  }

  /**
   * Resolves a pending entry: approved (already written to live storage) or rejected. Blocking —
   * run off the world thread (use {@link #io()}).
   */
  public ResolvePendingResponse resolvePending(String id, boolean approved, String resolvedBy) {
    return blockingStub()
        .resolvePending(
            ResolvePendingRequest.newBuilder()
                .setServerId(config.serverId())
                .setId(id == null ? "" : id)
                .setApproved(approved)
                .setResolvedBy(resolvedBy == null ? "" : resolvedBy)
                .setAuthToken(config.authToken())
                .build());
  }

  /**
   * Downloads the {@code .prefab.json} directly from the Discord CDN (HTTP GET on the URL returned
   * by {@link #getPending}). Blocking — run off the world thread (use {@link #io()}). Reads in a
   * streaming fashion and aborts if the body exceeds the owner-configured max prefab size.
   *
   * @param url the HTTPS download URL
   * @return the downloaded bytes
   * @throws IOException if the URL is invalid/insecure, the status is non-2xx, the body exceeds the
   *     cap, or a network failure occurs
   * @throws InterruptedException if the operation is interrupted
   */
  public byte[] downloadFromCdn(String url) throws IOException, InterruptedException {
    URI uri = requireHttpsUri(url);
    HttpResponse<InputStream> resp =
        httpClient()
            .send(
                HttpRequest.newBuilder(uri).GET().timeout(CDN_TIMEOUT).build(),
                HttpResponse.BodyHandlers.ofInputStream());
    int status = resp.statusCode();
    if (status / 100 != 2) {
      closeQuietly(resp.body());
      throw new IOException("download failed (HTTP " + status + ")");
    }
    try (InputStream in = resp.body()) {
      return readCapped(in, config.maxPrefabBytes());
    }
  }

  /**
   * Validates that {@code url} is a non-blank https URL.
   *
   * @return the parsed URI
   * @throws IOException if the URL is blank, malformed, or not https
   */
  private static URI requireHttpsUri(String url) throws IOException {
    if (url == null || url.isBlank()) {
      throw new IOException("empty download URL");
    }
    URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      throw new IOException("invalid download URL");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !scheme.equalsIgnoreCase("https")) {
      throw new IOException("download URL is not https");
    }
    return uri;
  }

  /**
   * Reads the stream fully into memory, aborting once the body exceeds {@code maxBytes} or the read
   * exceeds {@link #CDN_READ_DEADLINE} (slow-trickle guard).
   *
   * @param maxBytes the maximum accepted body size in bytes
   * @throws IOException if the body exceeds the size cap, the read times out, or a read fails
   */
  private static byte[] readCapped(InputStream in, int maxBytes) throws IOException {
    long deadline = System.nanoTime() + CDN_READ_DEADLINE.toNanos();
    try (ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024))) {
      byte[] buf = new byte[16 * 1024];
      int total = 0;
      int n;
      while ((n = in.read(buf)) != -1) {
        if (System.nanoTime() > deadline) {
          throw new IOException("download timed out (slow response)");
        }
        total += n;
        if (total > maxBytes) {
          throw new IOException("prefab exceeds the size limit (" + (maxBytes >> 20) + " MiB)");
        }
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    }
  }

  /** Closes the stream, logging at FINE if it fails (used to drain an error response body). */
  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException e) {
      LOG.at(Level.FINE).log("[PrefabsUploader] failed to close error body: %s", e.getMessage());
    }
  }

  /** Returns the lazily built HttpClient (created on the first download, off the world thread). */
  private HttpClient httpClient() {
    HttpClient c = httpClient;
    if (c == null) {
      synchronized (this) {
        c = httpClient;
        if (c == null) {
          c =
              HttpClient.newBuilder()
                  .connectTimeout(CDN_TIMEOUT)
                  .followRedirects(HttpClient.Redirect.NORMAL)
                  .build();
          httpClient = c;
        }
      }
    }
    return c;
  }

  public boolean isConfigured() {
    return state.get().configured();
  }

  public String inviteUrl() {
    return state.get().inviteUrl();
  }

  public void shutdown() {
    running = false;
    scheduler.shutdownNow();
    ioExecutor.shutdownNow();
    configuredWaiters.clear();
    linkWaiters.clear();
    synchronized (streamLock) {
      activeWindows = 0;
      closeStreamLocked();
    }
    if (channel != null) {
      channel.shutdownNow();
    }
  }

  private static String[] parseAddress(String addr) {
    int i = addr.lastIndexOf(':');
    if (i < 0) {
      return new String[] {addr, "50051"};
    }
    return new String[] {addr.substring(0, i), addr.substring(i + 1)};
  }

  /** Handles the downstream messages coming from the hub on the shared windowed stream. */
  private final class HubResponseObserver implements StreamObserver<HubMessage> {
    @Override
    public void onNext(HubMessage msg) {
      switch (msg.getPayloadCase()) {
        case CONFIG_STATE -> onConfigState(msg.getConfigState());
        case PLAYER_LINKED -> onPlayerLinked(msg.getPlayerLinked());
        default -> {}
      }
    }

    @Override
    public void onError(Throwable t) {
      LOG.at(Level.FINE).log("[PrefabsUploader] stream closed: %s", t.getMessage());
      // Drop the dead stream; the next openWindow() reopens it.
      synchronized (streamLock) {
        outbound = null;
      }
    }

    @Override
    public void onCompleted() {
      synchronized (streamLock) {
        outbound = null;
      }
    }
  }

  private void onConfigState(ConfigState cs) {
    String url = cs.getBotInviteUrl().isEmpty() ? state.get().inviteUrl() : cs.getBotInviteUrl();
    state.set(new HubState(cs.getConfigured(), url));
    maybePersistAuthToken(cs.getAuthToken());
    LOG.at(Level.INFO).log(
        "[PrefabsUploader] config updated: configured=%s guild=%s",
        cs.getConfigured(), cs.getGuildId());
    // Boot/state waiters fire on every ConfigState; configured waiters only when configured=true.
    for (Runnable w : new ArrayList<>(stateWaiters)) {
      runSafe(w);
    }
    if (cs.getConfigured()) {
      for (Runnable w : new ArrayList<>(configuredWaiters)) {
        runSafe(w);
      }
    }
  }

  /**
   * Persists a pairing token supplied by the hub, but only when the connection is authenticated
   * (the server holds a Hytale identity token) and the token passes a basic format check. The write
   * runs on the I/O executor so it never blocks the gRPC callback thread. A token offered while
   * unauthenticated (e.g. the boot fallback that proceeds without identity) is ignored.
   */
  private void maybePersistAuthToken(String token) {
    if (token.isEmpty()) {
      return;
    }
    if (!serverHasIdentityToken()) {
      LOG.at(Level.WARNING).log(
          "[PrefabsUploader] ignoring hub-supplied auth token: server is not authenticated with"
              + " Hytale yet");
      return;
    }
    if (!isPlausibleHubToken(token)) {
      LOG.at(Level.WARNING).log(
          "[PrefabsUploader] ignoring hub-supplied auth token: failed format check");
      return;
    }
    try {
      ioExecutor.execute(() -> config.setAuthToken(token));
    } catch (RejectedExecutionException e) {
      LOG.at(Level.FINE).log("[PrefabsUploader] auth token persist skipped (shutting down)");
    }
  }

  /** Sanity check on a hub-supplied token: bounded length, no control or whitespace characters. */
  private static boolean isPlausibleHubToken(String token) {
    if (token.length() > MAX_HUB_TOKEN_LEN) {
      return false;
    }
    return token.chars().noneMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c));
  }

  private void onPlayerLinked(PlayerLinked pl) {
    LOG.at(Level.INFO).log(
        "[PrefabsUploader] player linked: uuid=%s discord=%s",
        pl.getPlayerUuid(), pl.getDiscordName());
    List<Consumer<PlayerLinked>> snapshot = new ArrayList<>(linkWaiters);
    for (Consumer<PlayerLinked> w : snapshot) {
      w.accept(pl);
    }
  }
}
