package com.amsha.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;

import org.apache.camel.component.telegram.model.IncomingAudio;
import org.apache.camel.component.telegram.model.IncomingDocument;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.IncomingPhotoSize;
import org.apache.camel.component.telegram.model.IncomingVoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelegramMessageMapperTest {

    private TelegramMessageMapper mapper;

    @BeforeEach
    void setUp() {
        TelegramMediaResolver resolver = new TelegramMediaResolver();
        mapper = new TelegramMessageMapper(resolver);
    }

    @Test
    void testMapValidMessage() {
        ChannelUser user = new ChannelUser("u1", "c1", "Alice", ChannelType.TELEGRAM);
        ChannelMessage msg = mapper.map("hello world", user, "c1");

        assertNotNull(msg);
        assertInstanceOf(ChannelMessage.TelegramMessage.class, msg);
        assertEquals("hello world", msg.text());
        assertEquals("c1", msg.correlationId());
        assertEquals(user, msg.sender());
    }

    @Test
    void testMapNullOrBlank() {
        ChannelUser user = new ChannelUser("u1", "c1", "Alice", ChannelType.TELEGRAM);
        assertNull(mapper.map((String) null, user, "c1"));
        assertNull(mapper.map("   ", user, "c1"));
        assertNull(mapper.map((IncomingMessage) null, user, "c1"));
    }

    @Test
    void testMapIncomingMessageTextOnly() {
        ChannelUser user = new ChannelUser("u1", "c1", "Alice", ChannelType.TELEGRAM);
        IncomingMessage msg = new IncomingMessage();
        msg.setText("Check status");

        ChannelMessage res = mapper.map(msg, user, "c1");
        assertNotNull(res);
        assertEquals("Check status", res.text());
        assertTrue(res.attachments().isEmpty());
    }

    @Test
    void testMapIncomingMessagePhotoOnly() {
        ChannelUser user = new ChannelUser("u1", "c1", "Alice", ChannelType.TELEGRAM);
        IncomingMessage msg = new IncomingMessage();

        IncomingPhotoSize p1 = new IncomingPhotoSize();
        p1.setFileId("photo_small");
        p1.setWidth(100);
        p1.setHeight(100);

        IncomingPhotoSize p2 = new IncomingPhotoSize();
        p2.setFileId("photo_large");
        p2.setWidth(800);
        p2.setHeight(800);
        p2.setFileSize(50000L);

        msg.setPhoto(List.of(p1, p2));

        ChannelMessage res = mapper.map(msg, user, "c1");
        assertNotNull(res);
        assertNull(res.text());
        assertEquals(1, res.attachments().size());
        assertEquals("photo_photo_large.jpg", res.attachments().get(0).fileName());
        assertEquals(AttachmentType.IMAGE, res.attachments().get(0).type());
    }

    @Test
    void testMapIncomingMessagePhotoWithCaption() {
        ChannelUser user = new ChannelUser("u1", "c1", "Alice", ChannelType.TELEGRAM);
        IncomingMessage msg = new IncomingMessage();
        msg.setCaption("Analyze this chart");

        IncomingPhotoSize p = new IncomingPhotoSize();
        p.setFileId("chart_id");
        p.setWidth(500);
        p.setHeight(500);
        msg.setPhoto(List.of(p));

        ChannelMessage res = mapper.map(msg, user, "c1");
        assertNotNull(res);
        assertEquals("Analyze this chart", res.text());
        assertEquals("Analyze this chart", res.caption().orElse(null));
        assertEquals(1, res.attachments().size());
    }

    @Test
    void testMapIncomingMessageDocumentPdf() {
        ChannelUser user = new ChannelUser("u1", "c1", "Alice", ChannelType.TELEGRAM);
        IncomingMessage msg = new IncomingMessage();
        msg.setCaption("Here is the architecture spec");

        IncomingDocument doc = new IncomingDocument();
        doc.setFileId("spec_pdf");
        doc.setFileName("spec.pdf");
        doc.setMimeType("application/pdf");
        doc.setFileSize(123456L);
        msg.setDocument(doc);

        ChannelMessage res = mapper.map(msg, user, "c1");
        assertNotNull(res);
        assertEquals("Here is the architecture spec", res.text());
        assertEquals(1, res.attachments().size());
        assertEquals(AttachmentType.DOCUMENT_PDF, res.attachments().get(0).type());
        assertEquals("application/pdf", res.attachments().get(0).mimeType());
    }

    @Test
    void testMapIncomingMessageAudioAndVoice() {
        ChannelUser user = new ChannelUser("u1", "c1", "Alice", ChannelType.TELEGRAM);

        IncomingMessage audioMsg = new IncomingMessage();
        IncomingAudio audio = new IncomingAudio();
        audio.setFileId("track_1");
        audio.setTitle("podcast.mp3");
        audio.setMimeType("audio/mpeg");
        audioMsg.setAudio(audio);

        ChannelMessage audioRes = mapper.map(audioMsg, user, "c1");
        assertNotNull(audioRes);
        assertEquals(1, audioRes.attachments().size());
        assertEquals(AttachmentType.AUDIO, audioRes.attachments().get(0).type());

        IncomingMessage voiceMsg = new IncomingMessage();
        IncomingVoice voice = new IncomingVoice();
        voice.setFileId("voice_1");
        voice.setMimeType("audio/ogg");
        voiceMsg.setVoice(voice);

        ChannelMessage voiceRes = mapper.map(voiceMsg, user, "c1");
        assertNotNull(voiceRes);
        assertEquals(1, voiceRes.attachments().size());
        assertEquals(AttachmentType.AUDIO, voiceRes.attachments().get(0).type());
    }

    @Test
    void testMapIncomingMessageEmptyReturnsNull() {
        ChannelUser user = new ChannelUser("u1", "c1", "Alice", ChannelType.TELEGRAM);
        IncomingMessage empty = new IncomingMessage();
        assertNull(mapper.map(empty, user, "c1"));
    }
}
