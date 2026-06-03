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
package dev.atlasia.prefabuploader.config;

import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Configuração persistente do plugin (em {@code prefabsuploader/config.properties}).
 *
 * <p>Guarda a identidade estável do servidor ({@code server.id}), o <b>endereço do hub gRPC</b>
 * ({@code hub.address} — é aqui que você aponta pra produção ou pra um bot local de teste) e o
 * {@code auth.token} emitido no pareamento.
 *
 * <p>O arquivo é gerado <b>comentado</b>; os comentários são preservados a cada gravação (escrita
 * própria, não {@code Properties.store}). Editou? Reinicie o servidor (Hytale não tem hot-reload).
 * Autor: astahjmo (Astaroth).
 */
public final class PluginConfig {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  public static final String PLUGIN_VERSION = "0.1.0";

  // Default = bot LOCAL de teste (plaintext). O endereço do hub de PRODUÇÃO NÃO vai hardcoded no
  // código aberto — o operador aponta hub.address no config.properties (ver render()).
  private static final String DEFAULT_HUB = "localhost:50051";
  // Marcador de formato documentado: se o arquivo existente não tiver, fazemos upgrade uma vez.
  private static final String DOC_MARKER = "# PrefabsUploader config";

  private final Path file;

  private String serverId;
  private String hubAddress;
  private boolean hubTls;
  private boolean hubInsecure;
  private String authToken;

  private PluginConfig(Path file) {
    this.file = file;
  }

  /**
   * Carrega (ou cria/migra) a config em {@code <dataDir>/config.properties}. Normalmente {@code
   * dataDir} é {@code mods/ProjectAtlasia_PrefabsUploader/} (resolvido pelo plugin via getFile()).
   */
  public static PluginConfig load(Path dataDir) {
    PluginConfig cfg = new PluginConfig(dataDir.resolve("config.properties"));
    cfg.read();
    return cfg;
  }

  private void read() {
    // Migração do local antigo (CWD/prefabsuploader/config.properties) pro novo (dentro de mods/).
    Path legacy = Paths.get("prefabsuploader", "config.properties");
    boolean newExists = Files.exists(file);
    boolean migrate = !newExists && Files.exists(legacy);
    Path source = newExists ? file : (migrate ? legacy : null);

    boolean documented = false;
    if (source != null) {
      Properties props = new Properties();
      try (InputStream in = Files.newInputStream(source)) {
        props.load(in); // Properties.load lê em ISO-8859-1 (latin-1).
      } catch (IOException e) {
        LOG.at(Level.WARNING).log("[PrefabsUploader] falha ao ler config: %s", e.getMessage());
      }
      serverId = props.getProperty("server.id", "");
      hubAddress = props.getProperty("hub.address", DEFAULT_HUB);
      hubTls = Boolean.parseBoolean(props.getProperty("hub.tls", "false"));
      hubInsecure = Boolean.parseBoolean(props.getProperty("hub.insecure", "false"));
      authToken = props.getProperty("auth.token", "");
      documented = newExists && hasDocMarker();
    } else {
      serverId = "";
      hubAddress = DEFAULT_HUB;
      hubTls = false;
      hubInsecure = false;
      authToken = "";
    }

    boolean fresh = serverId == null || serverId.isEmpty();
    if (fresh) {
      serverId = UUID.randomUUID().toString();
      LOG.at(Level.INFO).log("[PrefabsUploader] novo server.id gerado: %s", serverId);
    }
    // Grava no arquivo NOVO: na criação, na migração, ou pra dar upgrade do formato antigo
    // (sem comentários) uma vez. Se já está documentado, não reescreve no boot.
    if (fresh || migrate || !documented) {
      write();
    }
    if (migrate) {
      LOG.at(Level.INFO).log("[PrefabsUploader] config migrada de %s para %s", legacy, file);
      try {
        Files.deleteIfExists(legacy);
      } catch (IOException ignored) {
        // não-fatal: deixa o arquivo antigo; o novo já é a fonte de verdade
      }
    }
  }

  private boolean hasDocMarker() {
    try {
      return Files.readString(file, StandardCharsets.ISO_8859_1).contains(DOC_MARKER);
    } catch (IOException e) {
      return false;
    }
  }

  /** Gera o arquivo COMENTADO a partir dos valores atuais (atômico + permissão 0600). */
  private synchronized void write() {
    String content = render();
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.write(tmp, content.getBytes(StandardCharsets.ISO_8859_1));
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      // [H3] auth.token em repouso → restringe a "dono apenas" (0600). Best-effort em FS não-POSIX.
      try {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
        // FS não-POSIX (ex.: Windows): segue sem 0600.
      } catch (IOException e) {
        LOG.at(Level.WARNING).log(
            "[PrefabsUploader] não foi possível restringir permissões da config: %s",
            e.getMessage());
      }
    } catch (IOException e) {
      LOG.at(Level.SEVERE).log("[PrefabsUploader] falha ao salvar config: %s", e.getMessage());
    }
  }

  /** Conteúdo documentado do config.properties (comentários ASCII, valores ASCII). */
  private String render() {
    StringBuilder b = new StringBuilder();
    b.append(DOC_MARKER)
        .append(" -- NAO compartilhe (o auth.token autentica seu servidor no hub).\n");
    b.append("# Editou algum valor? Reinicie o servidor (Hytale nao tem hot-reload).\n");
    b.append("#\n");
    b.append("# ===========================================================================\n");
    b.append("#  HUB gRPC -- para onde o plugin conecta. APONTE A URL AQUI.\n");
    b.append("# ===========================================================================\n");
    b.append("#  Producao:    seu-hub.exemplo.com:443   (hub.tls=true,  hub.insecure=false)\n");
    b.append("#  Teste local: localhost:50051           (hub.tls=false, hub.insecure=false)\n");
    b.append("hub.address=").append(hubAddress).append('\n');
    b.append("\n");
    b.append("# TLS na conexao com o hub: true em producao (porta 443, atras do proxy);\n");
    b.append("# false pra um bot LOCAL em plaintext (ex.: localhost:50051).\n");
    b.append("hub.tls=").append(hubTls).append('\n');
    b.append("\n");
    b.append(
        "# hub.insecure=true: conecta via TLS mas NAO valida o certificado (so DEV, com cert\n");
    b.append(
        "# self-signed/quebrado). Mantenha false em producao. So tem efeito se hub.tls=true.\n");
    b.append("hub.insecure=").append(hubInsecure).append('\n');
    b.append("\n");
    b.append("# Identidade estavel deste servidor (gerada uma vez -- nao alterar).\n");
    b.append("server.id=").append(serverId).append('\n');
    b.append("\n");
    b.append(
        "# Token emitido no pareamento (/setup). Preenchido automaticamente. NAO compartilhe.\n");
    b.append("auth.token=").append(authToken == null ? "" : authToken).append('\n');
    return b.toString();
  }

  public String serverId() {
    return serverId;
  }

  public String hubAddress() {
    return hubAddress;
  }

  public boolean hubTls() {
    return hubTls;
  }

  public boolean hubInsecure() {
    return hubInsecure;
  }

  public String authToken() {
    return authToken;
  }

  /** Persiste um novo auth token recebido no pareamento (regrava o arquivo comentado). */
  public synchronized void setAuthToken(String token) {
    if (token == null || token.equals(this.authToken)) {
      return;
    }
    this.authToken = token;
    write();
  }

  /** Regrava a config (comentada) com os valores atuais. */
  public synchronized void save() {
    write();
  }
}
