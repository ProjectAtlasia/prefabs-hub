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
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Enumerates the distinct block and fluid types contained in a {@link BlockSelection} together with
 * their occurrence counts, for the admin "review the blocks in a prefab" UI.
 *
 * <p>All methods touch the engine block/fluid asset registries and therefore run on the WORLD
 * THREAD only.
 */
public final class PrefabBlockManifest {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();

  private static final List<String> SUSPICIOUS_TOKENS =
      List.of("spawner", "command", "trigger", "mob", "entity", "tnt", "explos");

  private PrefabBlockManifest() {}

  /** One distinct block or fluid type in a prefab. */
  public record Entry(String name, int id, boolean fluid, int count, boolean suspicious) {}

  /**
   * Enumerates the distinct block and fluid types in {@code sel} with their counts. World thread
   * only (reads the engine block/fluid registries).
   *
   * <p>Air blocks ({@link BlockType#EMPTY_ID}) and empty fluids ({@link Fluid#EMPTY_ID}) are
   * skipped. The result is sorted with suspicious entries first, then by descending count, then by
   * ascending name.
   *
   * @param sel the in-memory selection to inspect
   * @return the distinct block/fluid entries, sorted for review
   */
  public static List<Entry> of(BlockSelection sel) {
    Map<Integer, Integer> blockCounts = new HashMap<>();
    sel.forEachBlock(
        (x, y, z, h) -> {
          if (h.blockId() != BlockType.EMPTY_ID) {
            blockCounts.merge(h.blockId(), 1, Integer::sum);
          }
        });

    Map<Integer, Integer> fluidCounts = new HashMap<>();
    sel.forEachFluid(
        (x, y, z, fluidId, level) -> {
          if (fluidId != Fluid.EMPTY_ID) {
            fluidCounts.merge(fluidId, 1, Integer::sum);
          }
        });

    List<Entry> entries = new ArrayList<>(blockCounts.size() + fluidCounts.size());
    blockCounts.forEach((id, count) -> entries.add(blockEntry(id, count)));
    fluidCounts.forEach((id, count) -> entries.add(fluidEntry(id, count)));

    entries.sort(
        Comparator.comparing(Entry::suspicious)
            .reversed()
            .thenComparing(Comparator.comparingInt(Entry::count).reversed())
            .thenComparing(Entry::name));
    return entries;
  }

  /**
   * Builds the manifest entry for a block id, resolving its registry name.
   *
   * @param id the engine block id
   * @param count how many times the block occurs in the selection
   * @return the resolved entry, marked suspicious when unknown or matching a flagged token
   */
  private static Entry blockEntry(int id, int count) {
    boolean unknown = id == BlockType.UNKNOWN_ID;
    String name = null;
    if (!unknown) {
      try {
        BlockType type = BlockType.getAssetMap().getAsset(id);
        if (type != null) {
          name = type.getId();
        }
      } catch (RuntimeException e) {
        LOG.at(Level.WARNING).log(
            "[PrefabsUploader] failed to resolve block id %d: %s", id, e.getMessage());
      }
    }
    if (name == null) {
      unknown = true;
      name = "unknown:" + id;
    }
    return new Entry(name, id, false, count, isSuspicious(name, false, unknown));
  }

  /**
   * Builds the manifest entry for a fluid id, resolving its registry name.
   *
   * @param id the engine fluid id
   * @param count how many times the fluid occurs in the selection
   * @return the resolved entry; fluids are always marked suspicious (grief/lag vector)
   */
  private static Entry fluidEntry(int id, int count) {
    boolean unknown = false;
    String name = null;
    try {
      Fluid fluid = Fluid.getAssetMap().getAsset(id);
      if (fluid != null) {
        name = fluid.getId();
      }
    } catch (RuntimeException e) {
      LOG.at(Level.WARNING).log(
          "[PrefabsUploader] failed to resolve fluid id %d: %s", id, e.getMessage());
    }
    if (name == null) {
      unknown = true;
      name = "unknown:" + id;
    }
    return new Entry(name, id, true, count, isSuspicious(name, true, unknown));
  }

  /**
   * Decides whether a block/fluid type warrants admin attention.
   *
   * @param name the resolved registry name (lowercased for token matching)
   * @param fluid whether the type is a fluid (always suspicious — grief/lag vector)
   * @param unknown whether the id could not be resolved against the registry
   * @return {@code true} when unknown, when a fluid, or when the name contains a flagged token
   */
  private static boolean isSuspicious(String name, boolean fluid, boolean unknown) {
    if (unknown || fluid) {
      return true;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    return SUSPICIOUS_TOKENS.stream().anyMatch(lower::contains);
  }
}
