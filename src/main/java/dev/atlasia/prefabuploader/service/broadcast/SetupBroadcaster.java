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
package dev.atlasia.prefabuploader.service.broadcast;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.atlasia.prefabuploader.config.PluginConfig;
import dev.atlasia.prefabuploader.service.hub.Client;
import dev.atlasia.prefabuploader.util.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * While the server is not yet paired, periodically warns online players that the Discord
 * integration must be configured, including the bot install link. Stops once configured.
 */
public final class SetupBroadcaster {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  private static final long INTERVAL_MS = 60_000;
  private static final String SETUP_PERMISSION =
      "projectatlasia.prefabsuploader.command.config.setup";

  private final Client client;
  private final PluginConfig config;
  private Thread thread;
  private volatile boolean running = false;

  public SetupBroadcaster(Client client, PluginConfig config) {
    this.client = client;
    this.config = config;
  }

  public void start() {
    if (running) {
      return;
    }
    running = true;
    thread =
        new Thread(
            () -> {
              while (running) {
                try {
                  Thread.sleep(INTERVAL_MS);
                  if (!running) {
                    break;
                  }
                  if (!config.pairMessage()) {
                    continue;
                  }
                  if (client.isConfigured()) {
                    continue;
                  }
                  broadcastToAdmins();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
                } catch (Throwable t) {
                  LOG.at(Level.WARNING).log(
                      "[PrefabsUploader] broadcast failed: %s", t.getMessage());
                }
              }
            },
            "PrefabsUploader-Broadcast");
    thread.setDaemon(true);
    thread.start();
    LOG.at(Level.INFO).log(
        "[PrefabsUploader] broadcaster started (interval %ds)", INTERVAL_MS / 1000);
  }

  /**
   * Sends the setup reminder only to online admins (holders of {@link #SETUP_PERMISSION}), each on
   * its own world thread so the chat packets never race the world loop. Players whose world cannot
   * be resolved are skipped. Runs off the world thread (the player snapshot is copied defensively).
   */
  private void broadcastToAdmins() {
    Universe universe = Universe.get();
    List<PlayerRef> players = new ArrayList<>(universe.getPlayers());
    if (players.isEmpty()) {
      return;
    }
    Message message = buildMessage();
    for (PlayerRef player : players) {
      World world = universe.getWorld(player.getWorldUuid());
      if (world == null) {
        continue;
      }
      world.execute(
          () -> {
            if (player.hasPermission(SETUP_PERMISSION)) {
              player.sendMessage(message);
            }
          });
    }
    LOG.at(Level.FINE).log(
        "[PrefabsUploader] setup reminder dispatched to admins (%d online)", players.size());
  }

  private Message buildMessage() {
    String url = client.inviteUrl();
    Message base =
        Message.join(
            Message.raw("[PrefabsUploader] ").color(Messages.TAG),
            Message.translation("server.prefabsuploader.broadcast.setup.prefix"),
            Message.raw(" "));
    if (url == null || url.isEmpty()) {
      return Message.join(
          base, Message.translation("server.prefabsuploader.broadcast.setup.noHub"));
    }
    return Message.join(
        base,
        Message.translation("server.prefabsuploader.broadcast.setup.installPrompt"),
        Message.raw(" "),
        Message.translation("server.prefabsuploader.broadcast.setup.installButton")
            .color(Messages.DISCORD)
            .link(url));
  }

  public void stop() {
    running = false;
    if (thread != null) {
      thread.interrupt();
    }
  }
}
