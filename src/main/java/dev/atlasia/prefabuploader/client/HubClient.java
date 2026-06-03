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
package dev.atlasia.prefabuploader.client;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.atlasia.prefabuploader.config.PluginConfig;
import dev.atlasia.prefabuploader.grpc.ConfigState;
import dev.atlasia.prefabuploader.grpc.GetPendingRequest;
import dev.atlasia.prefabuploader.grpc.GetPendingResponse;
import dev.atlasia.prefabuploader.grpc.Heartbeat;
import dev.atlasia.prefabuploader.grpc.HubMessage;
import dev.atlasia.prefabuploader.grpc.ListPendingRequest;
import dev.atlasia.prefabuploader.grpc.ListPendingResponse;
import dev.atlasia.prefabuploader.grpc.PlayerImportRequest;
import dev.atlasia.prefabuploader.grpc.PlayerImportResponse;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Cliente gRPC do plugin: mantém o stream bidirecional {@code Connect} com o hub
 * (config/heartbeat), reconectando com backoff, e expõe os RPCs unários ({@code RequestSetup},
 * {@code PlayerImport} e — no modelo PULL — {@code ListPending}/{@code GetPending}/{@code
 * ResolvePending}) além do download direto do CDN do Discord. O hub NÃO empurra mais prefabs (não
 * há case {@code PrefabPush}). Autor: astahjmo (Astaroth).
 */
public final class HubClient {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  private static final long MAX_MSG = 16L << 20;

  // [PULL] Download do CDN: timeout e teto de corpo (espelha PrefabValidator.MAX_BYTES = 8 MiB).
  private static final Duration CDN_TIMEOUT = Duration.ofSeconds(15);
  private static final int CDN_MAX_BYTES =
      dev.atlasia.prefabuploader.prefab.PrefabValidator.MAX_BYTES;
  private static final long HEARTBEAT_SEC = 30;
  private static final long BACKOFF_MIN_SEC = 2;
  private static final long BACKOFF_MAX_SEC = 60;

  // [P1] Espera o servidor autenticar no Hytale (ServerAuthManager) antes da 1ª conexão — evita a
  // corrida de boot em que o identity token ainda não está pronto e o hub recusa. Após o limite,
  // conecta mesmo assim (o hub decide; se o servidor estiver em modo offline, recusa e loga).
  private static final long AUTH_POLL_SEC = 2;
  private static final long MAX_AUTH_WAIT_SEC = 60;
  private long authWaitedSec = 0;

  // [H7] Header onde o identity token do servidor Hytale viaja em toda RPC pro hub validar.
  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  private final PluginConfig config;

