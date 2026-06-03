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
import com.hypixel.hytale.server.core.prefab.config.SelectionPrefabSerializer;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import org.bson.BsonDocument;

/**
 * Sanitização/validação de um prefab recebido ANTES de persistir. Um prefab pode carregar
 * componentes de entidade ({@code PrefabCopyableComponent}), então a regra dura aqui é: <b>rejeitar
 * qualquer entidade</b> e impor limites de tamanho/contagem. Autor: astahjmo (Astaroth).
 */
public final class PrefabValidator {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();

  // Limites (alinhados ao DESIGN.md): ≤ 8 MiB, ≤ 64³ blocos.
  public static final int MAX_BYTES = 8 << 20;
  public static final int MAX_BLOCKS = 262_144; // 64*64*64
  public static final int MAX_VOLUME = 262_144;

  /** Resultado da validação: ou um BlockSelection válido, ou um motivo de rejeição. */
  public record Result(boolean ok, BlockSelection selection, String error) {
    static Result reject(String error) {
      return new Result(false, null, error);
    }

    static Result accept(BlockSelection sel) {
      return new Result(true, sel, null);
    }
  }

  private PrefabValidator() {}

  public static Result validate(byte[] data) {
    if (data == null || data.length == 0) {
      return Result.reject("arquivo vazio");
    }
    if (data.length > MAX_BYTES) {
      return Result.reject("excede o limite de tamanho (" + (MAX_BYTES >> 20) + " MiB)");
    }

    // O .prefab.json é BSON serializado → JSON (texto). Parse defensivo.
    BsonDocument doc;
    try {
      doc = BsonDocument.parse(new String(data, StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      return Result.reject("JSON/BSON malformado: " + e.getMessage());
    }

    BlockSelection sel;
    try {
      sel = SelectionPrefabSerializer.deserialize(doc);
    } catch (Throwable t) {
      LOG.at(Level.WARNING).log("[PrefabsUploader] deserialize falhou: %s", t.getMessage());
      return Result.reject("prefab inválido (deserialize falhou)");
    }
    if (sel == null) {
      return Result.reject("prefab vazio após deserialize");
    }

    if (sel.getBlockCount() > MAX_BLOCKS) {
      return Result.reject("blocos demais: " + sel.getBlockCount() + " > " + MAX_BLOCKS);
    }
    if (sel.getSelectionVolume() > MAX_VOLUME) {
      return Result.reject(
          "volume grande demais: " + sel.getSelectionVolume() + " > " + MAX_VOLUME);
    }
    // Regra dura de segurança: nenhum prefab de jogador pode trazer entidades.
    if (sel.getEntityCount() > 0) {
      return Result.reject(
          "prefab contém entidades (" + sel.getEntityCount() + ") — não permitido");
    }

    // TODO(segurança [M1]): whitelist explícita de block ids.
    // BLOQUEADO: ainda não temos o mapeamento de ids → nomes de blocos permitidos do engine
    // (precisa do registro de blocos / asset catalog do Hytale). Enquanto isso, a defesa em
    // profundidade é: (a) validador nativo do engine (ValidationOption.BLOCKS em
    // PendingPrefabStore),
    // (b) rejeição de entidades acima, e (c) aprovação manual da staff antes de promover.
    // Quando o mapeamento existir: iterar os blocos do BlockSelection e rejeitar ids fora da
    // whitelist (blocos de comando, technical/internal, etc.).
    return Result.accept(sel);
  }
}
