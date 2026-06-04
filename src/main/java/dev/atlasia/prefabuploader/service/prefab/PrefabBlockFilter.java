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

import com.hypixel.hytale.server.core.prefab.config.SelectionPrefabSerializer;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.util.BsonUtil;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Rebuilds a {@link BlockSelection} keeping only the admin-approved block and fluid ids, then
 * serializes it to {@code .prefab.json} bytes. Disallowed cells are simply omitted, so they become
 * empty (air) when the prefab is pasted.
 *
 * <p>The serializer and block/fluid registries are touched, so this runs on the WORLD THREAD only.
 */
public final class PrefabBlockFilter {

  private PrefabBlockFilter() {}

  /**
   * Returns {@code .prefab.json} bytes containing only the allowed blocks and fluids from {@code
   * sel}; every block whose id is not in {@code allowedBlockIds} and every fluid whose id is not in
   * {@code allowedFluidIds} is dropped. World thread only.
   *
   * <p>The input selection is never mutated: a fresh selection is rebuilt from empty, its
   * properties and bounds copied over, and only the permitted cells re-added. Entities are
   * intentionally not copied — the plugin rejects entity-bearing prefabs.
   *
   * @param sel the source selection to filter
   * @param allowedBlockIds the set of block ids permitted to survive
   * @param allowedFluidIds the set of fluid ids permitted to survive
   * @return the filtered prefab encoded as strict-JSON UTF-8 bytes
   */
  public static byte[] filter(
      BlockSelection sel, Set<Integer> allowedBlockIds, Set<Integer> allowedFluidIds) {
    BlockSelection out = new BlockSelection(sel.getBlockCount(), 0);
    out.copyPropertiesFrom(sel);
    if (sel.hasSelectionBounds()) {
      out.setSelectionArea(sel.getSelectionMin(), sel.getSelectionMax());
    }

    sel.forEachBlock(
        (x, y, z, h) -> {
          if (allowedBlockIds.contains(h.blockId())) {
            out.addBlockAtWorldPos(
                x, y, z, h.blockId(), h.rotation(), h.filler(), h.supportValue());
          }
        });

    sel.forEachFluid(
        (x, y, z, fluidId, level) -> {
          if (allowedFluidIds.contains(fluidId)) {
            out.addFluidAtWorldPos(x, y, z, fluidId, level);
          }
        });

    return BsonUtil.toJson(SelectionPrefabSerializer.serialize(out))
        .getBytes(StandardCharsets.UTF_8);
  }
}
