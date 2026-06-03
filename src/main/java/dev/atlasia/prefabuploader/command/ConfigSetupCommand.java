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
import dev.atlasia.prefabuploader.client.HubClient;
import dev.atlasia.prefabuploader.grpc.SetupResponse;
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
  private static final java.awt.Color TAG = new java.awt.Color(0xFF, 0xAA, 0x00);
  private static final java.awt.Color CODE = new java.awt.Color(0x66, 0xDD, 0x77);
  private static final java.awt.Color DISCORD = new java.awt.Color(0x72, 0x89, 0xDA);

  private final HubClient client;

  public ConfigSetupCommand(@Nonnull HubClient client) {
    super("setup", "server.prefabsuploader.command.config.setup.description");
    this.client = client;
    requirePermission("projectatlasia.prefabsuploader.command.prefabsuploader.config.setup");
  }

  @Nullable
  @Override
  protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
    String requestedBy =
        context.isPlayer()
            ? context
                .senderAs(com.hypixel.hytale.server.core.universe.PlayerRef.class)
                .getUsername()
            : "console";
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
          } catch (Throwable t) {
            LOG.at(Level.WARNING).log("[PrefabsUploader] requestSetup falhou: %s", t.getMessage());
            context.sendMessage(
                Message.join(
                    Message.raw("[PrefabsUploader] ").color(TAG),
                    Message.translation("server.prefabsuploader.config.setup.hubError")));
          }
        });
  }
}
