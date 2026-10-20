package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StateProjectionPolicy}, the allow-list that governs what state may leave the process
 * towards an external or separate-process decision provider.
 *
 * <p>The record's own contract is "missing or invalid policy must fail closed". That makes the compact
 * constructor a security-relevant guard rather than a convenience: it is the only thing standing between a
 * misconfigured allow-list and an egress path that would project state through an empty or blank field set.
 * {@code RemoteDecisionEngine.resolveProjectionPolicy} and its self-hosted twin construct the policy from
 * {@code config.defaultProjectionPolicy()} and hand it straight to {@code StateProjectionRedactor}, so a
 * silent pass-through here would be a silent widening of egress.
 */
class StateProjectionPolicyTest {

    @Test
    void rejectsANullAllowList() {
        NullPointerException thrown = assertThrows(
                NullPointerException.class, () -> new StateProjectionPolicy(null, RedactionPolicy.DEFAULT));
        assertEquals("allowedFields", thrown.getMessage());
    }

    @Test
    void rejectsAnEmptyAllowList() {
        // An empty allow-list is the dangerous case: it reads as "project nothing", but a caller that
        // instead treated it as "no restriction" would leak the whole state. Fail closed instead.
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> new StateProjectionPolicy(Set.of(), RedactionPolicy.DEFAULT));
        assertTrue(thrown.getMessage().contains("must not be empty"));
        assertTrue(thrown.getMessage().contains("allow-list projection required"));
    }

    @Test
    void rejectsANullFieldName() {
        Set<String> fields = new HashSet<>();
        fields.add("tool_name");
        fields.add(null);

        NullPointerException thrown = assertThrows(
                NullPointerException.class, () -> new StateProjectionPolicy(fields, RedactionPolicy.DEFAULT));
        assertEquals("allowed field", thrown.getMessage());
    }

    @Test
    void rejectsABlankFieldName() {
        IllegalArgumentException empty = assertThrows(
                IllegalArgumentException.class,
                () -> new StateProjectionPolicy(Set.of("tool_name", ""), RedactionPolicy.DEFAULT));
        assertTrue(empty.getMessage().contains("must not be blank"));

        IllegalArgumentException whitespace = assertThrows(
                IllegalArgumentException.class,
                () -> new StateProjectionPolicy(Set.of("   "), RedactionPolicy.DEFAULT));
        assertTrue(whitespace.getMessage().contains("must not be blank"));
    }

    @Test
    void rejectsANullRedactionPolicy() {
        NullPointerException thrown =
                assertThrows(NullPointerException.class, () -> new StateProjectionPolicy(Set.of("tool_name"), null));
        assertEquals("redactionPolicy", thrown.getMessage());
    }

    @Test
    void copiesTheAllowListSoLaterMutationCannotWidenEgress() {
        // The non-vacuity check for the constructor's defensive copy. Without Set.copyOf the policy would
        // alias the caller's set, and adding a field afterwards would silently extend what may be projected.
        Set<String> mutable = new HashSet<>();
        mutable.add("tool_name");

        StateProjectionPolicy policy = new StateProjectionPolicy(mutable, RedactionPolicy.DEFAULT);
        mutable.add("secret");

        assertEquals(Set.of("tool_name"), policy.allowedFields(), "the policy must not follow the caller's set");
        assertThrows(
                UnsupportedOperationException.class,
                () -> policy.allowedFields().add("secret"),
                "the exposed set must be immutable");
    }

    @Test
    void ofCarriesBothComponents() {
        RedactionPolicy redaction = new RedactionPolicy(true, false, Set.of("password"));

        StateProjectionPolicy policy = StateProjectionPolicy.of(Set.of("tool_name", "risk"), redaction);

        assertEquals(Set.of("tool_name", "risk"), policy.allowedFields());
        assertSame(redaction, policy.redactionPolicy());
    }

    @Test
    void ofDefaultUsesTheDefaultRedactionPolicy() {
        StateProjectionPolicy policy = StateProjectionPolicy.ofDefault(Set.of("tool_name"));

        assertEquals(Set.of("tool_name"), policy.allowedFields());
        assertSame(RedactionPolicy.DEFAULT, policy.redactionPolicy());
    }

    @Test
    void equalityIsByBothComponents() {
        StateProjectionPolicy base = StateProjectionPolicy.ofDefault(Set.of("tool_name"));

        assertEquals(base, StateProjectionPolicy.ofDefault(Set.of("tool_name")));
        assertEquals(
                base.hashCode(),
                StateProjectionPolicy.ofDefault(Set.of("tool_name")).hashCode());
        // Field order must not matter: the allow-list is a set, not a sequence.
        assertEquals(
                StateProjectionPolicy.ofDefault(Set.of("a", "b")), StateProjectionPolicy.ofDefault(Set.of("b", "a")));

        assertNotEquals(base, StateProjectionPolicy.ofDefault(Set.of("risk")));
        assertNotEquals(
                base, StateProjectionPolicy.of(Set.of("tool_name"), new RedactionPolicy(false, false, Set.of())));
        assertNotEquals(base, null);
        assertNotEquals(base, "tool_name");
    }

    @Test
    void toStringNamesTheFieldsAndTheRedactionPolicy() {
        String rendered = StateProjectionPolicy.ofDefault(Set.of("tool_name")).toString();

        assertTrue(rendered.contains("allowedFields"), rendered);
        assertTrue(rendered.contains("tool_name"), rendered);
        assertTrue(rendered.contains("redactionPolicy"), rendered);
    }
}
