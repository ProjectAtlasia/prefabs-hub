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

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import dev.atlasia.prefabuploader.config.PluginConfig;
import dev.atlasia.prefabuploader.service.hub.Client;
import javax.annotation.Nonnull;

/** Root command {@code /prefabs-uploader}, with aliases {@code prefabsuploader} and {@code pu}. */
public class PrefabsUploaderCommand extends AbstractCommandCollection {

  public PrefabsUploaderCommand(@Nonnull Client client, @Nonnull PluginConfig config) {
    super("prefabsuploader", "server.prefabsuploader.command.root.description");
    addAliases("prefabs-uploader", "pu");
    addSubCommand(new ConfigCommand(client, config));
    addSubCommand(new ImportCommand(client));
    addSubCommand(new ValidateCommand(client));
    addSubCommand(new LinkCommand(client));
  }
}
