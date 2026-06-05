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
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.atlasia.prefabuploader.grpc.GetPendingResponse;
import dev.atlasia.prefabuploader.grpc.ResolvePendingResponse;
import dev.atlasia.prefabuploader.service.hub.Client;
import dev.atlasia.prefabuploader.service.prefab.PendingPrefab;
import dev.atlasia.prefabuploader.service.prefab.PlayerNameCache;
import dev.atlasia.prefabuploader.service.prefab.PrefabValidator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Admin "Prefab Validation" UI: lists pending prefabs with Approve/Delete actions and an in-panel
 * 3D preview.
 *
 * <p>Pull model: the list comes from the hub; selecting a card downloads the {@code .prefab.json}
 * from the CDN into memory, validates it ({@link PrefabValidator}) and renders the preview. Only
 * approval writes to disk.
 */
public class PrefabValidationPage extends AbstractPrefabPage<PrefabValidationPage.Data> {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();

  private static final int PREVIEW_TILT = 23;
  private static final int PREVIEW_SPIN_SPEED = 27;
  private static final int PREVIEW_SCALE = 100;

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

  private final Client hubClient;

  private int page = 0;
  private String searchFilter = "";
  private int selectedIndex = -1;
  private List<PendingPrefab> cached;

  private volatile BlockSelection selectedSelection;

  private volatile int selectionGen = 0;

