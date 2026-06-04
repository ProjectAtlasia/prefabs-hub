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
package dev.atlasia.prefabuploader.service.messaging;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import dev.atlasia.prefabuploader.grpc.PlayerImportResponse;
import dev.atlasia.prefabuploader.util.Messages;

/**
 * Renders the in-game message for the {@link PlayerImportResponse} statuses that are not handled by
 * the link window flow ({@code NEEDS_LINK}) or the per-command thread/DM cases. Each status maps to
 * a localized key so the in-game text follows the Hytale server language.
 */
public final class StatusReply {

  private StatusReply() {}

  /**
   * Sends the message for a configuration/contact status; falls back to the hub's raw text. The
   * {@code inviteFallback} (plugin config invite) is used for {@code NOT_IN_GUILD} when the hub did
   * not provide a guild invite.
   */
  public static void send(CommandContext context, PlayerImportResponse res, String inviteFallback) {
    switch (res.getStatus()) {
      case NOT_CONFIGURED ->
          context.sendMessage(
              Messages.tagged(Message.translation("server.prefabsuploader.status.notConfigured")));
      case NO_UPLOADS_CHANNEL ->
          context.sendMessage(
              Messages.tagged(
                  Message.translation("server.prefabsuploader.status.noUploadsChannel")));
      case NOT_IN_GUILD -> sendNotInGuild(context, res, inviteFallback);
      case CONTACT_FAILED ->
          context.sendMessage(
              Messages.tagged(Message.translation("server.prefabsuploader.status.contactFailed")));
      default -> context.sendMessage(Messages.tagged(Message.raw(res.getMessage())));
    }
  }

  private static void sendNotInGuild(
      CommandContext context, PlayerImportResponse res, String inviteFallback) {
    String hub = res.getGuildInviteUrl();
    String invite = (hub == null || hub.isEmpty()) ? inviteFallback : hub;
    if (invite == null || invite.isEmpty()) {
      context.sendMessage(
          Messages.tagged(
              Message.translation("server.prefabsuploader.status.inviteNotConfigured")));
      return;
    }
    context.sendMessage(
        Messages.tagged(Message.translation("server.prefabsuploader.status.notInGuild")));
    context.sendMessage(
        Message.join(
            Message.translation("server.prefabsuploader.status.joinPrompt"),
            Message.raw(" "),
            Message.translation("server.prefabsuploader.link.inviteButton")
                .color(Messages.DISCORD)
                .link(invite)));
  }
}
