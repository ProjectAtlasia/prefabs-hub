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
package dev.atlasia.prefabuploader.command;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.atlasia.prefabuploader.client.HubClient;
import dev.atlasia.prefabuploader.grpc.PlayerImportResponse;
import dev.atlasia.prefabuploader.prefab.PlayerNameCache;
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
  private static final long LINK_WINDOW_SEC = 180;
  private static final java.awt.Color TAG = new java.awt.Color(0xFF, 0xAA, 0x00);
  private static final java.awt.Color CODE = new java.awt.Color(0x66, 0xDD, 0x77);
  private static final java.awt.Color DISCORD = new java.awt.Color(0x72, 0x89, 0xDA);

  private final HubClient client;

  public LinkCommand(@Nonnull HubClient client) {
    super("link", "server.prefabsuploader.command.link.description");
    this.client = client;
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

    return CompletableFuture.runAsync(
        () -> {
          try {
            PlayerImportResponse res = client.playerImport(uuid, username);
            switch (res.getStatus()) {
              case NEEDS_LINK -> startLinkFlow(context, sender, uuid, res);
              case THREAD_OPENED ->
                  context.sendMessage(
                      tagged(Message.translation("server.prefabsuploader.link.alreadyLinked")));
              default -> context.sendMessage(tagged(Message.raw(res.getMessage())));
            }
          } catch (Throwable t) {
            LOG.at(Level.WARNING).log(
                "[PrefabsUploader] link/playerImport falhou: %s", t.getMessage());
            context.sendMessage(
                tagged(Message.translation("server.prefabsuploader.link.hubError")));
          }
        });
  }

  private void startLinkFlow(
      CommandContext context, PlayerRef sender, String uuid, PlayerImportResponse res) {
    context.sendMessage(
        Message.join(
            Message.raw("[PrefabsUploader] ").color(TAG),
            Message.translation("server.prefabsuploader.link.needsLink"),
            Message.raw(" "),
            Message.translation("server.prefabsuploader.link.code")
                .param("code", res.getLinkCode())
                .color(CODE)));
    if (!res.getBotInviteUrl().isEmpty()) {
      context.sendMessage(
          Message.join(
              Message.translation("server.prefabsuploader.link.invitePrompt"),
              Message.raw(" "),
              Message.translation("server.prefabsuploader.link.inviteButton")
                  .color(DISCORD)
                  .link(res.getBotInviteUrl())));
    }
    context.sendMessage(tagged(Message.translation("server.prefabsuploader.link.waiting")));

    HubClient.Window window = client.openWindow(LINK_WINDOW_SEC);
    Runnable dereg =
        client.awaitPlayerLinked(
            uuid,
            linked -> {
              window.close();
              sendInGame(
                  sender,
                  Message.join(
                      Message.raw("[PrefabsUploader] ").color(TAG),
                      Message.translation("server.prefabsuploader.link.linked")
                          .param("discord", linked.getDiscordName())));
            });
    window.onExpire(dereg);
  }

  /** Sends a message to the player on the world thread (no-op if the world/player is gone). */
  private static void sendInGame(PlayerRef ref, Message msg) {
    try {
      var world = Universe.get().getWorld(ref.getWorldUuid());
      if (world != null) {
        world.execute(() -> ref.sendMessage(msg));
      }
    } catch (Throwable t) {
      LOG.at(Level.FINE).log(
          "[PrefabsUploader] confirmação in-game de link falhou: %s", t.getMessage());
    }
  }

  private static Message tagged(Message msg) {
    return Message.join(Message.raw("[PrefabsUploader] ").color(TAG), msg);
  }
}