  public PrefabValidationPage(
      @Nonnull PlayerRef playerRef,
      @Nonnull Client hubClient,
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
      handleReview();
      return;
    }
    if ("Delete".equals(a)) {
      handleDelete();
      return;
    }
    sendUpdate();
  }

  /**
   * Handles a card click: selects it and triggers the background download and preview validation.
   */
  private void handleSelect(String action) {
    int rowIdx = parseIdx(action, "Select");
    if (rowIdx < 0 || rowIdx >= ROWS) {
      return;
    }
    List<PendingPrefab> view = filtered();
    int absolute = page * ROWS + rowIdx;
    PendingPrefab chosen = null;
    if (absolute >= 0 && absolute < view.size()) {
      selectedIndex = absolute;
      chosen = view.get(absolute);
    } else {
      selectedIndex = -1;
    }

    selectedSelection = null;
    int gen = ++selectionGen;
    refresh();

    if (chosen == null) {
      clearPreview();
      return;
    }

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
                bytes = hubClient.downloadFromCdn(gp.getDownloadUrl(), gp.getSizeBytes());
                result = PrefabValidator.validate(bytes, hubClient.maxPrefabBytes());
              } catch (Throwable t) {
                LOG.at(Level.WARNING).withCause(t).log(
                    "[PrefabsUploader] preview download/validate failed (%s)", target.prefabName());
                runOnWorld(
                    () -> {
                      if (gen != selectionGen) {
                        return;
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
                          "[PrefabsUploader] preview rejected (%s): %s",
                          target.prefabName(), reason);
                      showDetailValidationFailed(reason);
                    });
                return;
              }

              final BlockSelection sel = result.selection();
              runOnWorld(
                  () -> {
                    if (gen != selectionGen) {
                      return;
                    }
                    selectedSelection = sel;
                    sendPreview(target, sel);
                    refresh();
                  });
            });
  }

  /**
   * Opens the mandatory block-review step: ensures the prefab is downloaded and validated, then
   * hands it to {@link PrefabBlockReviewPage} where the admin curates the blocks and approves.
   */
  private void handleReview() {
    PendingPrefab pending = selected();
    if (pending == null) {
      sendUpdate();
      return;
    }
    BlockSelection have = selectedSelection;
    if (have != null) {
      runOnWorld(() -> openReview(pending, have));
      return;
    }
    final int gen = selectionGen;
    hubClient
        .io()
        .execute(
            () -> {
              try {
                GetPendingResponse gp = hubClient.getPending(pending.id());
                byte[] bytes = hubClient.downloadFromCdn(gp.getDownloadUrl(), gp.getSizeBytes());
                PrefabValidator.Result rv =
                    PrefabValidator.validate(bytes, hubClient.maxPrefabBytes());
                if (!rv.ok()) {
                  throw new IOException(rv.error());
                }
                final BlockSelection parsed = rv.selection();
                runOnWorld(
                    () -> {
                      if (gen != selectionGen) {
                        return;
                      }
                      selectedSelection = parsed;
                      openReview(pending, parsed);
                    });
              } catch (Throwable t) {
                LOG.at(Level.WARNING).withCause(t).log(
                    "[PrefabsUploader] failed to load prefab %s for review", pending.prefabName());
                runOnWorld(
                    () -> {
                      if (gen == selectionGen) {
                        showDetailError("server.prefabsuploader.ui.errorGeneric");
                      }
                      sendErrorGeneric();
                    });
              }
            });
  }

  /**
   * Opens the block-review page for the given prefab and its already-parsed selection. Must run on
   * the world thread.
   */
  private void openReview(PendingPrefab pending, BlockSelection selection) {
    World w = world();
    if (w == null) {
      return;
    }
    var store = w.getEntityStore().getStore();
    Player player = store.getComponent(playerRef.getReference(), Player.getComponentType());
    if (player == null) {
      return;
    }
    player
        .getPageManager()
        .openCustomPage(
            playerRef.getReference(),
            store,
            new PrefabBlockReviewPage(playerRef, hubClient, pending, selection));
  }

  /** Rejects the selection via resolvePending(false) on the hub (no disk write). */
  private void handleDelete() {
    PendingPrefab sel = selected();
    if (sel == null) {
      sendUpdate();
      return;
    }
    final String adminName = playerRef.getUsername();
    selectedSelection = null;

    hubClient
        .io()
        .execute(
            () -> {
              try {
                ResolvePendingResponse resp = hubClient.resolvePending(sel.id(), false, adminName);
                if (!resp.getOk() && !resp.getError().isBlank()) {
                  LOG.at(Level.WARNING).log(
                      "[PrefabsUploader] resolvePending(rejected) returned a hub error: %s",
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
                LOG.at(Level.WARNING).withCause(t).log(
                    "[PrefabsUploader] failed to reject prefab %s", sel.prefabName());
                runOnWorld(this::sendErrorGeneric);
              }
            });
  }

  private void sendPreview(PendingPrefab p, BlockSelection sel) {
    try {
      if (sel == null) {
        clearPreview();
        return;
      }
      EditorBlocksChange ebc = sel.toPacket();
      computeTints();
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
          "[PrefabsUploader] preview sent: %s blocks=%d", p.prefabName(), sel.getBlockCount());
    } catch (Throwable t) {
      LOG.at(Level.WARNING).withCause(t).log("[PrefabsUploader] preview failed");
    }
  }

  /** Clears the preview viewport with an empty packet on deselect, approval/deletion or close. */
  private void clearPreview() {
    try {
      playerRef.getPacketHandler().write(new BuilderToolPrefabPreview());
    } catch (Throwable t) {
      LOG.at(Level.FINE).log("[PrefabsUploader] clear preview failed: %s", t.getMessage());
    }
  }

  @Override
  public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
    selectionGen++;
    selectedSelection = null;
    clearPreview();
  }

  /** Computes the default biome and water tints once, caching the result. */
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
      LOG.at(Level.FINE).log("[PrefabsUploader] default tints unavailable: %s", t.getMessage());
      defaultBiomeTint = null;
      defaultWaterTint = null;
    }
    tintsComputed = true;
  }

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

  /**
   * Clears the selection and discards the in-memory bytes, invalidating pending background tasks.
   */
  private void deselect() {
    selectedIndex = -1;
    selectionGen++;
    selectedSelection = null;
  }

  /** Removes a pending prefab from the local list after approval or rejection. */
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
    b.set("#Accept.Disabled", !has);
    b.set("#Delete.Disabled", !has);
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

  /** Shows an error or status message on the detail panel's author label. */
  private void showDetailError(String i18nKey) {
    UICommandBuilder b = new UICommandBuilder();
    b.set("#SelAuthor.TextSpans", Message.translation(i18nKey));
    sendUpdate(b, false);
  }

  /** Shows a "validation failed" message with the given reason on the detail panel. */
  private void showDetailValidationFailed(String reason) {
    UICommandBuilder b = new UICommandBuilder();
    b.set(
        "#SelAuthor.TextSpans",
        Message.translation("server.prefabsuploader.ui.validationFailed")
            .param("error", sanitize(String.valueOf(reason))));
    sendUpdate(b, false);
  }

  private void sendResult(String i18nKey, String prefabName) {
    playerRef.sendMessage(tagged(Message.translation(i18nKey).param("name", sanitize(prefabName))));
  }

  /** Sends a generic chat error so raw exception text never reaches the client. */
  private void sendErrorGeneric() {
    playerRef.sendMessage(tagged(Message.translation("server.prefabsuploader.ui.errorGeneric")));
  }

  /**
   * Builds the author line combining the in-game name and Discord name, both sanitized.
   *
   * @param p the pending prefab
   * @return a {@link Message} for use via {@code .param(key, Message)} in the UI label
   */
  private static Message authorLine(PendingPrefab p) {
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
    return Message.raw(sanitize(p.playerUuid()));
  }
}
