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
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.atlasia.prefabuploader.config.PluginConfig;
import dev.atlasia.prefabuploader.grpc.PlayerImportResponse;
import dev.atlasia.prefabuploader.service.hub.Client;
import dev.atlasia.prefabuploader.service.messaging.LinkFlow;
import dev.atlasia.prefabuploader.service.messaging.StatusReply;
import dev.atlasia.prefabuploader.service.prefab.PlayerNameCache;
import dev.atlasia.prefabuploader.service.ratelimit.CommandRateLimiter;
import dev.atlasia.prefabuploader.util.Messages;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * {@code /prefabs-uploader import} — per-player prefab upload flow. Asks the hub: if the account is
 * not linked, runs the link flow; if it is, the hub opens a private Discord thread (or a DM
 * fallback when the bot lacks thread permissions). Gated by the permission node {@code
 * projectatlasia.prefabsuploader.command.import}, so the server owner controls who may run it
 * (grant that node to the desired group/players).
 */
public class ImportCommand extends AbstractCommand {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();

  private final Client client;
  private final PluginConfig config;
  private final LinkFlow linkFlow;

  public ImportCommand(@Nonnull Client client, @Nonnull PluginConfig config) {
    super("import", "server.prefabsuploader.command.import.description");
    this.client = client;
    this.config = config;
    this.linkFlow = new LinkFlow(client, config);
    requirePermission("projectatlasia.prefabsuploader.command.import");
  }

  @Nullable
  @Override
  protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
    if (!context.isPlayer()) {
      context.sendMessage(Message.translation("server.prefabsuploader.import.notplayer"));
      return CompletableFuture.completedFuture(null);
    }
    PlayerRef sender = context.senderAs(PlayerRef.class);
    String uuid = sender.getUuid().toString();
    String username = sender.getUsername();
    PlayerNameCache.get().put(uuid, username);

    long wait = CommandRateLimiter.get().remainingCooldownSeconds(uuid);
    if (wait > 0) {
      context.sendMessage(
          Messages.tagged(
              Message.translation("server.prefabsuploader.ratelimit.wait")
                  .param("seconds", String.valueOf(wait))));
      return CompletableFuture.completedFuture(null);
    }

    return CompletableFuture.runAsync(
        () -> {
          try {
            PlayerImportResponse res = client.playerImport(uuid, username);
            switch (res.getStatus()) {
              case NEEDS_LINK -> linkFlow.start(context, sender, uuid, res);
              case THREAD_OPENED ->
                  sendInGame(
                      sender,
                      Messages.tagged(
                          Message.translation("server.prefabsuploader.import.threadOpened")));
              case DM_OPENED ->
                  sendInGame(
                      sender,
                      Messages.tagged(
                          Message.translation("server.prefabsuploader.import.dmOpened")));
              default -> StatusReply.send(context, res, config.inviteUrl());
            }
          } catch (Throwable t) {
            LOG.at(Level.WARNING).log("[PrefabsUploader] playerImport failed: %s", t.getMessage());
            sendInGame(
                sender,
                Messages.tagged(Message.translation("server.prefabsuploader.import.hubError")));
          }
        });
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
}
