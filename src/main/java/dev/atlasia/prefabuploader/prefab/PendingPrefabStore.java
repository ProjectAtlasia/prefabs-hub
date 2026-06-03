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
 * Escreve no storage VIVO de server prefabs (a APROVAÇÃO) sob o novo modelo PULL.
 *
 * <p>O pendente NÃO vive mais em disco do servidor: ele só é materializado em memória quando a
 * staff o seleciona (download do CDN do Discord). Esta classe só entra na APROVAÇÃO: recebe os
 * bytes (já validados em memória por {@link PrefabValidator}), roda o validador NATIVO do engine —
 * que exige um {@code Path}, então escrevemos os bytes num arquivo TEMP do OS, validamos e
 * descartamos — e só então grava os bytes ORIGINAIS no {@code getServerPrefabsPath()} vivo (sem
 * re-serializar; o round-trip deserialize→serialize NPE-ava por falta de contexto de store). Autor:
 * astahjmo (Astaroth).
 */
public final class PendingPrefabStore {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  private static final String SUFFIX = ".prefab.json";
  private static final int MAX_NAME_LEN = 48;
  private static final PendingPrefabStore INSTANCE = new PendingPrefabStore();

  // Validação nativa do engine aplicada antes de confirmar o save (tunável).
  private static final Set<ValidationOption> VALIDATION =
      EnumSet.of(
          ValidationOption.BLOCKS,
          ValidationOption.BLOCK_STATES,
          ValidationOption.ENTITIES,
          ValidationOption.BLOCK_FILLER);

  // [M5] Serializa gravações concorrentes no storage vivo (dois admins aprovando ao mesmo tempo).
  private final ReentrantLock lock = new ReentrantLock();

  public static PendingPrefabStore get() {
    return INSTANCE;
  }

  private PendingPrefabStore() {}

  /**
   * Aprova: valida os bytes pelo validador NATIVO do engine e grava no {@code Prefabs/} vivo. Roda
   * na thread do mundo (loadBuffer/validate tocam o engine). Lança {@link IOException} com mensagem
   * amigável em caso de rejeição/erro — o chamador mostra ao admin e NÃO grava nada.
   *
   * @param p metadados do pendente (id/nome/dono)
   * @param data bytes do {@code .prefab.json} baixados do CDN (já passaram por {@link
   *     PrefabValidator})
   * @param adminName usuário do admin que aprovou (auditoria [M7])
   * @param adminUuid uuid do admin que aprovou (auditoria [M7])
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
      // 1) Validação NATIVA do engine via arquivo TEMP do OS (loadBuffer exige Path). Descartado em
      // qualquer caminho de saída (finally). NÃO toca no storage vivo.
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

      // 2) Grava os bytes ORIGINAIS no storage VIVO (mesmo destino/forma do antigo promote:
      // getServerPrefabsPath()/<owner>_<base>.prefab.json, anti path-traversal/symlink).
      Path liveRoot = PrefabStore.get().getServerPrefabsPath();
      Path dest = liveRoot.resolve(owner + "_" + base + SUFFIX);
      Files.createDirectories(dest.getParent());

      // [H10] Anti path-traversal por symlink: normalize().startsWith() é só string e não resolve
      // symlink. Validamos o caminho REAL — o diretório PAI (que já existe após createDirectories)
      // precisa estar dentro do liveRoot real; o nome é validado à parte (toRealPath exige
      // existência e o dest ainda não existe).
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

      // [M7] Trilha de auditoria: quem aprovou, o quê, de quem, quando.
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
   * Roda o validador nativo do engine sobre o arquivo TEMP. Retorna a descrição dos problemas (ou
   * null/vazio se OK).
   *
   * <p>[H9] Fail-SECURE: se o próprio validador falhar (contexto de engine indisponível, exceção
   * inesperada), NÃO aceitamos o prefab — retornamos uma string de erro pra que {@code approve()} o
   * REJEITE. A causa completa vai pro log.
   */
  private static String nativeValidate(Path file) {
    try {
      // loadBuffer dá um PrefabBuffer; o IPrefabBuffer (leitura) vem de newAccess().
      IPrefabBuffer buffer = PrefabBufferUtil.loadBuffer(file).newAccess();
      return PrefabBufferValidator.validate(buffer, VALIDATION);
    } catch (Throwable t) {
      LOG.at(Level.WARNING).withCause(t).log(
          "[PrefabsUploader] validação nativa indisponível — rejeitando aprovação (fail-secure)");
      return "validação nativa indisponível";
    }
  }

  /** Subpasta do dono: prefere o UUID Hytale (thread vinculada); cai pro Discord id; senão anon. */
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

  /** Nome-base do arquivo: sanitização de charset, anti path-traversal e colisão. */
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
