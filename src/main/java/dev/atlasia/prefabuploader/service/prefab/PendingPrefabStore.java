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
import java.util.stream.Stream;

/**
 * Approves server prefabs: validates the bytes with the engine's native validator (via a temp file,
 * which loadBuffer requires) and writes the original bytes into the live {@code
 * getServerPrefabsPath()}.
 */
public final class PendingPrefabStore {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  private static final String SUFFIX = ".prefab.json";
  private static final int MAX_NAME_LEN = 48;
  private static final int MAX_COLLISION_SUFFIX = 1000;
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
   * @param maxPerOwner the maximum number of prefabs a single owner may keep (quota)
   * @throws IOException if the data is missing, rejected by validation, over quota, or the write
   *     fails
   */
  public void approve(
      PendingPrefab p, byte[] data, String adminName, String adminUuid, int maxPerOwner)
      throws IOException {
    if (data == null || data.length == 0) {
      throw new IOException("no prefab data (not downloaded?)");
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
              "[PrefabsUploader] approval rejected by the engine (%s): %s", display, issues);
          throw new IOException("rejected by server validation: " + issues);
        }
      } finally {
        try {
          Files.deleteIfExists(temp);
        } catch (Throwable t) {
          LOG.at(Level.FINE).log(
              "[PrefabsUploader] failed to delete temp %s: %s", temp, t.getMessage());
        }
      }

      Path liveRoot = PrefabStore.get().getServerPrefabsPath();
      Files.createDirectories(liveRoot);

      if (countOwnerPrefabs(liveRoot, owner) >= maxPerOwner) {
        throw new IOException(
            "upload quota reached: this player already owns " + maxPerOwner + " prefabs (max)");
      }

      Path dest = resolveUniqueDest(liveRoot, owner, base);
      Files.write(dest, data);

      LOG.at(Level.INFO).log(
          "[PrefabsUploader] AUDIT approve: admin=%s(%s) prefab=%s owner=%s dest=%s bytes=%d at=%d",
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
          "[PrefabsUploader] native validation unavailable — rejecting approval (fail-secure)");
      return "native validation unavailable";
    }
  }

  /**
   * Counts how many approved prefabs an owner already has in the live tree (files named {@code
   * <owner>_*<SUFFIX>}).
   */
  private static long countOwnerPrefabs(Path liveRoot, String owner) throws IOException {
    String prefix = owner + "_";
    try (Stream<Path> files = Files.list(liveRoot)) {
      return files
          .filter(Files::isRegularFile)
          .map(path -> path.getFileName().toString())
          .filter(name -> name.startsWith(prefix) && name.endsWith(SUFFIX))
          .count();
    }
  }

  /**
   * Resolves a destination that does not overwrite an existing prefab: {@code
   * <owner>_<base>.prefab.json}, or {@code <owner>_<base>_N.prefab.json} for the first free {@code
   * N} when the base name is taken. Re-validates path containment and refuses symlinks.
   *
   * @throws IOException if no free name is found, or a containment/symlink check fails
   */
  private static Path resolveUniqueDest(Path liveRoot, String owner, String base)
      throws IOException {
    Path liveRootReal = liveRoot.toRealPath();
    for (int n = 1; n <= MAX_COLLISION_SUFFIX; n++) {
      String name = (n == 1) ? owner + "_" + base : owner + "_" + base + "_" + n;
      Path dest = liveRoot.resolve(name + SUFFIX);
      String destName = dest.getFileName().toString();
      if (destName.contains("/") || destName.contains("\\") || destName.contains("..")) {
        throw new IOException("invalid destination name");
      }
      if (!dest.getParent().toRealPath().startsWith(liveRootReal)) {
        throw new IOException("invalid destination (outside the prefabs tree)");
      }
      if (Files.exists(dest)) {
        continue;
      }
      if (Files.isSymbolicLink(dest)) {
        throw new IOException("destination is a symlink — refused");
      }
      return dest;
    }
    throw new IOException("too many prefabs with a similar name; delete some first");
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

  /**
   * File base name: charset sanitization and anti path-traversal (collisions resolved at write).
   */
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
