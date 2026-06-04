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
package dev.atlasia.prefabuploader;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.atlasia.prefabuploader.command.PrefabsUploaderCommand;
import dev.atlasia.prefabuploader.config.PluginConfig;
import dev.atlasia.prefabuploader.service.broadcast.SetupBroadcaster;
import dev.atlasia.prefabuploader.service.hub.Client;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Entry point of PrefabsUploader. Players send local prefabs to the server via Discord (PULL
 * model): the attachment lives on Discord's CDN and is only written to {@link
 * com.hypixel.hytale.server.core.prefab.PrefabStore} upon approval through {@code /prefabs-uploader
 * validate}.
 */
public class PrefabsUploaderPlugin extends JavaPlugin {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();

  private static final String MOD_FOLDER = "ProjectAtlasia_PrefabsUploader";

  private PluginConfig config;
  private Client hubClient;
  private SetupBroadcaster broadcaster;

  public PrefabsUploaderPlugin(@Nonnull JavaPluginInit init) {
    super(init);
  }

  /**
   * Resolves the mod data directory at {@code <mods>/ProjectAtlasia_PrefabsUploader/}, where {@code
   * mods} is the jar's parent folder.
   *
   * @return the mod data directory path
   */
  private Path dataDir() {
    try {
      Path jar = getFile();
      if (jar != null && jar.getParent() != null) {
        return jar.getParent().resolve(MOD_FOLDER);
      }
    } catch (Throwable t) {
      LOG.at(Level.WARNING).log(
          "[PrefabsUploader] failed to resolve the mod folder via getFile(): %s", t.getMessage());
    }
    return Paths.get("mods", MOD_FOLDER);
  }

  @Override
  protected void setup() {
    LOG.at(Level.INFO).log("[PrefabsUploader] setup()");

    this.config = PluginConfig.load(dataDir());
    this.hubClient = new Client(config);
    this.broadcaster = new SetupBroadcaster(hubClient, config);

    this.getCommandRegistry().registerCommand(new PrefabsUploaderCommand(hubClient, config));

    try {
      hubClient.start();
      broadcaster.start();
    } catch (Throwable t) {
      LOG.at(Level.SEVERE).log(
          "[PrefabsUploader] failed to start integration (plugin stays loaded): %s",
          t.getMessage());
    }

    LOG.at(Level.INFO).log(
        "[PrefabsUploader] enabled (server.id=%s, hub=%s).",
        config.serverId(), config.hubAddress());
  }

  @Override
  protected void shutdown() {
    LOG.at(Level.INFO).log("[PrefabsUploader] shutdown()");
    shutdownStep(broadcaster, "broadcaster", () -> broadcaster.stop());
    shutdownStep(hubClient, "hub client", () -> hubClient.shutdown());
    shutdownStep(config, "config", () -> config.save());
  }

  /**
   * Runs a shutdown action only if its component was initialized, isolating failures so one
   * component's error never blocks the others.
   *
   * @param component the component to tear down, or {@code null} if never initialized
   * @param name component name used in the failure log
   * @param action the teardown action
   */
  private static void shutdownStep(Object component, String name, Runnable action) {
    if (component == null) {
      return;
    }
    try {
      action.run();
    } catch (Throwable t) {
      LOG.at(Level.WARNING).log(
          "[PrefabsUploader] failed to shut down %s: %s", name, t.getMessage());
    }
  }
}
