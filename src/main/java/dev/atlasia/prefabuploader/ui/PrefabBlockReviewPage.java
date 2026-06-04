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

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.prefab.config.SelectionPrefabSerializer;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.atlasia.prefabuploader.grpc.ResolvePendingResponse;
import dev.atlasia.prefabuploader.service.hub.Client;
import dev.atlasia.prefabuploader.service.prefab.PendingPrefab;
import dev.atlasia.prefabuploader.service.prefab.PendingPrefabStore;
import dev.atlasia.prefabuploader.service.prefab.PrefabBlockFilter;
import dev.atlasia.prefabuploader.service.prefab.PrefabBlockManifest;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;

/**
 * Admin "Block Review" UI: lists every block/fluid type in a pending prefab with its count and an
 * allow/block toggle, then saves a filtered copy that keeps only the allowed types (the rest are
 * dropped). This is the mandatory step before a prefab is approved into live storage.
 *
 * <p>The selection is deserialized once on the world thread; toggling and the final filter/approve
 * all run on the world thread, while the blocking hub RPC runs on the {@link Client} I/O executor.
 */
public class PrefabBlockReviewPage extends AbstractPrefabPage<PrefabBlockReviewPage.Data> {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();

  private static final Color GREEN = new Color(0x55, 0xDD, 0x77);
  private static final Color RED = new Color(0xEE, 0x55, 0x55);
  private static final Color ORANGE = new Color(0xFF, 0xAA, 0x33);
  private static final Color WHITE = new Color(0xFF, 0xFF, 0xFF);
  private static final Color GRAY = new Color(0x80, 0x80, 0x80);
  private static final Color CYAN = new Color(0x94, 0xA7, 0xBB);

  public static class Data {
    public static final BuilderCodec<Data> CODEC =
        BuilderCodec.builder(Data.class, Data::new)
            .append(
                new KeyedCodec<>("Action", Codec.STRING, false),
                (d, v) -> d.action = v,
                d -> d.action)
            .add()
            .build();

    private String action;
  }

  private final Client hubClient;
  private final PendingPrefab pending;
  private final byte[] bytes;

  private BlockSelection selection;
  private List<PrefabBlockManifest.Entry> entries = List.of();
  private boolean[] allowed = new boolean[0];
  private boolean loaded;
  private boolean loadFailed;
  private boolean done;
  private int page = 0;

  public PrefabBlockReviewPage(
      @Nonnull PlayerRef playerRef,
      @Nonnull Client hubClient,
      @Nonnull PendingPrefab pending,
      @Nonnull byte[] bytes) {
    super(playerRef, CustomPageLifetime.CanDismiss, Data.CODEC);
    this.hubClient = hubClient;
    this.pending = pending;
    this.bytes = bytes;
  }

  @Override
  public void build(
      @Nonnull Ref<EntityStore> ref,
      @Nonnull UICommandBuilder ui,
      @Nonnull UIEventBuilder ev,
      @Nonnull Store<EntityStore> store) {
    ui.append("Pages/PrefabBlockReview_PrefabsUploaderPlugin.ui");

    ev.addEventBinding(
        CustomUIEventBindingType.Activating, "#PrevButton", EventData.of("Action", "PrevPage"));
    ev.addEventBinding(
        CustomUIEventBindingType.Activating, "#NextButton", EventData.of("Action", "NextPage"));
    for (int i = 0; i < ROWS; i++) {
      ev.addEventBinding(
          CustomUIEventBindingType.Activating, "#Row" + i, EventData.of("Action", "Toggle" + i));
    }
    ev.addEventBinding(
        CustomUIEventBindingType.Activating, "#Confirm", EventData.of("Action", "Confirm"));

    ensureLoaded();
    ui.set("#PrefabName.TextSpans", Message.raw(sanitize(pending.prefabName())));
    populateList(ui);
    updateSummary(ui);
  }

