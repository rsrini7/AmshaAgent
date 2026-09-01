package com.amsha.core.speech;

/** Resolved speech output behaviour for one completed agent turn. */
public enum OutputMode {

    /** Send the text answer only. */
    TEXT_ONLY,

    /** Send the text answer first, then the synthesized audio as a separate message. */
    TEXT_AND_AUDIO,

    /** Send audio only; fall back to text when synthesis or delivery fails. */
    AUDIO_ONLY
}
