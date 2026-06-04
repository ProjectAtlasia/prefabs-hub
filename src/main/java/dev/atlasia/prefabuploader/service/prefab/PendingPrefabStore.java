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
package dev.atlasia.prefabuploader.service.prefab;

import com.hypixel.hytale.builtin.blockphysics.PrefabBufferValidator;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.ValidationOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * Approves server prefabs: validates the bytes with the engine's native validator (via a temp file,
 * which loadBuffer requires) and writes the original bytes into the live {@code
 * getServerPrefabsPath()}.
 */
public final class PendingPrefabStore {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  private static final String SUFFIX = ".prefab.json";
  private static final int MAX_NAME_LEN = 48;
  private static final PendingPrefabStore INSTANCE = new PendingPrefabStore();

  private static final Set<ValidationOption> VALIDATION =
      EnumSet.of(
          ValidationOption.BLOCKS,
          ValidationOption.BLOCK_STATES,
          ValidationOption.ENTITIES,
          ValidationOption.BLOCK_FILLER);

  private final ReentrantLock lock = new ReentrantLock();

  public static PendingPrefabStore get() {
    return INSTANCE;
  }

  private PendingPrefabStore() {}

  /**
   * Approves: validates the bytes with the engine's NATIVE validator and writes them into the live
   * {@code Prefabs/}. Runs on the world thread (loadBuffer/validate touch the engine). Throws
   * {@link IOException} on rejection/error so the caller can surface it without writing anything.
   *
   * @param p pending prefab metadata (id/name/owner)
   * @param data bytes of the {@code .prefab.json}
   * @param adminName username of the approving admin (audit)
   * @param adminUuid uuid of the approving admin (audit)
   * @throws IOException if the data is missing, rejected by validation, or the write fails
   */
  public void approve(PendingPrefab p, byte[] data, String adminName, String adminUuid)
      throws IOException {
    if (data == null || data.length == 0) {
      throw new IOException("sem dados do prefab (não baixado?)");
    }
    String owner = ownerDir(p);
    String base = baseName(p.prefabName());
    String display = owner + "/" + base;

    lock.lock();
    try {
      Path temp = Files.createTempFile("pu-approve-", SUFFIX);
      try {
        Files.write(temp, data);
        String issues = nativeValidate(temp);
        if (issues != null && !issues.isBlank()) {
          LOG.at(Level.INFO).log(
              "[PrefabsUploader] aprovação rejeitada pelo engine (%s): %s", display, issues);
          throw new IOException("rejeitado pela validação do servidor: " + issues);
        }
      } finally {
        try {
          Files.deleteIfExists(temp);
        } catch (Throwable t) {
          LOG.at(Level.FINE).log(
              "[PrefabsUploader] falha ao apagar temp %s: %s", temp, t.getMessage());
        }
      }

      Path liveRoot = PrefabStore.get().getServerPrefabsPath();
      Path dest = liveRoot.resolve(owner + "_" + base + SUFFIX);
      Files.createDirectories(dest.getParent());

      Path liveRootReal = liveRoot.toRealPath();
      Path destParentReal = dest.getParent().toRealPath();
      if (!destParentReal.startsWith(liveRootReal)) {
        throw new IOException("destino inválido (fora da árvore de prefabs)");
      }
      String destName = dest.getFileName().toString();
      if (destName.contains("/") || destName.contains("\\") || destName.contains("..")) {
        throw new IOException("nome de destino inválido");
      }
      if (Files.isSymbolicLink(dest)) {
        throw new IOException("destino é symlink — recusado");
      }

      Files.write(dest, data);

      LOG.at(Level.INFO).log(
          "[PrefabsUploader] AUDITORIA aprovar: admin=%s(%s) prefab=%s owner=%s dest=%s bytes=%d at=%d",
          safe(adminName),
          safe(adminUuid),
          base,
          owner,
          dest.getFileName(),
          data.length,
          System.currentTimeMillis());
    } finally {
      lock.unlock();
    }
  }

  /**
   * Runs the engine's native validator over the given file.
   *
   * @param file the temp file holding the prefab bytes
   * @return a description of the problems found, or null/empty if the prefab is valid; a non-empty
   *     error string when the validator is unavailable (fail-secure)
   */
  private static String nativeValidate(Path file) {
    try {
      IPrefabBuffer buffer = PrefabBufferUtil.loadBuffer(file).newAccess();
      return PrefabBufferValidator.validate(buffer, VALIDATION);
    } catch (Throwable t) {
      LOG.at(Level.WARNING).withCause(t).log(
          "[PrefabsUploader] validação nativa indisponível — rejeitando aprovação (fail-secure)");
      return "validação nativa indisponível";
    }
  }

  /**
   * Owner subfolder: prefers the Hytale UUID, falls back to the Discord id, otherwise anonymous.
   */
  private static String ownerDir(PendingPrefab p) {
    String uuid = p.playerUuid();
    if (uuid != null && !uuid.isEmpty()) {
      String u = uuid.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
      return "player_" + (u.length() <= 16 ? u : u.substring(0, 16));
    }
    String digits =
        p.uploaderDiscordId() == null ? "" : p.uploaderDiscordId().replaceAll("[^0-9]", "");
    if (digits.isEmpty()) {
      return "anon";
    }
    return "discord_" + (digits.length() <= 8 ? digits : digits.substring(digits.length() - 8));
  }

  /** File base name: charset sanitization, anti path-traversal and collision handling. */
  private static String baseName(String rawName) {
    String raw = rawName == null ? "" : rawName;
    String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    cleaned = cleaned.replaceAll("_+", "_").replaceAll("^_|_$", "");
    if (cleaned.isEmpty()) {
      cleaned = "prefab";
    }
    if (cleaned.length() > MAX_NAME_LEN) {
      cleaned = cleaned.substring(0, MAX_NAME_LEN);
    }
    return cleaned;
  }

  private static String safe(String s) {
    return (s == null || s.isBlank()) ? "?" : s;
  }
}
