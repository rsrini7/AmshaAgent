package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Set;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;

import org.junit.jupiter.api.Test;

class TurnRequirementsTest {

    @Test
    void textOnlyWhenNoAttachments() {
        assertTrue(TurnRequirements.fromAttachments(List.of()).isTextOnly());
        assertTrue(TurnRequirements.textOnly().isTextOnly());
    }

    @Test
    void mapsAttachmentTypesAndMimes() {
        MediaAttachment image = MediaAttachment.ofUri(
                "a1", AttachmentType.IMAGE, "image/png", "x.png", 10, URI.create("https://example.com/a.png"), null);
        MediaAttachment audio = MediaAttachment.ofUri(
                "a2", AttachmentType.AUDIO, "audio/wav", "x.wav", 20, URI.create("https://example.com/b.wav"), null);

        TurnRequirements requirements = TurnRequirements.fromAttachments(List.of(image, audio));

        assertEquals(Set.of(AttachmentType.IMAGE, AttachmentType.AUDIO), requirements.requiredModalities());
        assertEquals(30L, requirements.totalPayloadBytes());
        assertFalse(requirements.isTextOnly());
    }

    @Test
    void fromContextDerivesGoalSignals() {
        TurnRequirements chitchat = TurnRequirements.fromContext(null, "hi there");
        assertTrue(chitchat.isChitchat());
        assertFalse(chitchat.requiresStructuredOutput());

        TurnRequirements structured =
                TurnRequirements.fromContext(null, "please extract the json schema for the user table");
        assertFalse(structured.isChitchat());
        assertTrue(structured.requiresStructuredOutput());

        TurnRequirements blank = TurnRequirements.fromContext(null, null);
        assertTrue(blank.isChitchat());
        assertFalse(blank.requiresStructuredOutput());
    }

    @Test
    void nullSetsAreNormalizedAndImmutable() {
        TurnRequirements requirements = new TurnRequirements(null, null, 0L, false, false);
        assertTrue(requirements.isTextOnly());
        assertTrue(requirements.requiredModalities().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> requirements.requiredModalities().add(AttachmentType.IMAGE));
    }
}
