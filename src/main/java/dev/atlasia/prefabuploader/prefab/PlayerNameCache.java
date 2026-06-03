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
package dev.atlasia.prefabuploader.prefab;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Cache persistente {@code UUID Hytale -> username}, pra exibir o nome IN-GAME do autor no {@code
 * /pu validate} (o upload em si só carrega o nome do Discord).
 *
 * <p>Populado quando temos o {@link PlayerRef} (no {@code /import}) e resolvido sob demanda pra
 * jogadores online via {@link Universe}. Persistido em {@code
 * prefabsuploader/player-names.properties}. Autor: astahjmo (Astaroth).
 */
public final class PlayerNameCache {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  private static final PlayerNameCache INSTANCE = new PlayerNameCache();

  public static PlayerNameCache get() {
    return INSTANCE;
  }

  private final Path file = Paths.get("prefabsuploader", "player-names.properties");
  private final Properties props = new Properties();

  private PlayerNameCache() {
    if (Files.exists(file)) {
      try (InputStream in = Files.newInputStream(file)) {
        props.load(in);
      } catch (IOException e) {
        LOG.at(Level.WARNING).log(
            "[PrefabsUploader] falha ao ler cache de nomes: %s", e.getMessage());
      }
    }
  }

  /** Registra/atualiza o username in-game de um UUID (chamado no {@code /import}). */
  public void put(String uuid, String username) {
    if (uuid == null || uuid.isBlank() || username == null || username.isBlank()) {
      return;
    }
    if (username.equals(props.getProperty(uuid))) {
      return;
    }
    props.setProperty(uuid, username);
    save();
  }

  /**
   * Resolve o username in-game de um UUID: cache → jogador online ({@link Universe}) → {@code
   * null}. Quando acha online, cacheia. Rode na world thread (faz lookup no Universe).
   */
  public String resolve(String uuid) {
    if (uuid == null || uuid.isBlank()) {
      return null;
    }
    String cached = props.getProperty(uuid);
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
      LOG.at(Level.FINE).log("[PrefabsUploader] resolve nome %s falhou: %s", uuid, t.getMessage());
    }
    return null;
  }

  private synchronized void save() {
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      try (OutputStream out = Files.newOutputStream(tmp)) {
        props.store(out, "PrefabsUploader — cache UUID->username (gerado, nao editar)");
      }
      Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      LOG.at(Level.SEVERE).log(
          "[PrefabsUploader] falha ao salvar cache de nomes: %s", e.getMessage());
    }
  }
}
