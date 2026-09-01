package com.amsha.core.speech;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.preference.UserPreferenceService;
import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.audio.SpeechSynthesisPort;
import com.amsha.llm.api.audio.SpeechSynthesisRequest;
import com.amsha.llm.api.audio.SpeechSynthesisResult;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.vertx.core.Context;

/**
 * Delivers optional speech output for one completed agent turn.
 *
 * <p>Speech is an enhancement, never a requirement. Every failure path returns {@code false} so the
 * caller keeps or sends the normal text answer; this service never throws for an expected failure
 * and never turns a synthesis problem into a failed agent turn.
 *
 * <p>Synthesis and channel delivery are synchronous and must not run on the Vert.x event loop. The
 * normal entry point is {@code AgentDispatchHandler}, which runs on the worker thread supplied by
 * {@code TurnDispatchService.dispatchAsync} (a {@code ManagedExecutor}) — channel messages reach it
 * via {@code ChannelServicesImpl.processAsync}, and the REST/CLI entry points run on their own
 * non-event-loop threads. The guard here therefore only triggers in anomalous callers; it fails
 * closed to text instead of blocking the loop.
 */
@ApplicationScoped
public class SpeechReplyService {

    private static final Logger log = Logger.getLogger(SpeechReplyService.class);
    private static final String DEFAULT_RESPONSE_FORMAT = "wav";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final int DEFAULT_MAX_INPUT_CHARS = 1200;

    private final int maxInputChars;
    private final Instance<SpeechSynthesisPort> speechPort;
    private final UserPreferenceService userPreferenceService;
    private final String responseFormat;
    private final Duration timeout;
    private final String audioFileName;

    @Inject
    public SpeechReplyService(
            Instance<SpeechSynthesisPort> speechPort,
            UserPreferenceService userPreferenceService,
            @ConfigProperty(name = "amsha.tts.max-input-chars", defaultValue = "1200") int maxInputChars,
            @ConfigProperty(name = "amsha.tts.response-format", defaultValue = DEFAULT_RESPONSE_FORMAT)
                    String responseFormat,
            @ConfigProperty(name = "amsha.tts.timeout", defaultValue = "PT120S") Duration timeout) {
        this.speechPort = speechPort;
        this.userPreferenceService = userPreferenceService;
        this.maxInputChars = maxInputChars > 0 ? maxInputChars : DEFAULT_MAX_INPUT_CHARS;
        // The format is shared with the provider's config; the channel derives the real extension
        // from the MIME type, so this file name is advisory and stays consistent with the format.
        this.responseFormat = responseFormat == null || responseFormat.isBlank()
                ? DEFAULT_RESPONSE_FORMAT
                : responseFormat.trim().toLowerCase(Locale.ROOT);
        this.timeout = timeout != null && !timeout.isNegative() && !timeout.isZero() ? timeout : DEFAULT_TIMEOUT;
        this.audioFileName = "amsha-reply." + this.responseFormat;
    }

    /**
     * Synthesizes and delivers audio for the resolved mode.
     *
     * @return true when audio was delivered; false when the caller must keep or send text
     */
    public boolean deliver(ChannelSender sender, ChannelUser user, OutputMode mode, String formattedAnswer) {
        if (sender == null || user == null || mode == null || mode == OutputMode.TEXT_ONLY) {
            return false;
        }
        if (formattedAnswer == null || formattedAnswer.isBlank()) {
            return false;
        }
        if (Context.isOnEventLoopThread()) {
            log.warn("[TTS] Skipping audio: synthesis must not block the Vert.x event loop");
            return false;
        }
        SpeechSynthesisPort port = resolvePort();
        if (port == null) {
            return false;
        }
        String speakable = SpeakableTextProjector.truncate(
                SpeakableTextProjector.project(formattedAnswer), effectiveMaxInputChars());
        if (speakable.isBlank()) {
            return false;
        }
        String userVoice = userPreferenceService != null
                ? userPreferenceService.getEffectiveVoice(user.platformId()).orElse(null)
                : null;
        SpeechSynthesisRequest request =
                SpeechSynthesisRequest.of(UUID.randomUUID().toString(), speakable, userVoice, responseFormat, timeout);
        if (!port.supports(request)) {
            return false;
        }
        SpeechSynthesisResult result = synthesize(port, request);
        if (result == null || result.status() != SpeechSynthesisResult.Status.SYNTHESIZED) {
            log.debugf("[TTS] Audio unavailable, falling back to text: %s", reason(result));
            return false;
        }
        byte[] audio = result.audio();
        if (audio == null || audio.length == 0) {
            return false;
        }
        return sendAudio(sender, user, audio, result.mimeType());
    }

    private SpeechSynthesisResult synthesize(SpeechSynthesisPort port, SpeechSynthesisRequest request) {
        try {
            return port.synthesize(request);
        } catch (RuntimeException e) {
            log.debugf("[TTS] Synthesis threw, falling back to text: %s", LogUtil.rootMessage(e));
            return null;
        }
    }

    private boolean sendAudio(ChannelSender sender, ChannelUser user, byte[] audio, String mimeType) {
        try {
            return sender.sendAudio(user, audio, mimeType, audioFileName);
        } catch (RuntimeException e) {
            log.debugf("[TTS] Audio delivery failed, falling back to text: %s", LogUtil.rootMessage(e));
            return false;
        }
    }

    private SpeechSynthesisPort resolvePort() {
        if (speechPort == null || !speechPort.isResolvable()) {
            return null;
        }
        try {
            return speechPort.get();
        } catch (RuntimeException e) {
            log.debugf("[TTS] Speech synthesis port unavailable: %s", LogUtil.rootMessage(e));
            return null;
        }
    }

    /** Returns only a categorical status or failure tag; never text, payload, or user data. */
    private static String reason(SpeechSynthesisResult result) {
        if (result == null) {
            return "no_result";
        }
        if (result.failureReason() != null) {
            return result.failureReason();
        }
        return result.status().name().toLowerCase(Locale.ROOT);
    }

    private int effectiveMaxInputChars() {
        return maxInputChars > 0 ? maxInputChars : DEFAULT_MAX_INPUT_CHARS;
    }
}
