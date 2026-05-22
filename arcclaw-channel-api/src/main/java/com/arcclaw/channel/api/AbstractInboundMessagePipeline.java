package com.arcclaw.channel.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reusable base pipeline implementing the Template Method pattern for channel inbound message processing.
 * Manages concurrency locks and asynchronous processing delegation.
 */
public abstract class AbstractInboundMessagePipeline {

    protected final ChannelServices channelServices;
    protected final ChannelType channelType;

    // Concurrent processing locks mapped to system timestamp of creation
    private final Map<String, Long> processingFlags = new ConcurrentHashMap<>();
    private static final long LOCK_TTL_MILLIS = 10 * 60 * 1000; // 10 minutes

    protected AbstractInboundMessagePipeline(ChannelServices channelServices, ChannelType channelType) {
        this.channelServices = channelServices;
        this.channelType = channelType;
    }

    protected abstract void sendRawReply(ChannelUser user, String text);
    protected abstract void logWarn(String format, Object... args);
    protected abstract void logInfo(String format, Object... args);
    protected abstract void logError(String format, Throwable t);

    /**
     * Executes the pipeline flow for a validated text message.
     */
    public void processInboundMessage(String chatId, String text, ChannelUser user, ChannelMessage channelMessage) {
        if (chatId == null || chatId.strip().isEmpty()) {
            logWarn("No chatId provided, dropping message");
            return;
        }

        if (text == null) {
            logWarn("Received null text message from chat %s - notifying user", chatId);
            if (user != null) {
                sendRawReply(user, "⚠️ I can only process text messages.");
            }
            return;
        }

        if (user == null) {
            logWarn("Dropped message with null user context in chat %s", chatId);
            return;
        }

        if (channelMessage == null) {
            return;
        }

        // Bypassing locks for reflex/slash commands
        boolean isReflex = text.trim().startsWith("/") || text.trim().startsWith("@");
        boolean acquiredLock = false;

        if (!isReflex) {
            long now = System.currentTimeMillis();
            pruneExpiredLocks(now);
            Long existingLockTime = processingFlags.putIfAbsent(chatId, now);
            if (existingLockTime != null) {
                sendRawReply(user, "⏳ Still processing your previous message. Please wait.");
                return;
            }
            acquiredLock = true;
            sendRawReply(user, "⏳");
        }

        logInfo("Message received for chat %s, delegating to SPI", chatId);
        final boolean finalAcquiredLock = acquiredLock;

        channelServices.processAsync(channelMessage)
            .handle((res, ex) -> {
                if (ex != null) {
                    logError("Uncaught exception in message processing for chat " + chatId, ex);
                    sendRawReply(user, "Sorry, an internal error occurred while processing your message.");
                }
                if (finalAcquiredLock) {
                    processingFlags.remove(chatId);
                }
                return null;
            });
    }

    private void pruneExpiredLocks(long now) {
        processingFlags.entrySet().removeIf(entry -> (now - entry.getValue()) > LOCK_TTL_MILLIS);
    }

    /** For verification and tests only */
    public boolean isProcessing(String chatId) {
        long now = System.currentTimeMillis();
        Long lockTime = processingFlags.get(chatId);
        if (lockTime == null) {
            return false;
        }
        if ((now - lockTime) > LOCK_TTL_MILLIS) {
            processingFlags.remove(chatId);
            return false;
        }
        return true;
    }
}
