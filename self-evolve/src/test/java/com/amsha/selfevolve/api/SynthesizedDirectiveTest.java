package com.amsha.selfevolve.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.junit.jupiter.api.Test;

class SynthesizedDirectiveTest {

    @Test
    void sourceRecordIdsJson_hasJdbiColumnMapping() throws Exception {
        var constructor = SynthesizedDirective.class.getDeclaredConstructor(
                String.class,
                String.class,
                String.class,
                DirectiveState.class,
                double.class,
                String.class,
                String.class,
                int.class,
                int.class,
                Instant.class,
                Instant.class);
        var sourceRecordIds = constructor.getParameters()[5];
        assertEquals("sourceRecordIdsJson", sourceRecordIds.getName());
        assertEquals(
                "source_record_ids",
                sourceRecordIds.getAnnotation(ColumnName.class).value());
    }

    @Test
    void create_setsCandidiateState() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "Always run fix_fqns.py", 0.9, "", "");
        assertEquals(DirectiveState.CANDIDATE, d.state());
        assertEquals("u1", d.userId());
        assertEquals(0.9, d.confidence());
        assertNotNull(d.id());
    }

    @Test
    void withState_transitionsCorrectly() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "test", 0.8, "", "");
        SynthesizedDirective shadow = d.withState(DirectiveState.SHADOW);
        assertEquals(DirectiveState.SHADOW, shadow.state());
        assertTrue(
                shadow.updatedAt().isAfter(d.updatedAt()) || shadow.updatedAt().equals(d.updatedAt()));
    }

    @Test
    void withHit_incrementsHits() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "test", 0.8, "", "");
        assertEquals(0, d.evaluationHits());
        SynthesizedDirective hit = d.withHit();
        assertEquals(1, hit.evaluationHits());
    }

    @Test
    void withMiss_incrementsMisses() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "test", 0.8, "", "");
        SynthesizedDirective miss = d.withMiss();
        assertEquals(1, miss.evaluationMisses());
    }

    @Test
    void isInjectable_activeWithHighConfidence_true() {
        SynthesizedDirective d =
                SynthesizedDirective.create("u1", "test", 0.9, "", "").withState(DirectiveState.ACTIVE);
        assertTrue(d.isInjectable());
    }

    @Test
    void isInjectable_activeWithLowConfidence_false() {
        SynthesizedDirective d =
                SynthesizedDirective.create("u1", "test", 0.5, "", "").withState(DirectiveState.ACTIVE);
        assertFalse(d.isInjectable());
    }

    @Test
    void isInjectable_shadowWithHighConfidence_true() {
        SynthesizedDirective d =
                SynthesizedDirective.create("u1", "test", 0.8, "", "").withState(DirectiveState.SHADOW);
        assertTrue(d.isInjectable());
    }

    @Test
    void isInjectable_candidate_false() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "test", 0.9, "", "");
        assertFalse(d.isInjectable());
    }

    @Test
    void isInjectable_disabled_false() {
        SynthesizedDirective d =
                SynthesizedDirective.create("u1", "test", 0.9, "", "").withState(DirectiveState.DISABLED);
        assertFalse(d.isInjectable());
    }

    @Test
    void isReadyForPromotion_shadowWithEnoughHits_true() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "test", 0.8, "", "")
                .withState(DirectiveState.SHADOW)
                .withHit()
                .withHit()
                .withHit();
        assertTrue(d.isReadyForPromotion(3));
    }

    @Test
    void isReadyForPromotion_shadowWithFewHits_false() {
        SynthesizedDirective d = SynthesizedDirective.create("u1", "test", 0.8, "", "")
                .withState(DirectiveState.SHADOW)
                .withHit();
        assertFalse(d.isReadyForPromotion(3));
    }

    @Test
    void compactConstructor_blankId_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SynthesizedDirective(
                        "", "u1", "text", DirectiveState.CANDIDATE, 0.8, "", "", 0, 0, Instant.now(), Instant.now()));
    }

    @Test
    void compactConstructor_textTooLong_throws() {
        String longText = "x".repeat(601);
        assertThrows(IllegalArgumentException.class, () -> SynthesizedDirective.create("u1", longText, 0.8, "", ""));
    }

    @Test
    void compactConstructor_invalidConfidence_throws() {
        assertThrows(IllegalArgumentException.class, () -> SynthesizedDirective.create("u1", "text", 1.5, "", ""));
    }
}
