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
package dev.atlasia.prefabuploader.service.messaging;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.atlasia.prefabuploader.config.PluginConfig;
import dev.atlasia.prefabuploader.grpc.PlayerImportResponse;
import dev.atlasia.prefabuploader.service.hub.Client;
import dev.atlasia.prefabuploader.util.Messages;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Shared link flow for {@code /prefabs-uploader link} and {@code import} when the player's account
 * is not linked yet: shows the link code and invite, opens a short listening window, and confirms
 * the link in-game once the Discord {@code /link} completes. If the window expires first, the
 * waiter is dropped and the player is told to run the command again.
 */
public final class LinkFlow {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  private static final long WINDOW_SEC = 180;

  private final Client client;
  private final PluginConfig config;

  public LinkFlow(Client client, PluginConfig config) {
    this.client = client;
    this.config = config;
  }

  /**
   * Starts the link flow for an unlinked player: shows the code/invite, opens the listening window
   * and registers the in-game confirmation (or an expiry notice).
   *
   * @param context the command context used to reply to the player
   * @param sender the player to confirm in-game once linked
   * @param uuid the player UUID awaited on the hub's {@code PlayerLinked} signal
   * @param res the {@code NEEDS_LINK} response carrying the link code and invite URL
   */
  public void start(
      CommandContext context, PlayerRef sender, String uuid, PlayerImportResponse res) {
    sendInGame(
        sender,
        Message.join(
            Message.raw("[PrefabsUploader] ").color(Messages.TAG),
            Message.translation("server.prefabsuploader.link.needsLink"),
            Message.raw(" "),
            Message.translation("server.prefabsuploader.link.code")
                .param("code", res.getLinkCode())
                .color(Messages.CODE)));
    String invite = inviteUrl(res);
    if (!invite.isEmpty()) {
      sendInGame(
          sender,
          Message.join(
              Message.translation("server.prefabsuploader.link.invitePrompt"),
              Message.raw(" "),
              Message.translation("server.prefabsuploader.link.inviteButton")
                  .color(Messages.DISCORD)
                  .link(invite)));
    }
    sendInGame(sender, Messages.tagged(Message.translation("server.prefabsuploader.link.waiting")));

    AtomicBoolean settled = new AtomicBoolean(false);
    Client.Window window = client.openWindow(WINDOW_SEC);
    Runnable dereg =
        client.awaitPlayerLinked(
            uuid,
            linked -> {
              if (!settled.compareAndSet(false, true)) {
                return;
              }
              window.close();
              sendInGame(
                  sender,
                  Messages.tagged(
                      Message.translation("server.prefabsuploader.link.linked")
                          .param("discord", linked.getDiscordName())));
            });
    window.onExpire(
        () -> {
          if (!settled.compareAndSet(false, true)) {
            return;
          }
          dereg.run();
          sendInGame(
              sender, Messages.tagged(Message.translation("server.prefabsuploader.link.expired")));
        });
  }

  /** Guild invite from the hub ({@code /setup invite}); falls back to the plugin config invite. */
  private String inviteUrl(PlayerImportResponse res) {
    String hub = res.getGuildInviteUrl();
    return (hub == null || hub.isEmpty()) ? config.inviteUrl() : hub;
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
          "[PrefabsUploader] in-game link confirmation failed: %s", t.getMessage());
    }
  }
}