  @Override
  public void handleDataEvent(
      @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, Data data) {
    super.handleDataEvent(ref, store, data);
    if (data.action == null) {
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
    if (a.startsWith("Toggle")) {
      handleToggle(a);
      return;
    }
    if ("Confirm".equals(a)) {
      handleConfirm();
      return;
    }
    sendUpdate();
  }

  /** Deserializes the prefab and builds the block manifest once, on the world thread. */
  private void ensureLoaded() {
    if (loaded) {
      return;
    }
    loaded = true;
    try {
      selection =
          SelectionPrefabSerializer.deserialize(
              BsonDocument.parse(new String(bytes, StandardCharsets.UTF_8)));
      entries = PrefabBlockManifest.of(selection);
      allowed = new boolean[entries.size()];
      Arrays.fill(allowed, true);
    } catch (Throwable t) {
      loadFailed = true;
      LOG.at(Level.WARNING).withCause(t).log(
          "[PrefabsUploader] block review failed to load prefab %s", pending.prefabName());
    }
  }

  /** Flips the allow/block state of the clicked row's block type. */
  private void handleToggle(String action) {
    if (done) {
      sendUpdate();
      return;
    }
    int row = parseIdx(action, "Toggle");
    int idx = page * ROWS + row;
    if (idx >= 0 && idx < allowed.length) {
      allowed[idx] = !allowed[idx];
    }
    refresh();
  }

  /**
   * Filters the prefab to the allowed block/fluid types and approves it: rebuild + native
   * validation + write on the world thread, then resolvePending(true) on the hub.
   */
  private void handleConfirm() {
    if (done || loadFailed || selection == null) {
      sendUpdate();
      return;
    }
    Set<Integer> allowBlocks = new HashSet<>();
    Set<Integer> allowFluids = new HashSet<>();
    int kept = 0;
    for (int i = 0; i < entries.size(); i++) {
      if (allowed[i]) {
        kept++;
        PrefabBlockManifest.Entry e = entries.get(i);
        (e.fluid() ? allowFluids : allowBlocks).add(e.id());
      }
    }
    if (kept == 0) {
      sendUpdate();
      return;
    }
    done = true;
    final int total = entries.size();
    final int keptF = kept;
    final String adminName = playerRef.getUsername();
    final String adminUuid = String.valueOf(playerRef.getUuid());

    UICommandBuilder busy = new UICommandBuilder();
    busy.set("#Confirm.Disabled", true);
    busy.set(
        "#Summary.TextSpans", Message.translation("server.prefabsuploader.blockreview.saving"));
    sendUpdate(busy, false);

    hubClient
        .io()
        .execute(
            () -> {
              try {
                runOnWorldAwait(
                    () -> {
                      byte[] filtered =
                          PrefabBlockFilter.filter(selection, allowBlocks, allowFluids);
                      try {
                        PendingPrefabStore.get()
                            .approve(
                                pending,
                                filtered,
                                adminName,
                                adminUuid,
                                hubClient.maxPrefabsPerPlayer());
                      } catch (Throwable t) {
                        throw new RuntimeException(t);
                      }
                    });

                ResolvePendingResponse resp =
                    hubClient.resolvePending(pending.id(), true, adminName);
                if (!resp.getOk() && !resp.getError().isBlank()) {
                  LOG.at(Level.WARNING).log(
                      "[PrefabsUploader] resolvePending(approved) returned a hub error: %s",
                      resp.getError());
                }
                runOnWorld(
                    () -> {
                      UICommandBuilder b = new UICommandBuilder();
                      b.set(
                          "#Summary.TextSpans",
                          Message.translation("server.prefabsuploader.blockreview.approved")
                              .param("kept", keptF)
                              .param("total", total));
                      b.set("#Confirm.Disabled", true);
                      sendUpdate(b, false);
                      playerRef.sendMessage(
                          tagged(
                              Message.translation("server.prefabsuploader.blockreview.approvedChat")
                                  .param("name", sanitize(pending.prefabName()))));
                    });
              } catch (Throwable t) {
                done = false;
                final String reason = causeMessage(t);
                LOG.at(Level.WARNING).withCause(t).log(
                    "[PrefabsUploader] block-review approve failed for %s", pending.prefabName());
                runOnWorld(
                    () -> {
                      UICommandBuilder b = new UICommandBuilder();
                      b.set(
                          "#Summary.TextSpans",
                          Message.translation("server.prefabsuploader.blockreview.error")
                              .param("error", sanitize(reason)));
                      b.set("#Confirm.Disabled", false);
                      sendUpdate(b, false);
                    });
              }
            });
  }

  private void refresh() {
    UICommandBuilder b = new UICommandBuilder();
    populateList(b);
    updateSummary(b);
    sendUpdate(b, false);
  }

  /** Renders the current page of block rows (state glyph, name, count). */
  private void populateList(UICommandBuilder b) {
    int total = entries.size();
    int maxPage = Math.max(0, (total - 1) / ROWS);
    if (page > maxPage) {
      page = maxPage;
    }
    b.set(
        "#PageInfo.TextSpans",
        Message.translation("server.prefabsuploader.blockreview.pageInfo")
            .param("count", total)
            .param("page", page + 1)
            .param("pages", maxPage + 1));
    int start = page * ROWS;
    for (int i = 0; i < ROWS; i++) {
      int idx = start + i;
      if (idx < total) {
        PrefabBlockManifest.Entry e = entries.get(idx);
        boolean ok = allowed[idx];
        b.set("#Row" + i + ".Visible", true);
        b.set(
            "#RowState" + i + ".TextSpans", Message.raw(ok ? "ON" : "OFF").color(ok ? GREEN : RED));
        Color nameColor = !ok ? GRAY : (e.suspicious() ? ORANGE : WHITE);
        b.set("#RowName" + i + ".TextSpans", Message.raw(sanitize(e.name())).color(nameColor));
        String count = "x" + e.count() + (e.fluid() ? "  (fluid)" : "");
        b.set("#RowCount" + i + ".TextSpans", Message.raw(count).color(CYAN));
      } else {
        b.set("#Row" + i + ".Visible", false);
      }
    }
  }

  /** Updates the allowed/blocked summary line and the Confirm button's enabled state. */
  private void updateSummary(UICommandBuilder b) {
    if (loadFailed) {
      b.set(
          "#Summary.TextSpans",
          Message.translation("server.prefabsuploader.blockreview.loadError"));
      b.set("#Confirm.Disabled", true);
      return;
    }
    int allowedCount = 0;
    int blockedCount = 0;
    for (boolean v : allowed) {
      if (v) {
        allowedCount++;
      } else {
        blockedCount++;
      }
    }
    b.set(
        "#Summary.TextSpans",
        Message.translation("server.prefabsuploader.blockreview.summary")
            .param("allowed", allowedCount)
            .param("blocked", blockedCount));
    b.set("#Confirm.Disabled", done || entries.isEmpty() || allowedCount == 0);
  }
}
