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
package dev.atlasia.prefabuploader.service.prefab;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Persistent {@code Hytale UUID -> username} cache stored in {@code
 * prefabsuploader/player-names.properties}, used to display an author's in-game name.
 */
public final class PlayerNameCache {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  private static final PlayerNameCache INSTANCE = new PlayerNameCache();

  public static PlayerNameCache get() {
    return INSTANCE;
  }

  private final Path file = Paths.get("prefabsuploader", "player-names.properties");
  private final Map<String, String> cache = new ConcurrentHashMap<>();

  private PlayerNameCache() {
    if (Files.exists(file)) {
      try (InputStream in = Files.newInputStream(file)) {
        Properties props = new Properties();
        props.load(in);
        props.forEach((k, v) -> cache.put(String.valueOf(k), String.valueOf(v)));
      } catch (IOException e) {
        LOG.at(Level.WARNING).log(
            "[PrefabsUploader] failed to read name cache: %s", e.getMessage());
      }
    }
  }

  /**
   * Stores or updates the in-game username associated with a UUID.
   *
   * @param uuid the player's UUID
   * @param username the player's in-game username
   */
  public void put(String uuid, String username) {
    if (uuid == null || uuid.isBlank() || username == null || username.isBlank()) {
      return;
    }
    String previous = cache.put(uuid, username);
    if (username.equals(previous)) {
      return;
    }
    save();
  }

  /**
   * Resolves the in-game username for a UUID, trying the cache first and then online players via
   * {@link Universe}, caching the result when found online. The in-memory cache is thread-safe; the
   * {@link Universe} lookup is only reached for an uncached UUID and should be done on the world
   * thread.
   *
   * @param uuid the player's UUID
   * @return the resolved username, or {@code null} if it cannot be determined
   */
  public String resolve(String uuid) {
    if (uuid == null || uuid.isBlank()) {
      return null;
    }
    String cached = cache.get(uuid);
    if (cached != null) {
      return cached;
    }
    try {
      PlayerRef ref = Universe.get().getPlayer(UUID.fromString(uuid));
      if (ref != null) {
        String name = ref.getUsername();
        if (name != null && !name.isBlank()) {
          put(uuid, name);
          return name;
        }
      }
    } catch (Throwable t) {
      LOG.at(Level.FINE).log("[PrefabsUploader] name resolve %s failed: %s", uuid, t.getMessage());
    }
    return null;
  }

  private synchronized void save() {
    try {
      Properties props = new Properties();
      props.putAll(cache);
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      try (OutputStream out = Files.newOutputStream(tmp)) {
        props.store(out, "PrefabsUploader — UUID->username cache (generated, do not edit)");
      }
      Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      LOG.at(Level.SEVERE).log("[PrefabsUploader] failed to save name cache: %s", e.getMessage());
    }
  }
}
