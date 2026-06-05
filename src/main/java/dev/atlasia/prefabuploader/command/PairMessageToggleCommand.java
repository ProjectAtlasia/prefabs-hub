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

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import dev.atlasia.prefabuploader.config.PluginConfig;
import dev.atlasia.prefabuploader.util.Messages;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Leaf of {@code /prefabs-uploader config pair-message}: enables or disables the automatic pairing
 * broadcast (commands keep working regardless). Persisted in the plugin config. Admin-gated.
 */
public class PairMessageToggleCommand extends AbstractCommand {

  private final PluginConfig config;
  private final boolean enabled;

  public PairMessageToggleCommand(
      @Nonnull String name, boolean enabled, @Nonnull PluginConfig config) {
    super(name, "server.prefabsuploader.command.config.pairMessage.description");
    this.config = config;
    this.enabled = enabled;
    requirePermission("projectatlasia.prefabsuploader.command.config.pair-message." + name);
  }

  @Nullable
  @Override
  protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
    config.setPairMessage(enabled);
    context.sendMessage(
        Messages.tagged(
            Message.translation(
                enabled
                    ? "server.prefabsuploader.config.pairMessage.on"
                    : "server.prefabsuploader.config.pairMessage.off")));
    return CompletableFuture.completedFuture(null);
  }
}
