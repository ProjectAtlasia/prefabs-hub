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
package dev.atlasia.prefabuploader.ui;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Shared base for the plugin's admin CustomUI pages. Owns world-thread marshalling, the chat brand
 * tag, and text/utility helpers so the concrete pages contain only their own UI logic.
 *
 * @param <D> the page's data-event payload type
 */
public abstract class AbstractPrefabPage<D> extends InteractiveCustomUIPage<D> {

  private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();

  /** Number of list rows the paginated {@code .ui} pages render per page. */
  protected static final int ROWS = 20;

  protected AbstractPrefabPage(
      PlayerRef playerRef, CustomPageLifetime lifetime, BuilderCodec<D> codec) {
    super(playerRef, lifetime, codec);
  }

  /** Prefixes a message with the plugin's (uncolored) chat tag. */
  protected static Message tagged(Message msg) {
    return Message.join(Message.raw("[PrefabsUploader] "), msg);
  }

  /**
   * Returns the player's world.
   *
   * @return the world, or {@code null} if the player disconnected
   */
  protected World world() {
    try {
      return Universe.get().getWorld(playerRef.getWorldUuid());
    } catch (Throwable t) {
      LOG.at(Level.FINE).log("[PrefabsUploader] world unavailable: %s", t.getMessage());
      return null;
    }
  }

  /**
   * Runs {@code r} on the player's world thread, as required for packet/UI operations.
   *
   * @param r the task to run
   */
  protected void runOnWorld(Runnable r) {
    World w = world();
    if (w == null) {
      return;
    }
    w.execute(
        () -> {
          try {
            r.run();
          } catch (Throwable t) {
            LOG.at(Level.WARNING).withCause(t).log(
                "[PrefabsUploader] UI task on the world thread failed");
          }
        });
  }

  /**
   * Runs {@code r} on the world thread and blocks until it completes, propagating any exception.
   * Must be called only from the I/O executor, never from the world thread, to avoid deadlock.
   *
   * @param r the task to run
   * @throws Exception if the task fails or the world is unavailable
   */
  protected void runOnWorldAwait(Runnable r) throws Exception {
    World w = world();
    if (w == null) {
      throw new IOException("world unavailable");
    }
    CompletableFuture<Void> done = new CompletableFuture<>();
    w.execute(
        () -> {
          try {
            r.run();
            done.complete(null);
          } catch (Throwable t) {
            done.completeExceptionally(t);
          }
        });
    try {
      done.get(20, TimeUnit.SECONDS);
    } catch (ExecutionException ee) {
      Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
      if (cause instanceof Exception ex) {
        throw ex;
      }
      throw new IOException(cause.getMessage(), cause);
    }
  }

  /**
   * Parses the trailing index of an action string such as {@code "Select3"}.
   *
   * @return the parsed index, or {@code -1} if malformed
   */
  protected static int parseIdx(String action, String prefix) {
    try {
      return Integer.parseInt(action.substring(prefix.length()));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** Extracts a concise message from a throwable, unwrapping one RuntimeException layer. */
  protected static String causeMessage(Throwable t) {
    Throwable c = t;
    if (c instanceof RuntimeException && c.getCause() != null) {
      c = c.getCause();
    }
    String msg = c.getMessage();
    return (msg == null || msg.isBlank()) ? c.getClass().getSimpleName() : msg;
  }

  /**
   * Strips Unicode control and formatting characters (C0 controls, DEL, bidi overrides, zero-width)
   * from player-supplied strings before display, preventing layout/RTL spoofing in the UI.
   *
   * @param s the input string
   * @return the sanitized string, or an empty string if {@code s} is null or empty
   */
  protected static String sanitize(String s) {
    if (s == null || s.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean drop =
          c < 0x20
              || c == 0x7F
              || (c >= 0x202A && c <= 0x202E)
              || (c >= 0x2066 && c <= 0x2069)
              || c == 0x200B
              || c == 0x200C
              || c == 0x200D
              || c == 0x2060
              || c == 0xFEFF;
      if (!drop) {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
