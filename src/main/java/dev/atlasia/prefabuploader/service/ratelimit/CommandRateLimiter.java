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
package dev.atlasia.prefabuploader.service.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key cooldown rate limiter (one shared instance). Throttles player-facing commands so a single
 * player cannot spam hub RPCs / Discord contacts even though each individual call is otherwise
 * valid. Independent of any IP-based limit on the hub side.
 */
public final class CommandRateLimiter {

  private static final CommandRateLimiter INSTANCE = new CommandRateLimiter();
  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  /** Fixed cooldown between command uses; set by the plugin, not server-configurable. */
  private static final long COOLDOWN_SECONDS = 15;

  private final Map<String, Long> lastUseNanos = new ConcurrentHashMap<>();

  public static CommandRateLimiter get() {
    return INSTANCE;
  }

  private CommandRateLimiter() {}

  /**
   * Checks the cooldown for {@code key} and, when allowed, records this use atomically.
   *
   * @param key the rate-limit subject (e.g. a player UUID)
   * @return the remaining cooldown in seconds (rounded up, min 1) when blocked, or {@code 0} when
   *     the action is allowed and has just been recorded
   */
  public long remainingCooldownSeconds(String key) {
    long cooldownNanos = COOLDOWN_SECONDS * NANOS_PER_SECOND;
    long now = System.nanoTime();
    long[] remaining = {0L};
    lastUseNanos.compute(
        key,
        (k, prev) -> {
          if (prev != null && now - prev < cooldownNanos) {
            remaining[0] = Math.max(1L, (cooldownNanos - (now - prev)) / NANOS_PER_SECOND);
            return prev;
          }
          return now;
        });
    return remaining[0];
  }
}
