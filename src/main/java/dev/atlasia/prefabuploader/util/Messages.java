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
package dev.atlasia.prefabuploader.util;

import com.hypixel.hytale.server.core.Message;
import java.awt.Color;

/**
 * Shared chat-message helpers and brand colors for the plugin's in-game output. Centralizes the
 * {@code [PrefabsUploader]} brand tag and the palette so every command and service renders
 * identical text and colors.
 */
public final class Messages {

  /** Brand-tag color (orange) for the {@code [PrefabsUploader]} chat prefix. */
  public static final Color TAG = new Color(0xFF, 0xAA, 0x00);

  /** Discord blurple, used for invite/install buttons and Discord-related accents. */
  public static final Color DISCORD = new Color(0x72, 0x89, 0xDA);

  /** Green highlight for codes and inline command snippets shown to the player. */
  public static final Color CODE = new Color(0x66, 0xDD, 0x77);

  private Messages() {}

  /**
   * Prefixes a message with the colored {@code [PrefabsUploader]} chat tag.
   *
   * @param msg the message to prefix
   * @return the tag joined with {@code msg}
   */
  public static Message tagged(Message msg) {
    return Message.join(Message.raw("[PrefabsUploader] ").color(TAG), msg);
  }
}
