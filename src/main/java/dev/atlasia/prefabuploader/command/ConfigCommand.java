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

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import dev.atlasia.prefabuploader.config.PluginConfig;
import dev.atlasia.prefabuploader.service.hub.Client;
import javax.annotation.Nonnull;

/** Sub-command collection {@code /prefabs-uploader config}. */
public class ConfigCommand extends AbstractCommandCollection {

  public ConfigCommand(@Nonnull Client client, @Nonnull PluginConfig config) {
    super("config", "server.prefabsuploader.command.config.description");
    addSubCommand(new ConfigSetupCommand(client));
    addSubCommand(new ConfigPairMessageCommand(config));
  }
}
