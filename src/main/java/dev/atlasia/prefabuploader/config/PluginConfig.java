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
 * Persistent plugin configuration ({@code config.properties}): {@code server.id}, {@code
 * hub.address} (gRPC hub endpoint) and the {@code auth.token} issued during pairing.
 */
public final class PluginConfig {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
  public static final String PLUGIN_VERSION = "0.1.0";

  private static final String DEFAULT_HUB;
  private static final boolean DEFAULT_TLS;

  static {
    String hub = "localhost:50051";
    boolean tls = false;
    try (InputStream in =
        PluginConfig.class.getResourceAsStream("/prefabsuploader-build.properties")) {
      if (in != null) {
        Properties p = new Properties();
        p.load(in);
        hub = p.getProperty("hub.default", hub);
        tls = Boolean.parseBoolean(p.getProperty("hub.tls", String.valueOf(tls)));
      }
    } catch (IOException e) {
      LOG.at(Level.FINE).log("[PrefabsUploader] build defaults indisponíveis: %s", e.getMessage());
    }
    DEFAULT_HUB = hub;
    DEFAULT_TLS = tls;
  }

  private static final String DOC_MARKER = "# PrefabsUploader config";

  private final Path file;

  private String serverId;
  private String hubAddress;
  private boolean hubTls;
  private boolean hubInsecure;
  private String authToken;
  private boolean pairMessage;

  private PluginConfig(Path file) {
    this.file = file;
  }

  /** Loads (or creates/migrates) the config at {@code <dataDir>/config.properties}. */
  public static PluginConfig load(Path dataDir) {
    PluginConfig cfg = new PluginConfig(dataDir.resolve("config.properties"));
    cfg.read();
    return cfg;
  }

  private void read() {
    Path legacy = Paths.get("prefabsuploader", "config.properties");
    boolean newExists = Files.exists(file);
    boolean migrate = !newExists && Files.exists(legacy);
    Path source = newExists ? file : (migrate ? legacy : null);

    boolean documented = false;
    if (source != null) {
      Properties props = new Properties();
      try (InputStream in = Files.newInputStream(source)) {
        props.load(in);
      } catch (IOException e) {
        LOG.at(Level.WARNING).log("[PrefabsUploader] falha ao ler config: %s", e.getMessage());
      }
      serverId = props.getProperty("server.id", "");
      hubAddress = props.getProperty("hub.address", DEFAULT_HUB);
      hubTls = Boolean.parseBoolean(props.getProperty("hub.tls", String.valueOf(DEFAULT_TLS)));
      hubInsecure = Boolean.parseBoolean(props.getProperty("hub.insecure", "false"));
      authToken = props.getProperty("auth.token", "");
      pairMessage = Boolean.parseBoolean(props.getProperty("pair.message", "true"));
      documented = newExists && hasDocMarker();
    } else {
      serverId = "";
      hubAddress = DEFAULT_HUB;
      hubTls = DEFAULT_TLS;
      hubInsecure = false;
      authToken = "";
      pairMessage = true;
    }

    boolean fresh = serverId == null || serverId.isEmpty();
    if (fresh) {
      serverId = UUID.randomUUID().toString();
      LOG.at(Level.INFO).log("[PrefabsUploader] novo server.id gerado: %s", serverId);
    }
    if (fresh || migrate || !documented) {
      write();
    }
    if (migrate) {
      LOG.at(Level.INFO).log("[PrefabsUploader] config migrada de %s para %s", legacy, file);
      try {
        Files.deleteIfExists(legacy);
      } catch (IOException ignored) {
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

  /** Writes the commented config file from the current values (atomic write, 0600 permissions). */
  private synchronized void write() {
    String content = render();
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.write(tmp, content.getBytes(StandardCharsets.ISO_8859_1));
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      try {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
      } catch (IOException e) {
        LOG.at(Level.WARNING).log(
            "[PrefabsUploader] não foi possível restringir permissões da config: %s",
            e.getMessage());
      }
    } catch (IOException e) {
      LOG.at(Level.SEVERE).log("[PrefabsUploader] falha ao salvar config: %s", e.getMessage());
    }
  }

  /** Renders the documented {@code config.properties} content (ASCII comments and values). */
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
    b.append("# pair.message=false desliga o aviso automatico no chat pra parear o servidor.\n");
    b.append("# Os comandos continuam funcionando normalmente (so silencia o broadcast).\n");
    b.append("pair.message=").append(pairMessage).append('\n');
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

  public boolean pairMessage() {
    return pairMessage;
  }

  /** Enables/disables the automatic pairing broadcast and persists the change. */
  public synchronized void setPairMessage(boolean enabled) {
    if (this.pairMessage == enabled) {
      return;
    }
    this.pairMessage = enabled;
    write();
  }

  /** Persists a new auth token received during pairing, rewriting the commented file. */
  public synchronized void setAuthToken(String token) {
    if (token == null || token.equals(this.authToken)) {
      return;
    }
    this.authToken = token;
    write();
  }

  /** Rewrites the commented config with the current values. */
  public synchronized void save() {
    write();
  }
}
