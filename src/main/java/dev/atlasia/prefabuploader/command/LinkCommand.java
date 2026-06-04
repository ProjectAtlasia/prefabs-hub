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
import dev.atlasia.prefabuploader.config.PluginConfig;
import dev.atlasia.prefabuploader.grpc.PlayerImportResponse;
import dev.atlasia.prefabuploader.service.hub.Client;
import dev.atlasia.prefabuploader.service.messaging.LinkFlow;
import dev.atlasia.prefabuploader.service.messaging.StatusReply;
import dev.atlasia.prefabuploader.service.prefab.PlayerNameCache;
import dev.atlasia.prefabuploader.service.ratelimit.CommandRateLimiter;
import java.awt.Color;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * {@code /prefabs-uploader link} — links the player's Hytale account to Discord. If unlinked, shows
 * the link code and opens a short window that confirms in-game once the Discord {@code /link}
 * completes. Player-facing (no admin permission).
 */
public class LinkCommand extends AbstractCommand {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  private static final Color TAG = new Color(0xFF, 0xAA, 0x00);

  private final Client client;
  private final PluginConfig config;
  private final LinkFlow linkFlow;

  public LinkCommand(@Nonnull Client client, @Nonnull PluginConfig config) {
    super("link", "server.prefabsuploader.command.link.description");
    this.client = client;
    this.config = config;
    this.linkFlow = new LinkFlow(client, config);
    requirePermission("projectatlasia.prefabsuploader.command.link");
  }

  @Nullable
  @Override
  protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
    if (!context.isPlayer()) {
      context.sendMessage(Message.translation("server.prefabsuploader.link.notplayer"));
      return CompletableFuture.completedFuture(null);
    }
    PlayerRef sender = context.senderAs(PlayerRef.class);
    String uuid = sender.getUuid().toString();
    String username = sender.getUsername();
    PlayerNameCache.get().put(uuid, username);

    long wait = CommandRateLimiter.get().remainingCooldownSeconds(uuid);
    if (wait > 0) {
      context.sendMessage(
          tagged(
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
              case THREAD_OPENED, DM_OPENED ->
                  context.sendMessage(
                      tagged(Message.translation("server.prefabsuploader.link.alreadyLinked")));
              default -> StatusReply.send(context, res, config.inviteUrl());
            }
          } catch (Throwable t) {
            LOG.at(Level.WARNING).log(
                "[PrefabsUploader] link/playerImport failed: %s", t.getMessage());
            context.sendMessage(
                tagged(Message.translation("server.prefabsuploader.link.hubError")));
          }
        });
  }

  private static Message tagged(Message msg) {
    return Message.join(Message.raw("[PrefabsUploader] ").color(TAG), msg);
  }
}
