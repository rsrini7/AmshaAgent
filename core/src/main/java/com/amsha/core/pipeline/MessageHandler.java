package com.amsha.core.pipeline;

/**
 * Single step in the message processing pipeline.
 */
public interface MessageHandler {

    /**
     * Priority of the handler. Lower numbers run first.
     * Suggested ranges:
     * 10-20: Security, Allowlist, Global Reflexes (Deny/Approve)
     * 30-40: Session/Context Resolution
     * 50-60: Content Transformation (Intent Classification, Compaction)
     * 70-80: Dispatch to Agent/Reasoning
     */
    int priority();

    /**
     * Process the message.
     * @return {@link HandleResult#STOP} if the pipeline should halt immediately,
     *         {@link HandleResult#CONTINUE} to pass to the next handler.
     */
    HandleResult handle(MessageContext context);
}
