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
import dev.atlasia.prefabuploader.client.HubClient;
import dev.atlasia.prefabuploader.grpc.PlayerImportResponse;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * {@code /prefabs-uploader import} — per-player prefab upload flow. Asks the hub: if the account is
 * not linked, shows the link code; if it is, the hub opens a private Discord thread.
 */
public class ImportCommand extends AbstractCommand {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  private static final java.awt.Color TAG = new java.awt.Color(0xFF, 0xAA, 0x00);

  private final HubClient client;

  public ImportCommand(@Nonnull HubClient client) {
    super("import", "server.prefabsuploader.command.import.description");
    this.client = client;
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
    dev.atlasia.prefabuploader.prefab.PlayerNameCache.get().put(uuid, username);

    return CompletableFuture.runAsync(
        () -> {
          try {
            PlayerImportResponse res = client.playerImport(uuid, username);
            switch (res.getStatus()) {
              case NEEDS_LINK -> showNeedsLink(context);
              case THREAD_OPENED ->
                  context.sendMessage(
                      tagged(Message.translation("server.prefabsuploader.import.threadOpened")));
              default -> context.sendMessage(tagged(Message.raw(res.getMessage())));
            }
          } catch (Throwable t) {
            LOG.at(Level.WARNING).log("[PrefabsUploader] playerImport falhou: %s", t.getMessage());
            context.sendMessage(
                tagged(Message.translation("server.prefabsuploader.import.hubError")));
          }
        });
  }

  private void showNeedsLink(CommandContext context) {
    context.sendMessage(tagged(Message.translation("server.prefabsuploader.import.useLink")));
  }

  private static Message tagged(Message msg) {
    return Message.join(Message.raw("[PrefabsUploader] ").color(TAG), msg);
  }
}
