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
 * Um prefab pendente de análise da staff. Sob o novo modelo PULL, o pendente NÃO vive mais em disco
 * do servidor: isto é só o METADADO (ponteiro) que o hub devolve via {@code ListPending}. O arquivo
 * real fica no CDN do Discord e só é baixado (em memória) quando a staff seleciona o card. Autor:
 * astahjmo (Astaroth).
 *
 * @param id identificador opaco do pendente no hub (usado em {@code GetPending}/{@code
 *     ResolvePending})
 * @param prefabName nome do prefab pra exibir
 * @param uploaderName nome do uploader no Discord
 * @param uploaderDiscordId id do uploader no Discord
 * @param playerUuid uuid Hytale do jogador vinculado (pode ser vazio)
 * @param playerName nome in-game do jogador vinculado (pode ser vazio)
 * @param sizeBytes tamanho declarado do prefab em bytes
 * @param uploadedAt epoch-millis do upload
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

  /** Converte um {@link PendingItem} (proto do hub) no record de metadados da UI. */
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
