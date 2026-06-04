/*
 * PrefabsUploader — envia prefabs locais do jogador para o servidor Hytale.
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
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
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

  private static final Duration CDN_TIMEOUT = Duration.ofSeconds(15);
  private static final int CDN_MAX_BYTES =
      dev.atlasia.prefabuploader.service.prefab.PrefabValidator.MAX_BYTES;

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
  // Headers de identificação enviados em TODA RPC: versão do plugin + nome do servidor Hytale.
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
              // Close the transport when idle; gRPC reopens it transparently on the next RPC.
              .idleTimeout(IDLE_TIMEOUT_SEC, TimeUnit.SECONDS);
      if (config.hubTls()) {
        if (config.hubInsecure()) {
          var ssl =
              io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forClient()
                  .trustManager(
                      io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory
                          .INSTANCE)
                  .build();
          builder.sslContext(ssl).overrideAuthority(hostPort[0]);
          LOG.at(Level.WARNING).log(
              "[PrefabsUploader] TLS INSEGURO (sem validar certificado) habilitado");
        } else {
          builder.useTransportSecurity().overrideAuthority(hostPort[0]);
        }
      } else {
        builder.usePlaintext();
      }
      builder.intercept(identityInterceptor());
      this.channel = builder.build();
    } catch (Throwable t) {
      LOG.at(Level.SEVERE).log(
          "[PrefabsUploader] falha ao criar canal gRPC para %s: %s",
          config.hubAddress(), t.getMessage());
      running = false;
      return;
    }
    awaitAuthThenConnect();
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
          "[PrefabsUploader] buscando estado inicial do hub em %s", config.hubAddress());
      bootFetchConfigState();
      return;
    }
    if (authWaitedSec >= MAX_AUTH_WAIT_SEC) {
      LOG.at(Level.WARNING).log(
          "[PrefabsUploader] sem identity token do Hytale após %ds — seguindo mesmo assim "
              + "(o hub recusa se o servidor não estiver autenticado; rode /auth login).",
          MAX_AUTH_WAIT_SEC);
      bootFetchConfigState();
      return;
    }
    if (authWaitedSec == 0) {
      LOG.at(Level.INFO).log(
          "[PrefabsUploader] aguardando autenticação do servidor no Hytale antes de falar com o hub…");
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
      LOG.at(Level.INFO).log("[PrefabsUploader] estado inicial indisponível: %s", t.getMessage());
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
          LOG.at(Level.INFO).log("[PrefabsUploader] estado inicial do hub recebido");
        };
    stateWaiters.add(holder[0]);
    // If the window times out without a ConfigState, retry boot.
    w.onExpire(
        () -> {
          stateWaiters.remove(holder[0]);
          if (!bootDone) {
            LOG.at(Level.INFO).log(
                "[PrefabsUploader] hub não respondeu com estado inicial — nova tentativa");
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
      return com.hypixel.hytale.server.core.auth.ServerAuthManager.getInstance().hasIdentityToken();
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
                  "[PrefabsUploader] sem identity token do Hytale — o hub vai recusar. "
                      + "Autentique o servidor no Hytale (/auth login).");
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
      return com.hypixel.hytale.server.core.auth.ServerAuthManager.getInstance().getIdentityToken();
    } catch (Throwable t) {
      LOG.at(Level.WARNING).log(
          "[PrefabsUploader] falha ao obter identity token do Hytale: %s", t.getMessage());
      return null;
    }
  }

  /** Nome do servidor Hytale (best-effort; vazio se indisponível), sanitizado pra header ASCII. */
  private static String serverName() {
    try {
      return asciiHeader(com.hypixel.hytale.server.core.HytaleServer.get().getServerName());
    } catch (Throwable t) {
      return "";
    }
  }

  /** Mantém só ASCII imprimível e limita a 64 chars (metadados gRPC exigem ASCII). */
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
      throw new IllegalStateException("integração não inicializada (canal gRPC indisponível)");
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
    LOG.at(Level.FINE).log("[PrefabsUploader] janela aberta — stream Connect ativo");
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
      LOG.at(Level.FINE).log("[PrefabsUploader] fechar stream falhou: %s", t.getMessage());
    }
    LOG.at(Level.FINE).log(
        "[PrefabsUploader] todas as janelas fecharam — stream Connect encerrado");
  }

  private void runSafe(Runnable r) {
    try {
      r.run();
    } catch (Throwable t) {
      LOG.at(Level.WARNING).log("[PrefabsUploader] callback de janela falhou: %s", t.getMessage());
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
                    "[PrefabsUploader] callback de link falhou: %s", t.getMessage());
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

  /** Called by {@code /prefabs-uploader config setup}. Blocking — run off the game thread. */
  public SetupResponse requestSetup(String requestedBy) {
    if (channel == null) {
      throw new IllegalStateException("integração não inicializada (canal gRPC indisponível)");
    }
    PrefabsUploaderGrpc.PrefabsUploaderBlockingStub blocking =
        PrefabsUploaderGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    return blocking.requestSetup(
        SetupRequest.newBuilder()
            .setServerId(config.serverId())
            .setRequestedBy(requestedBy == null ? "" : requestedBy)
            .build());
  }

  /**
   * Called by {@code /prefabs-uploader import}/{@code link}. Blocking — run off the game thread.
   */
  public PlayerImportResponse playerImport(String playerUuid, String playerName) {
    if (channel == null) {
      throw new IllegalStateException("integração não inicializada (canal gRPC indisponível)");
    }
    PrefabsUploaderGrpc.PrefabsUploaderBlockingStub blocking =
        PrefabsUploaderGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    return blocking.playerImport(
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
    if (channel == null) {
      throw new IllegalStateException("integração não inicializada (canal gRPC indisponível)");
    }
    PrefabsUploaderGrpc.PrefabsUploaderBlockingStub blocking =
        PrefabsUploaderGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    return blocking.listPending(
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
    if (channel == null) {
      throw new IllegalStateException("integração não inicializada (canal gRPC indisponível)");
    }
    PrefabsUploaderGrpc.PrefabsUploaderBlockingStub blocking =
        PrefabsUploaderGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    return blocking.getPending(
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
    if (channel == null) {
      throw new IllegalStateException("integração não inicializada (canal gRPC indisponível)");
    }
    PrefabsUploaderGrpc.PrefabsUploaderBlockingStub blocking =
        PrefabsUploaderGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    return blocking.resolvePending(
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
   * streaming fashion and aborts if the body exceeds {@link #CDN_MAX_BYTES}.
   *
   * @param url the HTTPS download URL
   * @return the downloaded bytes
   * @throws IOException if the URL is invalid/insecure, the status is non-2xx, the body exceeds the
   *     cap, or a network failure occurs
   * @throws InterruptedException if the operation is interrupted
   */
  public byte[] downloadFromCdn(String url) throws IOException, InterruptedException {
    if (url == null || url.isBlank()) {
      throw new IOException("URL de download vazia");
    }
    URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      throw new IOException("URL de download inválida");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !scheme.equalsIgnoreCase("https")) {
      throw new IOException("URL de download não é https");
    }

    HttpResponse<InputStream> resp =
        httpClient()
            .send(
                HttpRequest.newBuilder(uri).GET().timeout(CDN_TIMEOUT).build(),
                HttpResponse.BodyHandlers.ofInputStream());
    int status = resp.statusCode();
    if (status / 100 != 2) {
      try (InputStream ignored = resp.body()) {
      } catch (IOException e) {
        LOG.at(Level.FINE).log(
            "[PrefabsUploader] falha ao fechar corpo de erro: %s", e.getMessage());
      }
      throw new IOException("download falhou (HTTP " + status + ")");
    }

    try (InputStream in = resp.body();
        java.io.ByteArrayOutputStream out =
            new java.io.ByteArrayOutputStream(Math.min(CDN_MAX_BYTES, 64 * 1024))) {
      byte[] buf = new byte[16 * 1024];
      int total = 0;
      int n;
      while ((n = in.read(buf)) != -1) {
        total += n;
        if (total > CDN_MAX_BYTES) {
          throw new IOException(
              "prefab excede o limite de tamanho (" + (CDN_MAX_BYTES >> 20) + " MiB)");
        }
        out.write(buf, 0, n);
      }
      return out.toByteArray();
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
      LOG.at(Level.FINE).log("[PrefabsUploader] stream encerrado: %s", t.getMessage());
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
    if (!cs.getAuthToken().isEmpty()) {
      config.setAuthToken(cs.getAuthToken());
    }
    LOG.at(Level.INFO).log(
        "[PrefabsUploader] config atualizada: configured=%s guild=%s",
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

  private void onPlayerLinked(PlayerLinked pl) {
    LOG.at(Level.INFO).log(
        "[PrefabsUploader] player linkado: uuid=%s discord=%s",
        pl.getPlayerUuid(), pl.getDiscordName());
    List<Consumer<PlayerLinked>> snapshot = new ArrayList<>(linkWaiters);
    for (Consumer<PlayerLinked> w : snapshot) {
      w.accept(pl);
    }
  }
}
