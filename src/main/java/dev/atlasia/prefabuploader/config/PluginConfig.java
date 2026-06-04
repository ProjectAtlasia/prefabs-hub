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
  public static final String PLUGIN_VERSION = "0.2.0"; // x-release-please-version

  private static final String DEFAULT_HUB;
  private static final boolean DEFAULT_TLS;
  private static final boolean DEFAULT_ALLOW_INSECURE;

  static {
    String hub = "localhost:50051";
    boolean tls = false;
    boolean allowInsecure = false;
    try (InputStream in =
        PluginConfig.class.getResourceAsStream("/prefabsuploader-build.properties")) {
      if (in != null) {
        Properties p = new Properties();
        p.load(in);
        hub = p.getProperty("hub.default", hub);
        tls = Boolean.parseBoolean(p.getProperty("hub.tls", String.valueOf(tls)));
        allowInsecure =
            Boolean.parseBoolean(
                p.getProperty("hub.insecure.allowed", String.valueOf(allowInsecure)));
      }
    } catch (IOException e) {
      LOG.at(Level.FINE).log("[PrefabsUploader] build defaults unavailable: %s", e.getMessage());
    }
    DEFAULT_HUB = hub;
    DEFAULT_TLS = tls;
    DEFAULT_ALLOW_INSECURE = allowInsecure;
  }

  private static final int DEFAULT_MAX_PREFAB_MB = 8;
  private static final int MIN_MAX_PREFAB_MB = 1;
  private static final int MAX_MAX_PREFAB_MB = 64;

  private static final String DOC_MARKER = "# PrefabsUploader config";

  private final Path file;

  private String serverId;
  private String hubAddress;
  private boolean hubTls;
  private boolean hubInsecure;
  private int maxPrefabBytes;
  private String authToken;
  private boolean pairMessage;
  private String inviteUrl;

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
        LOG.at(Level.WARNING).log("[PrefabsUploader] failed to read config: %s", e.getMessage());
      }
      serverId = props.getProperty("server.id", "");
      hubAddress = props.getProperty("hub.address", DEFAULT_HUB);
      hubTls = Boolean.parseBoolean(props.getProperty("hub.tls", String.valueOf(DEFAULT_TLS)));
      hubInsecure = Boolean.parseBoolean(props.getProperty("hub.insecure", "false"));
      maxPrefabBytes =
          parseMaxPrefabBytes(
              props.getProperty("prefab.max.size.mb", String.valueOf(DEFAULT_MAX_PREFAB_MB)));
      authToken = props.getProperty("auth.token", "");
      pairMessage = Boolean.parseBoolean(props.getProperty("pair.message", "true"));
      inviteUrl = props.getProperty("discord.invite.url", "");
      documented = newExists && hasDocMarker();
    } else {
      serverId = "";
      hubAddress = DEFAULT_HUB;
      hubTls = DEFAULT_TLS;
      hubInsecure = false;
      maxPrefabBytes = DEFAULT_MAX_PREFAB_MB << 20;
      authToken = "";
      pairMessage = true;
      inviteUrl = "";
    }

    boolean fresh = serverId == null || serverId.isEmpty();
    if (fresh) {
      serverId = UUID.randomUUID().toString();
      LOG.at(Level.INFO).log("[PrefabsUploader] new server.id generated: %s", serverId);
    }
    if (fresh || migrate || !documented) {
      write();
    }
    if (migrate) {
      LOG.at(Level.INFO).log("[PrefabsUploader] config migrated from %s to %s", legacy, file);
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

  /**
   * Parses the configured max prefab size (in MB), clamped to {@code [MIN..MAX]} and converted to
   * bytes. Falls back to the default on a malformed value.
   */
  private static int parseMaxPrefabBytes(String rawMb) {
    int mb;
    try {
      mb = Integer.parseInt(rawMb.trim());
    } catch (NumberFormatException e) {
      LOG.at(Level.WARNING).log(
          "[PrefabsUploader] invalid prefab.max.size.mb='%s'; using default", rawMb);
      mb = DEFAULT_MAX_PREFAB_MB;
    }
    mb = Math.max(MIN_MAX_PREFAB_MB, Math.min(MAX_MAX_PREFAB_MB, mb));
    return mb << 20;
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
            "[PrefabsUploader] could not restrict config permissions: %s", e.getMessage());
      }
    } catch (IOException e) {
      LOG.at(Level.SEVERE).log("[PrefabsUploader] failed to save config: %s", e.getMessage());
    }
  }

  /** Renders the documented {@code config.properties} content (ASCII comments and values). */
  private String render() {
    StringBuilder b = new StringBuilder();
    b.append(DOC_MARKER)
        .append(" -- do NOT share (auth.token authenticates your server with the hub).\n");
    b.append("# Changed a value? Restart the server (Hytale has no hot-reload).\n");
    b.append("#\n");
    b.append("# ===========================================================================\n");
    b.append("#  gRPC HUB -- where the plugin connects. SET THE URL HERE.\n");
    b.append("# ===========================================================================\n");
    b.append("#  Production:  your-hub.example.com:443   (hub.tls=true,  hub.insecure=false)\n");
    b.append("#  Local test:  localhost:50051           (hub.tls=false, hub.insecure=false)\n");
    b.append("hub.address=").append(hubAddress).append('\n');
    b.append("\n");
    b.append("# TLS on the hub connection: true in production (port 443, behind the proxy);\n");
    b.append("# false for a LOCAL bot in plaintext (e.g. localhost:50051).\n");
    b.append("hub.tls=").append(hubTls).append('\n');
    b.append("\n");
    b.append(
        "# hub.insecure=true: connects via TLS but does NOT validate the certificate (DEV builds\n");
    b.append(
        "# only -- official/release builds ignore this and always validate). Needs hub.tls=true.\n");
    b.append("hub.insecure=").append(hubInsecure).append('\n');
    b.append("\n");
    b.append("# Max size (in MB) of a prefab a player may upload; anything larger is rejected.\n");
    b.append("# Clamped to [1..64]. Default 8.\n");
    b.append("prefab.max.size.mb=").append(maxPrefabBytes >> 20).append('\n');
    b.append("\n");
    b.append("# pair.message=false turns off the automatic chat prompt to pair the server.\n");
    b.append("# The commands keep working normally (it only silences the broadcast).\n");
    b.append("pair.message=").append(pairMessage).append('\n');
    b.append("\n");
    b.append("# Your Discord server invite, shown in-game so players can join (needed to link).\n");
    b.append(
        "# Optional: if you set it on Discord with /setup invite, that one wins; this is the\n");
    b.append(
        "# fallback used when no invite was configured on Discord. Example: https://discord.gg/abcd\n");
    b.append("discord.invite.url=").append(inviteUrl == null ? "" : inviteUrl).append('\n');
    b.append("\n");
    b.append("# Stable identity of this server (generated once -- do not change).\n");
    b.append("server.id=").append(serverId).append('\n');
    b.append("\n");
    b.append("# Token issued during pairing (/setup). Filled in automatically. Do NOT share.\n");
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

  /**
   * Whether this build permits disabling TLS certificate validation ({@code hub.insecure=true}).
   * Baked at build time: dev builds allow it for local testing against a self-signed hub, while
   * official/release builds set it to {@code false} so the shipped jar always validates the hub
   * certificate, regardless of {@code config.properties}.
   */
  public boolean insecureTlsAllowed() {
    return DEFAULT_ALLOW_INSECURE;
  }

  /** Maximum prefab upload size in bytes, configured by the server owner. */
  public int maxPrefabBytes() {
    return maxPrefabBytes;
  }

  public String authToken() {
    return authToken;
  }

  public boolean pairMessage() {
    return pairMessage;
  }

  /**
   * Owner-configured Discord guild invite shown in-game. Used as a fallback when the hub did not
   * provide one (i.e. {@code /setup invite} was not run); may be empty.
   */
  public String inviteUrl() {
    return inviteUrl == null ? "" : inviteUrl;
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
