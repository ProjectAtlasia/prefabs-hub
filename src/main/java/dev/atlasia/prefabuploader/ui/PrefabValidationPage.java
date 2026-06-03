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
package dev.atlasia.prefabuploader.ui;

import com.hypixel.hytale.builtin.buildertools.prefabeditor.PrefabEditSessionManager;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.buildertools.BuilderToolPrefabPreview;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.EditorBlocksChange;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.asset.util.ColorParseUtil;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.atlasia.prefabuploader.client.HubClient;
import dev.atlasia.prefabuploader.grpc.GetPendingResponse;
import dev.atlasia.prefabuploader.grpc.ResolvePendingResponse;
import dev.atlasia.prefabuploader.prefab.PendingPrefab;
import dev.atlasia.prefabuploader.prefab.PendingPrefabStore;
import dev.atlasia.prefabuploader.prefab.PlayerNameCache;
import dev.atlasia.prefabuploader.prefab.PrefabValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * UI "Validação de Prefabs" (admin): painel esquerdo = busca + lista de pendentes +
 * Aprovar/Deletar; painel direito = preview 3D in-panel.
 *
 * <p>Modelo PULL: a lista vem do hub (metadados). Ao selecionar um card, o plugin baixa o {@code
 * .prefab.json} direto do CDN do Discord EM MEMÓRIA (via {@link HubClient#downloadFromCdn}), valida
 * ({@link PrefabValidator}) e renderiza o preview a partir do {@code BlockSelection} resultante —
 * SEM tocar em disco. Os bytes ficam só em memória ({@link #selectedBytes}) e são descartados ao
 * trocar de card, aprovar/rejeitar ou fechar. Só a APROVAÇÃO grava em disco (storage vivo).
 *
 * <p>Threading: chamadas gRPC bloqueantes e o download HTTP rodam no executor de I/O do {@link
 * HubClient} ({@link HubClient#io()}); packets de preview e {@code sendUpdate} voltam pra thread do
 * mundo via {@link #runOnWorld}. Cliques rápidos são protegidos por um contador de geração ({@link
 * #selectionGen}): só o ÚLTIMO selecionado renderiza.
 *
 * <p>Estrutura do {@code .ui} clonada do plugin {@code ger4d/hy-dungeon-generator}. O preview é
 * dirigido pelo packet {@link BuilderToolPrefabPreview}. Autor: astahjmo.
 */
public class PrefabValidationPage extends InteractiveCustomUIPage<PrefabValidationPage.Data> {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  private static final int ROWS = 20;

  // Preview tuning — valores idênticos aos plugins que funcionam (e ao PrefabPage vanilla).
  private static final int PREVIEW_TILT = 23;
  private static final int PREVIEW_SPIN_SPEED = 27;
  private static final int PREVIEW_SCALE = 100;

  // Tints padrão (mesmos defaults do PrefabPage vanilla). Computados sob demanda e cacheados.
  private static volatile boolean tintsComputed;
  private static volatile Integer defaultBiomeTint;
  private static volatile Integer defaultWaterTint;

  public static class Data {
    public static final BuilderCodec<Data> CODEC =
        BuilderCodec.builder(Data.class, Data::new)
            .append(
                new KeyedCodec<>("Action", Codec.STRING, false),
                (d, v) -> d.action = v,
                d -> d.action)
            .add()
            .append(
                new KeyedCodec<>("@Search", Codec.STRING, false),
                (d, v) -> d.search = v,
                d -> d.search)
            .add()
            .build();

    private String action;
    private String search;
  }

  private final HubClient hubClient;

  private int page = 0;
  private String searchFilter = "";
  private int selectedIndex = -1;
  private List<PendingPrefab> cached;

  // [PULL] Bytes do prefab selecionado, baixados do CDN e mantidos SÓ em memória. Descartados ao
  // trocar de card, aprovar/rejeitar ou fechar. Acessados da thread do mundo e do executor de I/O →
  // volatile.
  private volatile byte[] selectedBytes;

  // [PULL] Guarda de seleção stale: cada clique incrementa. Uma tarefa de background só aplica seu
  // resultado (preview/bytes) se a geração ainda for a dela — cliques rápidos só renderizam o
  // ÚLTIMO. Acessado de múltiplas threads → volatile.
  private volatile int selectionGen = 0;

  public PrefabValidationPage(
      @Nonnull PlayerRef playerRef,
      @Nonnull HubClient hubClient,
      @Nonnull List<PendingPrefab> initial) {
    super(playerRef, CustomPageLifetime.CanDismiss, Data.CODEC);
    this.hubClient = hubClient;
    this.cached = new ArrayList<>(initial);
  }

  @Override
  public void build(
      @Nonnull Ref<EntityStore> ref,
      @Nonnull UICommandBuilder ui,
      @Nonnull UIEventBuilder ev,
      @Nonnull Store<EntityStore> store) {
    ui.append("Pages/PrefabValidation_PrefabsUploaderPlugin.ui");

    ev.addEventBinding(
        CustomUIEventBindingType.ValueChanged,
        "#SearchInput",
        EventData.of("@Search", "#SearchInput.Value"),
        false);
    ev.addEventBinding(
        CustomUIEventBindingType.Activating, "#PrevButton", EventData.of("Action", "PrevPage"));
    ev.addEventBinding(
        CustomUIEventBindingType.Activating, "#NextButton", EventData.of("Action", "NextPage"));
    for (int i = 0; i < ROWS; i++) {
      ev.addEventBinding(
          CustomUIEventBindingType.Activating, "#Card" + i, EventData.of("Action", "Select" + i));
    }
    ev.addEventBinding(
        CustomUIEventBindingType.Activating, "#Accept", EventData.of("Action", "Accept"));
    ev.addEventBinding(
        CustomUIEventBindingType.Activating, "#Delete", EventData.of("Action", "Delete"));

    populateList(ui);
    updateDetail(ui);
  }

  @Override
  public void handleDataEvent(
      @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, Data data) {
    super.handleDataEvent(ref, store, data);
    if (data.action == null && data.search == null) {
      return;
    }

    if (data.search != null) {
      searchFilter = data.search.trim().toLowerCase();
      page = 0;
      deselect();
      refresh();
      return;
    }

    String a = data.action;
    if ("PrevPage".equals(a)) {
      if (page > 0) {
        page--;
      }
      refresh();
      return;
    }
    if ("NextPage".equals(a)) {
      page++;
      refresh();
      return;
    }
    if (a.startsWith("Select")) {
      handleSelect(a);
      return;
    }
    if ("Accept".equals(a)) {
      handleAccept();
      return;
    }
    if ("Delete".equals(a)) {
      handleDelete();
      return;
    }
    sendUpdate();
  }

  /** Clique num card: seleciona e dispara o download+validação do preview em background. */
  private void handleSelect(String action) {
    int rowIdx = parseIdx(action, "Select");
    List<PendingPrefab> view = filtered();
    int absolute = page * ROWS + rowIdx;
    PendingPrefab chosen = null;
    if (absolute >= 0 && absolute < view.size()) {
      selectedIndex = absolute;
      chosen = view.get(absolute);
    } else {
      selectedIndex = -1;
    }

    // [PULL] Troca de seleção → descarta bytes do anterior e invalida tarefas de background velhas.
    selectedBytes = null;
    int gen = ++selectionGen;
    refresh();

    if (chosen == null) {
      clearPreview();
      return;
    }

    // Estado "carregando preview…" enquanto baixa do CDN.
    UICommandBuilder loading = new UICommandBuilder();
    loading.set(
        "#SelAuthor.TextSpans", Message.translation("server.prefabsuploader.ui.previewLoading"));
    sendUpdate(loading, false);

    final PendingPrefab target = chosen;
    hubClient
        .io()
        .execute(
            () -> {
              byte[] bytes;
              PrefabValidator.Result result;
              try {
                GetPendingResponse gp = hubClient.getPending(target.id());
                bytes = hubClient.downloadFromCdn(gp.getDownloadUrl());
                result = PrefabValidator.validate(bytes);
              } catch (Throwable t) {
                LOG.at(Level.WARNING).log(
                    "[PrefabsUploader] preview download/validate falhou (%s): %s",
                    target.prefabName(), t.getMessage());
                runOnWorld(
                    () -> {
                      if (gen != selectionGen) {
                        return; // o admin já trocou de card — ignora resultado velho
                      }
                      showDetailError("server.prefabsuploader.ui.previewDownloadError");
                    });
                return;
              }

              if (!result.ok()) {
                final String reason = result.error();
                runOnWorld(
                    () -> {
                      if (gen != selectionGen) {
                        return;
                      }
                      LOG.at(Level.INFO).log(
                          "[PrefabsUploader] preview rejeitado (%s): %s",
                          target.prefabName(), reason);
                      showDetailValidationFailed(reason);
                    });
                return;
              }

              final byte[] okBytes = bytes;
              final BlockSelection sel = result.selection();
              runOnWorld(
                  () -> {
                    if (gen != selectionGen) {
                      return; // seleção stale — não segura bytes nem renderiza
                    }
                    selectedBytes = okBytes; // segura os bytes do ÚLTIMO selecionado
                    sendPreview(target, sel);
                    refresh(); // restaura o painel de detalhe (sai do "carregando…")
                  });
            });
  }

  /** Aprovar: valida nativo + grava no storage vivo, depois resolvePending(true) no hub. */
  private void handleAccept() {
    PendingPrefab sel = selected();
    if (sel == null) {
      sendUpdate();
      return;
    }
    final String adminName = playerRef.getUsername();
    final String adminUuid = String.valueOf(playerRef.getUuid());
    final int gen = selectionGen;
    byte[] have = selectedBytes;

    hubClient
        .io()
        .execute(
            () -> {
              try {
                byte[] bytes = have;
                if (bytes == null) {
                  // Re-busca por segurança (bytes podem ter sido descartados).
                  GetPendingResponse gp = hubClient.getPending(sel.id());
                  bytes = hubClient.downloadFromCdn(gp.getDownloadUrl());
                  PrefabValidator.Result rv = PrefabValidator.validate(bytes);
                  if (!rv.ok()) {
                    throw new java.io.IOException(rv.error());
                  }
                }
                final byte[] finalBytes = bytes;

                // A gravação (validação NATIVA + write no storage vivo) toca o engine → world
                // thread.
                runOnWorldAwait(
                    () -> {
                      try {
                        PendingPrefabStore.get().approve(sel, finalBytes, adminName, adminUuid);
                      } catch (Throwable t) {
                        throw new RuntimeException(t);
                      }
                    });

                // Gravou: confirma no hub (bloqueante) e atualiza a UI.
                ResolvePendingResponse resp = hubClient.resolvePending(sel.id(), true, adminName);
                if (!resp.getOk() && !resp.getError().isBlank()) {
                  LOG.at(Level.WARNING).log(
                      "[PrefabsUploader] resolvePending(approved) com erro do hub: %s",
                      resp.getError());
                }
                runOnWorld(
                    () -> {
                      removeFromList(sel);
                      deselect();
                      clearPreview();
                      sendResult("server.prefabsuploader.ui.approved", sel.prefabName());
                      refresh();
                    });
              } catch (Throwable t) {
                final String reason = causeMessage(t);
                LOG.at(Level.WARNING).withCause(t).log(
                    "[PrefabsUploader] falha ao aprovar prefab %s", sel.prefabName());
                runOnWorld(
                    () -> {
                      if (gen == selectionGen) {
                        showDetailValidationFailed(reason);
                      }
                      sendError(reason);
                    });
              }
            });
  }

  /** Rejeitar: resolvePending(false) no hub (sem disco). */
  private void handleDelete() {
    PendingPrefab sel = selected();
    if (sel == null) {
      sendUpdate();
      return;
    }
    final String adminName = playerRef.getUsername();
    // Rejeição não usa os bytes — descarta já.
    selectedBytes = null;

    hubClient
        .io()
        .execute(
            () -> {
              try {
                ResolvePendingResponse resp = hubClient.resolvePending(sel.id(), false, adminName);
                if (!resp.getOk() && !resp.getError().isBlank()) {
                  LOG.at(Level.WARNING).log(
                      "[PrefabsUploader] resolvePending(rejected) com erro do hub: %s",
                      resp.getError());
                }
                runOnWorld(
                    () -> {
                      removeFromList(sel);
                      deselect();
                      clearPreview();
                      sendResult("server.prefabsuploader.ui.removed", sel.prefabName());
                      refresh();
                    });
              } catch (Throwable t) {
                final String reason = causeMessage(t);
                LOG.at(Level.WARNING).withCause(t).log(
                    "[PrefabsUploader] falha ao rejeitar prefab %s", sel.prefabName());
                runOnWorld(() -> sendError(reason));
              }
            });
  }

  // ---- preview 3D in-panel (PrefabPreviewComponent via BuilderToolPrefabPreview) ----
  // Padrão idêntico ao plugin ger4d/hy-dungeon-generator e ao PrefabPage vanilla: monta o packet
  // por campos e escreve via getPacketHandler(); o cliente amarra ao PrefabPreviewComponent da
  // página ativa (o packet não tem id de componente). A FONTE do BlockSelection agora é o download
  // do CDN (em memória), não mais o disco.
  private void sendPreview(PendingPrefab p, BlockSelection sel) {
    try {
      if (sel == null) {
        clearPreview();
        return;
      }
      EditorBlocksChange ebc = sel.toPacket();
      computeTints();
      // Limpa o preview anterior antes de mandar o novo (mesmo padrão dos plugins que funcionam).
      playerRef.getPacketHandler().write(new BuilderToolPrefabPreview());
      BuilderToolPrefabPreview pkt = new BuilderToolPrefabPreview();
      pkt.tilt = PREVIEW_TILT;
      pkt.spinSpeed = PREVIEW_SPIN_SPEED;
      pkt.previewScale = PREVIEW_SCALE;
      pkt.blocksChange = ebc.blocksChange;
      pkt.fluidsChange = ebc.fluidsChange;
      pkt.entityChanges = ebc.entityChanges;
      pkt.biomeTint = defaultBiomeTint;
      pkt.waterTint = defaultWaterTint;
      playerRef.getPacketHandler().write(pkt);
      LOG.at(Level.INFO).log(
          "[PrefabsUploader] preview enviado: %s blocks=%d", p.prefabName(), sel.getBlockCount());
    } catch (Throwable t) {
      LOG.at(Level.WARNING).withCause(t).log("[PrefabsUploader] preview falhou");
    }
  }

  /** Limpa o viewport de preview (packet vazio) — ao desselecionar, aprovar/deletar ou fechar. */
  private void clearPreview() {
    try {
      playerRef.getPacketHandler().write(new BuilderToolPrefabPreview());
    } catch (Throwable t) {
      LOG.at(Level.FINE).log("[PrefabsUploader] clear preview falhou: %s", t.getMessage());
    }
  }

  @Override
  public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
    // [PULL] Fechou a UI → invalida tarefas pendentes e descarta os bytes em memória.
    selectionGen++;
    selectedBytes = null;
    clearPreview();
  }

  /** Calcula os tints default uma vez (idêntico aos DEFAULT_*_TINT do PrefabPage vanilla). */
  private static void computeTints() {
    if (tintsComputed) {
      return;
    }
    try {
      defaultBiomeTint =
          ColorParseUtil.colorToARGBInt(PrefabEditSessionManager.DEFAULT_TINT) & 0xFFFFFF;
      defaultWaterTint =
          ColorParseUtil.colorToARGBInt(Environment.getUnknownFor("").getWaterTint()) & 0xFFFFFF;
    } catch (Throwable t) {
      LOG.at(Level.FINE).log("[PrefabsUploader] tints default indisponíveis: %s", t.getMessage());
      defaultBiomeTint = null;
      defaultWaterTint = null;
    }
    tintsComputed = true;
  }

  // ---- threading: voltar pra thread do mundo ----

  /** Mundo do jogador (pode ser null se desconectou). */
  private World world() {
    try {
      return Universe.get().getWorld(playerRef.getWorldUuid());
    } catch (Throwable t) {
      LOG.at(Level.FINE).log("[PrefabsUploader] mundo indisponível: %s", t.getMessage());
      return null;
    }
  }

  /** Executa {@code r} na thread do mundo do jogador (packets/UI exigem world thread). */
  private void runOnWorld(Runnable r) {
    World w = world();
    if (w == null) {
      return;
    }
    w.execute(
        () -> {
          try {
            r.run();
          } catch (Throwable t) {
            LOG.at(Level.WARNING).withCause(t).log(
                "[PrefabsUploader] tarefa de UI na world thread falhou");
          }
        });
  }

  /**
   * Executa {@code r} na thread do mundo e BLOQUEIA até concluir (usado no approve: a validação
   * nativa + write precisam terminar antes de confirmar no hub). Propaga a 1ª exceção. Chamado SÓ
   * do executor de I/O — nunca da world thread (evita deadlock).
   */
  private void runOnWorldAwait(Runnable r) throws Exception {
    World w = world();
    if (w == null) {
      throw new java.io.IOException("mundo indisponível");
    }
    java.util.concurrent.CompletableFuture<Void> done =
        new java.util.concurrent.CompletableFuture<>();
    w.execute(
        () -> {
          try {
            r.run();
            done.complete(null);
          } catch (Throwable t) {
            done.completeExceptionally(t);
          }
        });
    try {
      done.get(20, java.util.concurrent.TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException ee) {
      Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
      if (cause instanceof Exception ex) {
        throw ex;
      }
      throw new java.io.IOException(cause.getMessage(), cause);
    }
  }

  // ---- helpers ----
  private List<PendingPrefab> filtered() {
    if (searchFilter.isEmpty()) {
      return cached;
    }
    return cached.stream()
        .filter(p -> (p.uploaderName() + " " + p.prefabName()).toLowerCase().contains(searchFilter))
        .toList();
  }

  private PendingPrefab selected() {
    List<PendingPrefab> v = filtered();
    return (selectedIndex >= 0 && selectedIndex < v.size()) ? v.get(selectedIndex) : null;
  }

  /** Desseleciona e descarta os bytes em memória (invalidando tarefas de background pendentes). */
  private void deselect() {
    selectedIndex = -1;
    selectionGen++;
    selectedBytes = null;
  }

  /** Remove um pendente da lista local (após aprovar/rejeitar). */
  private void removeFromList(PendingPrefab p) {
    cached.removeIf(x -> x.id().equals(p.id()));
  }

  private void refresh() {
    UICommandBuilder b = new UICommandBuilder();
    populateList(b);
    updateDetail(b);
    sendUpdate(b, false);
  }

  private void populateList(UICommandBuilder b) {
    List<PendingPrefab> view = filtered();
    int maxPage = Math.max(0, (view.size() - 1) / ROWS);
    if (page > maxPage) {
      page = maxPage;
    }
    b.set(
        "#PageInfo.TextSpans",
        Message.translation("server.prefabsuploader.ui.pageInfo")
            .param("count", view.size())
            .param("page", page + 1)
            .param("pages", maxPage + 1));
    int start = page * ROWS;
    for (int i = 0; i < ROWS; i++) {
      int idx = start + i;
      if (idx < view.size()) {
        PendingPrefab p = view.get(idx);
        b.set("#Card" + i + ".Visible", true);
        // [L1] Sanitiza nome/autor antes de exibir (controle/bidi/zero-width).
        b.set("#CardName" + i + ".TextSpans", Message.raw(sanitize(p.prefabName())));
        b.set(
            "#CardAuthor" + i + ".TextSpans",
            Message.translation("server.prefabsuploader.ui.cardAuthor")
                .param("author", authorLine(p)));
      } else {
        b.set("#Card" + i + ".Visible", false);
      }
    }
  }

  private void updateDetail(UICommandBuilder b) {
    PendingPrefab p = selected();
    boolean has = p != null;
    // #Detail fica SEMPRE visível (o PrefabPreviewComponent precisa existir no cliente quando o
    // packet de preview chega). Só alternamos labels/estado dos botões.
    b.set("#Accept.Disabled", !has);
    b.set("#Delete.Disabled", !has);
    // [L1] Sanitiza nome/autor antes de exibir (controle/bidi/zero-width).
    b.set(
        "#SelName.TextSpans",
        has
            ? Message.raw(sanitize(p.prefabName()))
            : Message.translation("server.prefabsuploader.ui.selectPrompt"));
    b.set(
        "#SelAuthor.TextSpans",
        has
            ? Message.translation("server.prefabsuploader.ui.author").param("author", authorLine(p))
            : Message.raw(""));
  }

  /** Mostra uma mensagem de erro/estado no rótulo de autor do painel de detalhe. */
  private void showDetailError(String i18nKey) {
    UICommandBuilder b = new UICommandBuilder();
    b.set("#SelAuthor.TextSpans", Message.translation(i18nKey));
    sendUpdate(b, false);
  }

  /** Mostra "validação falhou: <motivo>" no painel de detalhe (i18n com param). */
  private void showDetailValidationFailed(String reason) {
    UICommandBuilder b = new UICommandBuilder();
    b.set(
        "#SelAuthor.TextSpans",
        Message.translation("server.prefabsuploader.ui.validationFailed")
            .param("error", sanitize(String.valueOf(reason))));
    sendUpdate(b, false);
  }

  private void sendResult(String i18nKey, String prefabName) {
    playerRef.sendMessage(
        Message.join(
            Message.raw("[PrefabsUploader] "),
            Message.translation(i18nKey).param("name", sanitize(prefabName))));
  }

  private void sendError(String reason) {
    playerRef.sendMessage(
        Message.join(
            Message.raw("[PrefabsUploader] "),
            Message.translation("server.prefabsuploader.ui.error")
                .param("error", sanitize(String.valueOf(reason)))));
  }

  private static String causeMessage(Throwable t) {
    Throwable c = t;
    if (c instanceof RuntimeException && c.getCause() != null) {
      c = c.getCause();
    }
    String msg = c.getMessage();
    return (msg == null || msg.isBlank()) ? c.getClass().getSimpleName() : msg;
  }

  /**
   * Linha de autor (i18n): nome IN-GAME + nome do Discord. O separador/"Discord:" vêm do .lang; os
   * nomes vão como params já sanitizados ([L1] controle/bidi/zero-width). Retorna {@link Message}
   * pra compor via {@code .param(key, Message)} no rótulo da UI.
   */
  private static Message authorLine(PendingPrefab p) {
    // Preferência: nome in-game enviado pelo bot (do vínculo) → resolução por UUID (cache/online).
    String hy = p.playerName();
    if (hy == null || hy.isBlank()) {
      hy = PlayerNameCache.get().resolve(p.playerUuid());
    }
    String dc = p.uploaderName();
    boolean hasHy = hy != null && !hy.isBlank();
    boolean hasDc = dc != null && !dc.isBlank();
    if (hasHy && hasDc) {
      return Message.translation("server.prefabsuploader.ui.authorBoth")
          .param("hytale", sanitize(hy))
          .param("discord", sanitize(dc));
    }
    if (hasHy) {
      return Message.raw(sanitize(hy));
    }
    if (hasDc) {
      return Message.translation("server.prefabsuploader.ui.authorDiscord")
          .param("discord", sanitize(dc));
    }
    // Sem nome in-game nem Discord → cai pro uuid (fallback do antigo owner()).
    return Message.raw(sanitize(p.playerUuid()));
  }

  private static int parseIdx(String action, String prefix) {
    try {
      return Integer.parseInt(action.substring(prefix.length()));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * [L1] Remove caracteres de controle/formatação Unicode perigosos de strings vindas do jogador
   * (nome do prefab / autor) antes de jogar na UI. Cobre: controles C0 (U+0000–U+001F) e DEL, bidi
   * overrides/embeds/isolates (U+202A–U+202E, U+2066–U+2069), zero-width (U+200B–U+200D, U+FEFF) e
   * o ZWNBSP/word-joiner. Evita spoofing de layout/RTL na UI de validação.
   */
  private static String sanitize(String s) {
    if (s == null || s.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean drop =
          c < 0x20 // controles C0
              || c == 0x7F // DEL
              || (c >= 0x202A && c <= 0x202E) // bidi overrides/embeds (LRE/RLE/PDF/LRO/RLO)
              || (c >= 0x2066 && c <= 0x2069) // bidi isolates (LRI/RLI/FSI/PDI)
              || c == 0x200B // zero-width space
              || c == 0x200C // zero-width non-joiner
              || c == 0x200D // zero-width joiner
              || c == 0x2060 // word joiner
              || c == 0xFEFF; // ZWNBSP / BOM
      if (!drop) {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
