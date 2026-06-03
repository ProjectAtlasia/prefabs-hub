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
package dev.atlasia.prefabuploader;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.atlasia.prefabuploader.broadcast.SetupBroadcaster;
import dev.atlasia.prefabuploader.client.HubClient;
import dev.atlasia.prefabuploader.command.PrefabsUploaderCommand;
import dev.atlasia.prefabuploader.config.PluginConfig;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Entry point do PrefabsUploader.
 *
 * <p>Permite que o jogador envie seus prefabs locais pro servidor via Discord. Sob o modelo PULL, o
 * bot NÃO empurra mais nada e o servidor NÃO guarda o pendente em disco: o anexo vive no CDN do
 * Discord. Quando a staff abre {@code /prefabs-uploader validate}, o plugin lista os pendentes
 * (metadados via {@code ListPending}), baixa o selecionado EM MEMÓRIA pra preview e só grava no
 * storage vivo ({@link com.hypixel.hytale.server.core.prefab.PrefabStore}) ao APROVAR.
 *
 * <p>Mod: prefabs-uploader. Autor: astahjmo (Astaroth). Ver {@code docs/DESIGN.md}.
 */
public class PrefabsUploaderPlugin extends JavaPlugin {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();

  // Pasta de dados do mod dentro de mods/ (mesma convenção dos outros mods: <Group>_<Name>).
  private static final String MOD_FOLDER = "ProjectAtlasia_PrefabsUploader";

  private PluginConfig config;
  private HubClient hubClient;
  private SetupBroadcaster broadcaster;

  public PrefabsUploaderPlugin(@Nonnull JavaPluginInit init) {
    super(init);
  }

  /** Pasta de dados do mod: {@code <mods>/ProjectAtlasia_PrefabsUploader/} (mods = pai do jar). */
  private java.nio.file.Path dataDir() {
    try {
      java.nio.file.Path jar = getFile();
      if (jar != null && jar.getParent() != null) {
        return jar.getParent().resolve(MOD_FOLDER);
      }
    } catch (Throwable t) {
      LOG.at(Level.WARNING).log(
          "[PrefabsUploader] não resolvi a pasta do mod via getFile(): %s", t.getMessage());
    }
    // Fallback: mods/<folder> relativo ao diretório de trabalho do servidor.
    return java.nio.file.Paths.get("mods", MOD_FOLDER);
  }

  @Override
  protected void setup() {
    LOG.at(Level.INFO).log("[PrefabsUploader] setup()");

    this.config = PluginConfig.load(dataDir());
    this.hubClient = new HubClient(config);
    this.broadcaster = new SetupBroadcaster(hubClient);

    this.getCommandRegistry().registerCommand(new PrefabsUploaderCommand(hubClient));

    // Blindagem: a integração (rede/hub) NUNCA pode derrubar o boot. Se falhar, o plugin
    // segue carregado, o comando registrado, e o cliente tenta reconectar em background.
    try {
      hubClient.start();
      broadcaster.start();
    } catch (Throwable t) {
      LOG.at(Level.SEVERE).log(
          "[PrefabsUploader] falha ao iniciar integração (plugin segue carregado): %s",
          t.getMessage());
    }

    // [PULL] Sem scheduler de limpeza local: o pendente não vive mais em disco do servidor (vive no
    // CDN do Discord; o hub gerencia expiração/limpeza). Nada a limpar aqui.

    LOG.at(Level.INFO).log(
        "[PrefabsUploader] habilitado (server.id=%s, hub=%s).",
        config.serverId(), config.hubAddress());
  }

  @Override
  protected void shutdown() {
    LOG.at(Level.INFO).log("[PrefabsUploader] shutdown()");
    if (broadcaster != null) {
      broadcaster.stop();
    }
    if (hubClient != null) {
      hubClient.shutdown();
    }
    if (config != null) {
      config.save();
    }
  }
}