  private ManagedChannel channel;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "PrefabsUploader-Hub");
            t.setDaemon(true);
            return t;
          });

  // [PULL] Executor de I/O (RPCs bloqueantes + download HTTP do CDN). Vive aqui pra ser reusado
  // pela UI de validação (que NUNCA pode bloquear a thread do mundo). Daemon, single-thread (o
  // fluxo da staff é serial: lista → clica → baixa → aprova/rejeita).
  private final ExecutorService ioExecutor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "PrefabsUploader-IO");
            t.setDaemon(true);
            return t;
          });

  // Cliente HTTP do download do CDN (lazy: só monta no 1º download, fora da thread do mundo).
  private volatile HttpClient httpClient;

  // Observer de saída do stream atual. Send NÃO é concorrente → sincronizar nele.
  private final AtomicReference<StreamObserver<PluginMessage>> outbound = new AtomicReference<>();
  private final Object sendLock = new Object();

  private volatile boolean running = false;
  private volatile long backoffSec = BACKOFF_MIN_SEC;

  // Estado de config como snapshot ATÔMICO e imutável: leitura consistente do par
  // (configured, inviteUrl). Antes eram dois volatiles independentes → o broadcaster
  // podia ler um par inconsistente (configured novo + inviteUrl velha, ou vice-versa).
  private final AtomicReference<HubState> state = new AtomicReference<>(new HubState(false, ""));

  private record HubState(boolean configured, String inviteUrl) {}

  public HubClient(PluginConfig config) {
    this.config = config;
  }

  /**
   * Executor de I/O do cliente (RPCs bloqueantes + download do CDN). A UI de validação usa este
   * pool pra NUNCA bloquear a thread do mundo. Daemon — não segura o shutdown da JVM.
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
      // forAddress(SocketAddress) usa endereço DIRETO — não passa pelo NameResolver via
      // ServiceLoader. No fat jar shadeado só o resolver 'unix' (UdsNameResolverProvider)
      // sobrevive ao merge, então 'localhost:50051' era resolvido como unix socket e o
      // transporte netty rejeitava. Endereço direto contorna isso por completo.
      NettyChannelBuilder builder =
          NettyChannelBuilder.forAddress(address).maxInboundMessageSize((int) MAX_MSG);
      if (config.hubTls()) {
        if (config.hubInsecure()) {
          // TLS SEM validar o certificado (cert self-signed/quebrado) — equivalente ao
          // `grpcurl -insecure`. NÃO usar em produção com cert válido.
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
          // Endereço direto contorna o NameResolver, então o authority/SNI não vem do hostname
          // automaticamente — fixamos via overrideAuthority pra o handshake TLS e a verificação
          // de certificado baterem com o domínio real (ex.: atrás do Traefik/:443).
          builder.useTransportSecurity().overrideAuthority(hostPort[0]);
        }
      } else {
        builder.usePlaintext();
      }
      // [H7] Em TODA RPC, anexa o identity token do servidor Hytale (sessions.hytale.com) pro hub
      // provar que é um servidor real e autenticado. O hub rejeita conexões sem JWT válido.
      builder.intercept(identityInterceptor());
      this.channel = builder.build();
    } catch (Throwable t) {
      // NUNCA propagar: uma falha de rede/config não pode derrubar o boot do servidor.
      LOG.at(Level.SEVERE).log(
          "[PrefabsUploader] falha ao criar canal gRPC para %s: %s",
          config.hubAddress(), t.getMessage());
      running = false;
      return;
    }
    scheduler.scheduleAtFixedRate(
        this::sendHeartbeat, HEARTBEAT_SEC, HEARTBEAT_SEC, TimeUnit.SECONDS);
    awaitAuthThenConnect();
  }

  /**
   * Gating da 1ª conexão: só conecta quando o servidor tem identity token do Hytale (autenticado).
   * Faz polling curto até {@link #MAX_AUTH_WAIT_SEC}; se estourar, conecta assim mesmo. A reconexão
   * (scheduleReconnect) não passa por aqui — o token já está pronto depois do boot.
   */
  private void awaitAuthThenConnect() {
    if (!running) {
      return;
    }
    if (serverHasIdentityToken()) {
      LOG.at(Level.INFO).log("[PrefabsUploader] conectando ao hub em %s", config.hubAddress());
      connect();
      return;
    }
    if (authWaitedSec >= MAX_AUTH_WAIT_SEC) {
      LOG.at(Level.WARNING).log(
          "[PrefabsUploader] sem identity token do Hytale após %ds — conectando mesmo assim "
              + "(o hub recusa se o servidor não estiver autenticado; rode /auth login).",
          MAX_AUTH_WAIT_SEC);
      connect();
      return;
    }
    if (authWaitedSec == 0) {
      LOG.at(Level.INFO).log(
          "[PrefabsUploader] aguardando autenticação do servidor no Hytale antes de conectar ao hub…");
    }
    authWaitedSec += AUTH_POLL_SEC;
    scheduler.schedule(this::awaitAuthThenConnect, AUTH_POLL_SEC, TimeUnit.SECONDS);
  }

  /** True se o servidor já está autenticado no Hytale (tem identity token). */
  private static boolean serverHasIdentityToken() {
    try {
      return com.hypixel.hytale.server.core.auth.ServerAuthManager.getInstance().hasIdentityToken();
    } catch (Throwable t) {
      return false;
    }
  }

  /**
   * Interceptor que coloca {@code authorization: Bearer <identityToken>} em cada chamada. O token é
   * lido na hora (o engine renova sozinho ~5min antes de expirar), então sempre vai o atual.
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
            super.start(responseListener, headers);
          }
        };
      }
    };
  }

  /** Lê o identity token do servidor pelo ServerAuthManager do engine (null se não autenticado). */
  private static String serverIdentityToken() {
    try {
      return com.hypixel.hytale.server.core.auth.ServerAuthManager.getInstance().getIdentityToken();
    } catch (Throwable t) {
      LOG.at(Level.WARNING).log(
          "[PrefabsUploader] falha ao obter identity token do Hytale: %s", t.getMessage());
      return null;
    }
  }

  private void connect() {
    if (!running) {
      return;
    }
    try {
      PrefabsUploaderGrpc.PrefabsUploaderStub stub = PrefabsUploaderGrpc.newStub(channel);
      StreamObserver<PluginMessage> out = stub.connect(new HubResponseObserver());
      outbound.set(out);
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
    } catch (Throwable t) {
      LOG.at(Level.WARNING).log("[PrefabsUploader] connect falhou: %s", t.getMessage());
      scheduleReconnect();
    }
  }

  private void scheduleReconnect() {
    if (!running) {
      return;
    }
    outbound.set(null);
    long delay = backoffSec;
    backoffSec = Math.min(backoffSec * 2, BACKOFF_MAX_SEC);
    LOG.at(Level.INFO).log("[PrefabsUploader] reconectando em %ds", delay);
    scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
  }

  private void sendHeartbeat() {
    synchronized (sendLock) {
      StreamObserver<PluginMessage> out = outbound.get();
      if (out == null) {
        return;
      }
      try {
        out.onNext(
            PluginMessage.newBuilder()
                .setHeartbeat(Heartbeat.newBuilder().setTimestampMs(System.currentTimeMillis()))
                .build());
      } catch (Throwable t) {
        LOG.at(Level.FINE).log("[PrefabsUploader] heartbeat falhou: %s", t.getMessage());
      }
    }
  }

  /**
   * Chamado por {@code /prefabs-uploader config setup}. Bloqueante — rode fora da thread do jogo.
   */
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

  /** Chamado por {@code /prefabs-uploader import}. Bloqueante — rode fora da thread do jogo. */
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

  // ---- [PULL] RPCs do fluxo de validação (lista/ponteiro/resolução) ----
  // Auth: igual requestSetup/playerImport — só mandamos serverId; o identity token do servidor vai
  // no header authorization (identityInterceptor) em TODA RPC. Além do gate de identidade, as RPCs
  // de fila mandam o auth_token (token de pareamento) — o hub valida o par (server_id, auth_token)
  // pra impedir que um servidor real-mas-malicioso leia/resolva a fila de OUTRO servidor.

  /**
   * Lista os pendentes (só metadados/ponteiros) do hub. Bloqueante — rode fora da thread do mundo
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
   * Pede uma URL de download FRESCA (CDN do Discord) pra um pendente. Bloqueante — rode fora da
   * thread do mundo (use {@link #io()}). A URL é efêmera; NÃO persistir.
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
   * Resolve um pendente: aprovado (já gravado no storage vivo) ou rejeitado. Bloqueante — rode fora
   * da thread do mundo (use {@link #io()}).
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
   * Baixa o {@code .prefab.json} direto do CDN do Discord (HTTP GET na URL vinda de {@link
   * #getPending}). Bloqueante — rode fora da thread do mundo (use {@link #io()}). Capa o corpo em
   * {@link #CDN_MAX_BYTES} (8 MiB): lê em streaming e aborta se passar, pra não estourar memória. A
   * URL NÃO é persistida.
   *
   * @throws IOException URL inválida/insegura, status não-2xx, corpo acima do teto, ou falha de
   *     rede
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
      // Só HTTPS (CDN do Discord). Bloqueia file://, http:// e afins (anti-SSRF básico).
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
        // drena/fecha o corpo de erro
      } catch (IOException e) {
        LOG.at(Level.FINE).log(
            "[PrefabsUploader] falha ao fechar corpo de erro: %s", e.getMessage());
      }
      throw new IOException("download falhou (HTTP " + status + ")");
    }

    // Lê em streaming com teto rígido (não confiamos no Content-Length do CDN).
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

  /** HttpClient lazy (montado no 1º download, fora da thread do mundo). */
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
    StreamObserver<PluginMessage> out = outbound.getAndSet(null);
    if (out != null) {
      try {
        synchronized (sendLock) {
          out.onCompleted();
        }
      } catch (Throwable t) {
        // stream já pode estar morto; só registramos (NUNCA silenciar exceção).
        LOG.at(Level.FINE).log(
            "[PrefabsUploader] onCompleted no shutdown falhou: %s", t.getMessage());
      }
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

  /** Trata as mensagens descendentes do hub. */
  private final class HubResponseObserver implements StreamObserver<HubMessage> {
    @Override
    public void onNext(HubMessage msg) {
      backoffSec = BACKOFF_MIN_SEC; // recebeu algo → conexão saudável
      switch (msg.getPayloadCase()) {
        case CONFIG_STATE -> onConfigState(msg.getConfigState());
        default -> {
          // payload desconhecido / não-tratado (o hub não empurra mais prefabs) — ignora
        }
      }
    }

    @Override
    public void onError(Throwable t) {
      LOG.at(Level.WARNING).log("[PrefabsUploader] stream com erro: %s", t.getMessage());
      scheduleReconnect();
    }

    @Override
    public void onCompleted() {
      LOG.at(Level.INFO).log("[PrefabsUploader] hub encerrou o stream");
      scheduleReconnect();
    }
  }

  private void onConfigState(ConfigState cs) {
    // Snapshot atômico: mantém a invite URL anterior se a nova vier vazia.
    String url = cs.getBotInviteUrl().isEmpty() ? state.get().inviteUrl() : cs.getBotInviteUrl();
    state.set(new HubState(cs.getConfigured(), url));
    if (!cs.getAuthToken().isEmpty()) {
      config.setAuthToken(cs.getAuthToken());
    }
    LOG.at(Level.INFO).log(
        "[PrefabsUploader] config atualizada: configured=%s guild=%s",
        cs.getConfigured(), cs.getGuildId());
  }
}
