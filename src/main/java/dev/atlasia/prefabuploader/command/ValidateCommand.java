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
package dev.atlasia.prefabuploader.command;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.atlasia.prefabuploader.grpc.ListPendingResponse;
import dev.atlasia.prefabuploader.service.hub.Client;
import dev.atlasia.prefabuploader.service.prefab.PendingPrefab;
import dev.atlasia.prefabuploader.ui.PrefabValidationPage;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * {@code /prefabs-uploader validate} — opens the pending-prefab validation UI (admin only).
 *
 * <p>The pending list is fetched from the hub via {@code ListPending}. That call is blocking, so it
 * runs on the {@link Client} I/O executor and the page is opened afterwards on the world thread.
 */
public class ValidateCommand extends AbstractCommand {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();

  private final Client hubClient;

  public ValidateCommand(@Nonnull Client hubClient) {
    super("validate", "server.prefabsuploader.command.validate.description");
    requirePermission("projectatlasia.prefabsuploader.command.validate");
    this.hubClient = hubClient;
  }

  @Nullable
  @Override
  protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
    if (!ctx.isPlayer()) {
      ctx.sendMessage(Message.translation("server.prefabsuploader.validate.notplayer"));
      return CompletableFuture.completedFuture(null);
    }
    PlayerRef sender = ctx.senderAs(PlayerRef.class);

    hubClient
        .io()
        .execute(
            () -> {
              List<PendingPrefab> items;
              try {
                ListPendingResponse resp = hubClient.listPending();
                items = resp.getItemsList().stream().map(PendingPrefab::fromProto).toList();
              } catch (Throwable t) {
                LOG.at(Level.WARNING).log(
                    "[PrefabsUploader] ListPending failed: %s", t.getMessage());
                sendInGame(
                    sender,
                    Message.join(
                        Message.raw("[PrefabsUploader] "),
                        Message.translation("server.prefabsuploader.ui.hubOffline")));
                return;
              }
              openPage(sender, items);
            });
    return CompletableFuture.completedFuture(null);
  }

  /** Sends a message to the player on the world thread (no-op if the world is gone). */
  private static void sendInGame(PlayerRef ref, Message msg) {
    try {
      var world = Universe.get().getWorld(ref.getWorldUuid());
      if (world != null) {
        world.execute(() -> ref.sendMessage(msg));
      }
    } catch (Throwable t) {
      LOG.at(Level.FINE).log("[PrefabsUploader] in-game message failed: %s", t.getMessage());
    }
  }

  /** Opens the page on the player's world thread with the already-loaded pending list. */
  private void openPage(PlayerRef sender, List<PendingPrefab> items) {
    var world = Universe.get().getWorld(sender.getWorldUuid());
    if (world == null) {
      return;
    }
    world.execute(
        () -> {
          var store = world.getEntityStore().getStore();
          Player player = store.getComponent(sender.getReference(), Player.getComponentType());
          if (player == null) {
            return;
          }
          player
              .getPageManager()
              .openCustomPage(
                  sender.getReference(), store, new PrefabValidationPage(sender, hubClient, items));
        });
  }
}
