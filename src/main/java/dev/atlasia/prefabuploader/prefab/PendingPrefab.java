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

import dev.atlasia.prefabuploader.grpc.PendingItem;

/**
 * Metadata for a prefab pending staff review. Only a pointer returned by the hub via {@code
 * ListPending}; the actual file lives on the Discord CDN and is only downloaded into memory when a
 * card is selected.
 *
 * @param id opaque identifier of the pending entry in the hub (used in {@code GetPending}/{@code
 *     ResolvePending})
 * @param prefabName prefab name to display
 * @param uploaderName uploader name on Discord
 * @param uploaderDiscordId uploader id on Discord
 * @param playerUuid linked Hytale player uuid (may be empty)
 * @param playerName linked in-game player name (may be empty)
 * @param sizeBytes declared prefab size in bytes
 * @param uploadedAt upload epoch-millis
 */
public record PendingPrefab(
    String id,
    String prefabName,
    String uploaderName,
    String uploaderDiscordId,
    String playerUuid,
    String playerName,
    long sizeBytes,
    long uploadedAt) {

  /** Converts a {@link PendingItem} (hub proto) into the UI metadata record. */
  public static PendingPrefab fromProto(PendingItem item) {
    return new PendingPrefab(
        item.getId(),
        item.getPrefabName(),
        item.getUploaderName(),
        item.getUploaderDiscordId(),
        item.getPlayerUuid(),
        item.getPlayerName(),
        item.getSizeBytes(),
        item.getUploadedAtMs());
  }
}
