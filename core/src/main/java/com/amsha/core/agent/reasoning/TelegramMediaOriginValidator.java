package com.amsha.core.agent.reasoning;

import java.net.URI;
import java.util.List;

import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.core.agent.loop.AgentExecutionContext;

/**
 * Validates whether inbound media attachments qualify for Telegram channel-origin authorization.
 */
final class TelegramMediaOriginValidator {

    private TelegramMediaOriginValidator() {}

    /**
     * Returns the narrow policy authorization granted to media explicitly received by Telegram.
     *
     * <p>This is not an autonomy or global HITL bypass. The context must carry channel identity
     * and a user, the channel must be Telegram, and every remote attachment in the batch must use
     * the canonical opaque {@code telegram://file/<file-id>} reference. Inline bytes remain
     * governed by the media port. A mixed batch fails closed so a generic URL cannot inherit
     * Telegram's channel-origin authorization.
     */
    static boolean hasTrustedTelegramMediaOrigin(AgentExecutionContext context) {
        if (context == null
                || !context.hasChannelInfo()
                || context.userId() == null
                || context.userId().isBlank()
                || !ChannelType.TELEGRAM.id().equalsIgnoreCase(context.channelType())) {
            return false;
        }
        List<MediaAttachment> attachments = context.mediaAttachments();
        if (attachments == null || attachments.isEmpty()) {
            return false;
        }
        boolean hasTelegramReference = false;
        for (MediaAttachment attachment : attachments) {
            if (!isTelegramOrInlineAttachment(attachment)) {
                return false;
            }
            if (attachment.sourceUri() != null) {
                hasTelegramReference = true;
            }
        }
        return hasTelegramReference;
    }

    private static boolean isTelegramOrInlineAttachment(MediaAttachment attachment) {
        if (attachment == null) {
            return false;
        }
        URI sourceUri = attachment.sourceUri();
        return sourceUri == null || isCanonicalTelegramReference(sourceUri);
    }

    /**
     * Performs the authorization-side shape check; the governed resolver remains authoritative
     * for the full URI and file-token validation before any network request.
     */
    private static boolean isCanonicalTelegramReference(URI sourceUri) {
        if (!"telegram".equalsIgnoreCase(sourceUri.getScheme())
                || sourceUri.isOpaque()
                || !"file".equalsIgnoreCase(sourceUri.getHost())
                || sourceUri.getUserInfo() != null
                || sourceUri.getPort() != -1
                || sourceUri.getQuery() != null
                || sourceUri.getFragment() != null) {
            return false;
        }
        String path = sourceUri.getPath();
        if (path == null || path.length() < 2 || path.charAt(0) != '/' || path.indexOf('/', 1) >= 0) {
            return false;
        }
        String fileId = path.substring(1);
        return fileId.length() <= 512 && fileId.chars().allMatch(TelegramMediaOriginValidator::isTelegramFileIdChar);
    }

    private static boolean isTelegramFileIdChar(int character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '_'
                || character == '-';
    }
}
