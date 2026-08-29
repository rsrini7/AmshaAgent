package com.amsha.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;

import org.apache.camel.component.telegram.model.IncomingAudio;
import org.apache.camel.component.telegram.model.IncomingDocument;
import org.apache.camel.component.telegram.model.IncomingPhotoSize;
import org.apache.camel.component.telegram.model.IncomingVoice;
import org.junit.jupiter.api.Test;

class TelegramMediaResolverTest {

    @Test
    void photoBecomesOpaqueReferenceWithoutInlinePayload() {
        TelegramMediaResolver resolver = new TelegramMediaResolver();
        IncomingPhotoSize photo = new IncomingPhotoSize();
        photo.setFileId("photo_123");
        photo.setFileSize(2048L);

        MediaAttachment attachment = resolver.resolvePhoto(photo);

        assertNotNull(attachment);
        assertEquals(AttachmentType.IMAGE, attachment.type());
        assertEquals("image/jpeg", attachment.mimeType());
        assertEquals("photo_photo_123.jpg", attachment.fileName());
        assertEquals(2048L, attachment.sizeBytes());
        assertEquals("telegram://file/photo_123", attachment.sourceUri().toString());
        assertNull(attachment.inlineData());
        assertEquals("photo_123", attachment.metadata().get("file_id"));
    }

    @Test
    void documentsUseMimeAndNameToChooseSafeType() {
        TelegramMediaResolver resolver = new TelegramMediaResolver();

        IncomingDocument pdf = new IncomingDocument();
        pdf.setFileId("doc_pdf_1");
        pdf.setFileName("report.pdf");
        pdf.setMimeType("application/pdf; charset=binary");
        pdf.setFileSize(4096L);

        IncomingDocument image = new IncomingDocument();
        image.setFileId("doc_img_1");
        image.setFileName("screenshot.png");
        image.setMimeType("image/png");

        IncomingDocument other = new IncomingDocument();
        other.setFileId("doc_bin_1");
        other.setFileName("data.bin");
        other.setMimeType("application/octet-stream");

        assertEquals(AttachmentType.DOCUMENT_PDF, resolver.resolveDocument(pdf).type());
        assertEquals("application/pdf", resolver.resolveDocument(pdf).mimeType());
        assertEquals(AttachmentType.IMAGE, resolver.resolveDocument(image).type());
        assertEquals(AttachmentType.OTHER, resolver.resolveDocument(other).type());
    }

    @Test
    void audioAndVoiceNotesAreMappedWithUnknownSizeWhenMissing() {
        TelegramMediaResolver resolver = new TelegramMediaResolver();
        IncomingAudio audio = new IncomingAudio();
        audio.setFileId("audio_99");
        audio.setTitle("Song Title");
        audio.setMimeType("audio/mpeg");

        IncomingVoice voice = new IncomingVoice();
        voice.setFileId("voice_88");

        MediaAttachment audioAttachment = resolver.resolveAudio(audio);
        MediaAttachment voiceAttachment = resolver.resolveVoice(voice);

        assertEquals(AttachmentType.AUDIO, audioAttachment.type());
        assertEquals("Song Title", audioAttachment.fileName());
        assertEquals(-1L, audioAttachment.sizeBytes());
        assertEquals("audio/ogg", voiceAttachment.mimeType());
        assertEquals("voice_voice_88.ogg", voiceAttachment.fileName());
    }

    @Test
    void invalidOrMissingDescriptorsAreDroppedFailClosed() {
        TelegramMediaResolver resolver = new TelegramMediaResolver();
        assertNull(resolver.resolvePhoto(null));
        assertNull(resolver.resolveDocument(null));
        assertNull(resolver.resolveAudio(null));
        assertNull(resolver.resolveVoice(null));

        IncomingPhotoSize invalid = new IncomingPhotoSize();
        invalid.setFileId("../../secret");
        assertNull(resolver.resolvePhoto(invalid));
        assertThrows(
                NullPointerException.class,
                () -> resolver.resolveMedia(null, AttachmentType.IMAGE, "image/jpeg", "file.jpg", 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolveMedia("bad/id", AttachmentType.IMAGE, "image/jpeg", "file.jpg", 100));
    }

    @Test
    void filenamesAreConfinedToAFlatBoundedDisplayName() {
        TelegramMediaResolver resolver = new TelegramMediaResolver();
        IncomingDocument document = new IncomingDocument();
        document.setFileId("doc_1");
        document.setFileName("../private/\treport.pdf");
        document.setMimeType("application/pdf");

        MediaAttachment attachment = resolver.resolveDocument(document);

        assertNotNull(attachment);
        assertEquals(".._private_report.pdf", attachment.fileName());
        assertEquals(AttachmentType.DOCUMENT_PDF, attachment.type());
    }
}
