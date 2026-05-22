package com.arcclaw.channel.api;

/**
 * Reusable base pipeline implementing the Template Method pattern for channel inbound message processing.
 * Manages concurrency locks and asynchronous processing delegation.
 */
public abstract class AbstractInboundMessagePipeline {

    protected final ChannelServices channelServices;
    protected final ChannelType channelType;
    private final ProcessingLockManager lockManager;

    protected AbstractInboundMessagePipeline(ChannelServices channelServices, ChannelType channelType,
                                              ProcessingLockManager lockManager) {
        this.channelServices = channelServices;
        this.channelType = channelType;
        this.lockManager = lockManager;
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
            if (!lockManager.tryLock(chatId)) {
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
                    lockManager.unlock(chatId);
                }
                return null;
            });
    }

    /**
     * Checks whether the given chat is currently being processed.
     * Exposed for test verification. Production code should use the
     * {@link ProcessingLockManager} directly.
     */
    public boolean isProcessing(String chatId) {
        return lockManager.isLocked(chatId);
    }
}
