package com.amsha.channel.api;

/**
 * Shared media MIME-type vocabulary for the reactor.
 * <p>
 * These tokens are load-bearing: the media allowlist ({@code MediaBudgetLimits.ALLOWED_MIME_TYPES}),
 * the multimodal capability sets ({@code MultimodalCapabilities}), and the per-type magic-header
 * validator ({@code MediaSecurityValidator}) must all agree — a type present in the allowlist but
 * missing a validation branch (or vice versa) is a silent security gap. The same strings also drive
 * MIME&harr;extension mapping in the channel senders and audio-format handling in the provider and
 * ONNX layers.
 * <p>
 * Lives in {@code channel-api} because it is the lowest module reachable from every consumer
 * (channel senders, security, security-api, llm-provider-api, core, local-ml-onnx) and already owns
 * the attachment contract ({@link AttachmentType}, {@link MediaAttachment}).
 */
public final class MediaTypes {

    private MediaTypes() {}

    // -- Images ------------------------------------------------------------

    /** {@code image/png}. */
    public static final String PNG = "image/png";

    /** {@code image/jpeg}. */
    public static final String JPEG = "image/jpeg";

    /** {@code image/webp}. */
    public static final String WEBP = "image/webp";

    /** {@code image/gif}. */
    public static final String GIF = "image/gif";

    // -- Documents ---------------------------------------------------------

    /** {@code application/pdf}. */
    public static final String PDF = "application/pdf";

    // -- Audio (canonical) -------------------------------------------------

    /** {@code audio/mpeg} (MP3). */
    public static final String MPEG = "audio/mpeg";

    /** {@code audio/wav} (WAVE). */
    public static final String WAV = "audio/wav";

    /** {@code audio/ogg} (Ogg container). */
    public static final String OGG = "audio/ogg";

    /** {@code audio/opus} (Opus in Ogg). */
    public static final String OPUS = "audio/opus";

    /** {@code audio/webm} (WebM audio). */
    public static final String WEBM = "audio/webm";

    /** {@code audio/flac} (FLAC). */
    public static final String FLAC = "audio/flac";

    /** {@code audio/aac} (AAC). */
    public static final String AAC = "audio/aac";

    /** {@code audio/mp4} (MP4 container). */
    public static final String MP4 = "audio/mp4";

    /** {@code audio/pcm} (raw PCM). */
    public static final String PCM = "audio/pcm";

    // -- Audio aliases -----------------------------------------------------

    /** {@code audio/mp3} — an alias of {@link #MPEG}. */
    public static final String MP3 = "audio/mp3";

    /** {@code audio/x-wav} — an alias of {@link #WAV}. */
    public static final String X_WAV = "audio/x-wav";

    /** {@code audio/wave} — an alias of {@link #WAV}. */
    public static final String WAVE = "audio/wave";

    /** {@code audio/x-flac} — an alias of {@link #FLAC}. */
    public static final String X_FLAC = "audio/x-flac";

    /** {@code audio/m4a} — an alias of {@link #MP4}. */
    public static final String M4A = "audio/m4a";

    /** {@code audio/x-m4a} — an alias of {@link #MP4}. */
    public static final String X_M4A = "audio/x-m4a";

    /** {@code application/ogg} — an alias of {@link #OGG}. */
    public static final String APPLICATION_OGG = "application/ogg";

    /** {@code video/webm} — the container a WebM audio stream is often mislabelled with. */
    public static final String VIDEO_WEBM = "video/webm";
}
