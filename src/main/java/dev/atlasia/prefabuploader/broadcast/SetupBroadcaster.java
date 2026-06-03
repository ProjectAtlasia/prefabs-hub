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
package dev.atlasia.prefabuploader.broadcast;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.atlasia.prefabuploader.client.HubClient;
import java.util.logging.Level;

/**
 * While the server is not yet paired, periodically warns online players that the Discord
 * integration must be configured, including the bot install link. Stops once configured.
 */
public final class SetupBroadcaster {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  private static final long INTERVAL_MS = 60_000;

  private static final java.awt.Color TAG = new java.awt.Color(0xFF, 0xAA, 0x00);
  private static final java.awt.Color DISCORD = new java.awt.Color(0x72, 0x89, 0xDA);

  private final HubClient client;
  private Thread thread;
  private volatile boolean running = false;

  public SetupBroadcaster(HubClient client) {
    this.client = client;
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
                  if (client.isConfigured()) {
                    continue;
                  }
                  if (Universe.get().getPlayerCount() == 0) {
                    continue;
                  }
                  Universe.get().sendMessage(buildMessage());
                  LOG.at(Level.INFO).log("[PrefabsUploader] broadcast de setup enviado");
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
                } catch (Throwable t) {
                  LOG.at(Level.WARNING).log(
                      "[PrefabsUploader] broadcast falhou: %s", t.getMessage());
                }
              }
            },
            "PrefabsUploader-Broadcast");
    thread.setDaemon(true);
    thread.start();
    LOG.at(Level.INFO).log(
        "[PrefabsUploader] broadcaster iniciado (intervalo %ds)", INTERVAL_MS / 1000);
  }

  private Message buildMessage() {
    String url = client.inviteUrl();
    Message base =
        Message.join(
            Message.raw("[PrefabsUploader] ").color(TAG),
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
            .color(DISCORD)
            .link(url));
  }

  public void stop() {
    running = false;
    if (thread != null) {
      thread.interrupt();
    }
  }
}
