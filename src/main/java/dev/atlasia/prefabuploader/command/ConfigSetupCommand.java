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
import dev.atlasia.prefabuploader.grpc.SetupResponse;
import dev.atlasia.prefabuploader.service.hub.Client;
import java.awt.Color;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * {@code /prefabs-uploader config setup} — requests a pairing code from the hub and shows the admin
 * the instructions to enter on Discord. Permission-gated (admin).
 */
public class ConfigSetupCommand extends AbstractCommand {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  private static final Color TAG = new Color(0xFF, 0xAA, 0x00);
  private static final Color CODE = new Color(0x66, 0xDD, 0x77);
  private static final Color DISCORD = new Color(0x72, 0x89, 0xDA);

  private final Client client;

  public ConfigSetupCommand(@Nonnull Client client) {
    super("setup", "server.prefabsuploader.command.config.setup.description");
    this.client = client;
    requirePermission("projectatlasia.prefabsuploader.command.prefabsuploader.config.setup");
  }

  @Nullable
  @Override
  protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
    String requestedBy =
        context.isPlayer() ? context.senderAs(PlayerRef.class).getUsername() : "console";
    context.sendMessage(
        Message.join(
            Message.raw("[PrefabsUploader] ").color(TAG),
            Message.translation("server.prefabsuploader.config.setup.generating")));

    return CompletableFuture.runAsync(
        () -> {
          try {
            SetupResponse res = client.requestSetup(requestedBy);
            context.sendMessage(
                Message.join(
                    Message.raw("[PrefabsUploader] ").color(TAG),
                    Message.translation("server.prefabsuploader.config.setup.pairingCode"),
                    Message.raw(" "),
                    Message.translation("server.prefabsuploader.config.setup.pairingCode.value")
                        .param("code", res.getPairingCode())
                        .color(CODE)));
            if (!res.getBotInviteUrl().isEmpty()) {
              context.sendMessage(
                  Message.join(
                      Message.translation("server.prefabsuploader.config.setup.step1"),
                      Message.raw(" "),
                      Message.translation("server.prefabsuploader.config.setup.step1.button")
                          .color(DISCORD)
                          .link(res.getBotInviteUrl())));
            }
            context.sendMessage(
                Message.join(
                    Message.translation("server.prefabsuploader.config.setup.step2"),
                    Message.raw(" "),
                    Message.translation("server.prefabsuploader.config.setup.step2.command")
                        .param("code", res.getPairingCode())
                        .color(CODE)));
            context.sendMessage(Message.translation("server.prefabsuploader.config.setup.expires"));
            openSetupWindow(context);
          } catch (Throwable t) {
            LOG.at(Level.WARNING).log("[PrefabsUploader] requestSetup failed: %s", t.getMessage());
            context.sendMessage(
                Message.join(
                    Message.raw("[PrefabsUploader] ").color(TAG),
                    Message.translation("server.prefabsuploader.config.setup.hubError")));
          }
        });
  }

  /** Opens a 3-min window that confirms the pairing in-game when the hub reports configured. */
  private void openSetupWindow(CommandContext context) {
    if (!context.isPlayer()) {
      return;
    }
    PlayerRef admin = context.senderAs(PlayerRef.class);
    Client.Window window = client.openWindow(180);
    Runnable dereg =
        client.awaitConfigured(
            () -> {
              window.close();
              sendInGame(
                  admin,
                  Message.join(
                      Message.raw("[PrefabsUploader] ").color(TAG),
                      Message.translation("server.prefabsuploader.config.setup.paired")));
            });
    window.onExpire(dereg);
  }

  private static void sendInGame(PlayerRef ref, Message msg) {
    try {
      var world = Universe.get().getWorld(ref.getWorldUuid());
      if (world != null) {
        world.execute(() -> ref.sendMessage(msg));
      }
    } catch (Throwable t) {
      LOG.at(Level.FINE).log(
          "[PrefabsUploader] in-game setup confirmation failed: %s", t.getMessage());
    }
  }
}
