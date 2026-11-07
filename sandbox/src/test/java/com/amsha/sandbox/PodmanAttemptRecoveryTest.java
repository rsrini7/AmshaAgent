package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import com.amsha.core.sandbox.SandboxRecoveryEvidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PodmanAttemptRecoveryTest {

    @TempDir
    Path tempDir;

    private static String encode(String val) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(val.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void inspectRecoveryEvidenceRunningContainerReturnsRunningVerified() throws IOException {
        String wsLabel = encode("ws-1");
        Path fakePodman = FakePodmanHelper.createFakePodman(
                tempDir,
                "podman-running",
                "if [ \"$1\" = \"inspect\" ]; then\n"
                        + "  echo 'c-imm-1|running|0|" + wsLabel + "|att-1'\n"
                        + "  exit 0\n"
                        + "fi\n"
                        + "exit 1\n");

        SandboxRecoveryEvidence ev = PodmanAttemptContainerSupport.inspectRecoveryEvidence(
                fakePodman.toString(), "cnt-name", "ws-1", "att-1", "c-imm-1");

        assertTrue(ev instanceof SandboxRecoveryEvidence.RunningVerified);
        SandboxRecoveryEvidence.RunningVerified running = (SandboxRecoveryEvidence.RunningVerified) ev;
        assertEquals("podman-container:cnt-name", running.executionHandle());
        assertEquals("container:c-imm-1", running.processStartIdentity());
    }

    @Test
    void inspectRecoveryEvidenceExitedContainerSuccessReturnsExitedVerified() throws IOException {
        String wsLabel = encode("ws-1");
        Path fakePodman = FakePodmanHelper.createFakePodman(
                tempDir,
                "podman-exited-0",
                "if [ \"$1\" = \"inspect\" ]; then\n"
                        + "  echo 'c-imm-1|exited|0|" + wsLabel + "|att-1'\n"
                        + "  exit 0\n"
                        + "elif [ \"$1\" = \"logs\" ]; then\n"
                        + "  echo 'command succeeded output'\n"
                        + "  exit 0\n"
                        + "fi\n"
                        + "exit 1\n");

        SandboxRecoveryEvidence ev = PodmanAttemptContainerSupport.inspectRecoveryEvidence(
                fakePodman.toString(), "cnt-name", "ws-1", "att-1", "c-imm-1");

        assertTrue(ev instanceof SandboxRecoveryEvidence.ExitedVerified);
        SandboxRecoveryEvidence.ExitedVerified exited = (SandboxRecoveryEvidence.ExitedVerified) ev;
        assertEquals(0, exited.exitCode());
        assertTrue(exited.output().contains("command succeeded output"));
    }

    @Test
    void inspectRecoveryEvidenceExitedContainerErrorReturnsExitedVerified() throws IOException {
        String wsLabel = encode("ws-1");
        Path fakePodman = FakePodmanHelper.createFakePodman(
                tempDir,
                "podman-exited-err",
                "if [ \"$1\" = \"inspect\" ]; then\n"
                        + "  echo 'c-imm-1|exited|42|" + wsLabel + "|att-1'\n"
                        + "  exit 0\n"
                        + "elif [ \"$1\" = \"logs\" ]; then\n"
                        + "  echo 'syntax error'\n"
                        + "  exit 0\n"
                        + "fi\n"
                        + "exit 1\n");

        SandboxRecoveryEvidence ev = PodmanAttemptContainerSupport.inspectRecoveryEvidence(
                fakePodman.toString(), "cnt-name", "ws-1", "att-1", "c-imm-1");

        assertTrue(ev instanceof SandboxRecoveryEvidence.ExitedVerified);
        SandboxRecoveryEvidence.ExitedVerified exited = (SandboxRecoveryEvidence.ExitedVerified) ev;
        assertEquals(42, exited.exitCode());
        assertTrue(exited.output().contains("syntax error"));
    }

    @Test
    void inspectRecoveryEvidenceWorkspaceLabelMismatchReturnsOwnershipMismatch() throws IOException {
        String wrongWsLabel = encode("wrong-ws");
        Path fakePodman = FakePodmanHelper.createFakePodman(
                tempDir,
                "podman-mismatch-ws",
                "if [ \"$1\" = \"inspect\" ]; then\n"
                        + "  echo 'c-imm-1|running|0|" + wrongWsLabel + "|att-1'\n"
                        + "  exit 0\n"
                        + "fi\n"
                        + "exit 1\n");

        SandboxRecoveryEvidence ev = PodmanAttemptContainerSupport.inspectRecoveryEvidence(
                fakePodman.toString(), "cnt-name", "ws-1", "att-1", "c-imm-1");

        assertTrue(ev instanceof SandboxRecoveryEvidence.OwnershipMismatch);
    }

    @Test
    void inspectRecoveryEvidenceAttemptLabelMismatchReturnsOwnershipMismatch() throws IOException {
        String wsLabel = encode("ws-1");
        Path fakePodman = FakePodmanHelper.createFakePodman(
                tempDir,
                "podman-mismatch-att",
                "if [ \"$1\" = \"inspect\" ]; then\n"
                        + "  echo 'c-imm-1|running|0|" + wsLabel + "|different-att'\n"
                        + "  exit 0\n"
                        + "fi\n"
                        + "exit 1\n");

        SandboxRecoveryEvidence ev = PodmanAttemptContainerSupport.inspectRecoveryEvidence(
                fakePodman.toString(), "cnt-name", "ws-1", "att-1", "c-imm-1");

        assertTrue(ev instanceof SandboxRecoveryEvidence.OwnershipMismatch);
    }

    @Test
    void inspectRecoveryEvidenceContainerIdMismatchReturnsOwnershipMismatch() throws IOException {
        String wsLabel = encode("ws-1");
        Path fakePodman = FakePodmanHelper.createFakePodman(
                tempDir,
                "podman-mismatch-cid",
                "if [ \"$1\" = \"inspect\" ]; then\n"
                        + "  echo 'unexpected-id|running|0|" + wsLabel + "|att-1'\n"
                        + "  exit 0\n"
                        + "fi\n"
                        + "exit 1\n");

        SandboxRecoveryEvidence ev = PodmanAttemptContainerSupport.inspectRecoveryEvidence(
                fakePodman.toString(), "cnt-name", "ws-1", "att-1", "expected-cid");

        assertTrue(ev instanceof SandboxRecoveryEvidence.OwnershipMismatch);
    }

    @Test
    void inspectRecoveryEvidenceLegacyUnlabeledContainerReturnsOwnershipMismatch() throws IOException {
        Path fakePodman = FakePodmanHelper.createFakePodman(
                tempDir,
                "podman-legacy",
                "if [ \"$1\" = \"inspect\" ]; then\n"
                        + "  echo 'legacy-zc-cid|running|0||'\n"
                        + "  exit 0\n"
                        + "fi\n"
                        + "exit 1\n");

        SandboxRecoveryEvidence ev = PodmanAttemptContainerSupport.inspectRecoveryEvidence(
                fakePodman.toString(), "zc-cnt-name", "ws-1", "att-1", "legacy-zc-cid");

        assertTrue(ev instanceof SandboxRecoveryEvidence.OwnershipMismatch);
    }

    @Test
    void inspectRecoveryEvidenceMissingContainerReturnsMissing() throws IOException {
        Path fakePodman = FakePodmanHelper.createFakePodman(tempDir, "podman-missing", "exit 1\n");

        SandboxRecoveryEvidence ev = PodmanAttemptContainerSupport.inspectRecoveryEvidence(
                fakePodman.toString(), "nonexistent", "ws-1", "att-1", "c-1");

        assertTrue(ev instanceof SandboxRecoveryEvidence.Missing);
    }

    @Test
    void inspectRecoveryEvidenceUnparseableExitCodeReturnsInconclusive() throws IOException {
        String wsLabel = encode("ws-1");
        Path fakePodman = FakePodmanHelper.createFakePodman(
                tempDir,
                "podman-bad-exit",
                "if [ \"$1\" = \"inspect\" ]; then\n"
                        + "  echo 'c-imm-1|exited|corrupted_code|" + wsLabel + "|att-1'\n"
                        + "  exit 0\n"
                        + "fi\n"
                        + "exit 1\n");

        SandboxRecoveryEvidence ev = PodmanAttemptContainerSupport.inspectRecoveryEvidence(
                fakePodman.toString(), "cnt-name", "ws-1", "att-1", "c-imm-1");

        assertTrue(ev instanceof SandboxRecoveryEvidence.Inconclusive);
    }

    @Test
    void inspectRecoveryEvidenceLogReadFailureReturnsInconclusive() throws IOException {
        String wsLabel = encode("ws-1");
        Path fakePodman = FakePodmanHelper.createFakePodman(
                tempDir,
                "podman-logs-fail",
                "if [ \"$1\" = \"inspect\" ]; then\n"
                        + "  echo 'c-imm-1|exited|0|" + wsLabel + "|att-1'\n"
                        + "  exit 0\n"
                        + "elif [ \"$1\" = \"logs\" ]; then\n"
                        + "  exit 1\n"
                        + "fi\n"
                        + "exit 1\n");

        SandboxRecoveryEvidence ev = PodmanAttemptContainerSupport.inspectRecoveryEvidence(
                fakePodman.toString(), "cnt-name", "ws-1", "att-1", "c-imm-1");

        assertTrue(ev instanceof SandboxRecoveryEvidence.Inconclusive);
    }

    @Test
    void terminateContainerRunningVerifiedTargetsImmutableIdAndStops() throws IOException {
        String wsLabel = encode("ws-1");
        Path stateFile = tempDir.resolve("container_stopped.flag");
        Path stoppedTargetFile = tempDir.resolve("stopped_target.txt");
        Path fakePodman = FakePodmanHelper.createFakePodman(
                tempDir,
                "podman-terminate",
                "if [ \"$1\" = \"inspect\" ]; then\n"
                        + "  if [ -f '" + stateFile.toString() + "' ]; then\n"
                        + "    echo 'c-imm-1|exited|0|" + wsLabel + "|att-1'\n"
                        + "  else\n"
                        + "    echo 'c-imm-1|running|0|" + wsLabel + "|att-1'\n"
                        + "  fi\n"
                        + "  exit 0\n"
                        + "elif [ \"$1\" = \"stop\" ]; then\n"
                        + "  echo \"$4\" > '" + stoppedTargetFile.toString() + "'\n"
                        + "  touch '" + stateFile.toString() + "'\n"
                        + "  exit 0\n"
                        + "fi\n"
                        + "exit 0\n");

        boolean terminated = PodmanAttemptContainerSupport.terminateContainer(
                fakePodman.toString(), "cnt-name", "ws-1", "att-1", "c-imm-1");

        assertTrue(terminated);
        assertTrue(stateFile.toFile().exists());
        assertEquals("c-imm-1", Files.readString(stoppedTargetFile).trim());
    }

    @Test
    void inspectRecoveryEvidencePrefixContainerIdReturnsOwnershipMismatch() throws IOException {
        String wsLabel = encode("ws-1");
        Path fakePodman = FakePodmanHelper.createFakePodman(
                tempDir,
                "podman-prefix-cid",
                "if [ \"$1\" = \"inspect\" ]; then\n"
                        + "  echo 'c-imm-12345678|running|0|" + wsLabel + "|att-1'\n"
                        + "  exit 0\n"
                        + "fi\n"
                        + "exit 1\n");

        // expected is an abbreviated prefix "c-imm-123", actual is "c-imm-12345678" -> must reject
        SandboxRecoveryEvidence ev = PodmanAttemptContainerSupport.inspectRecoveryEvidence(
                fakePodman.toString(), "cnt-name", "ws-1", "att-1", "c-imm-123");

        assertTrue(ev instanceof SandboxRecoveryEvidence.OwnershipMismatch);
    }

    @Test
    void terminateContainerLegacyUnlabeledContainerLeftUntouched() throws IOException {
        Path stateFile = tempDir.resolve("unexpected_stop.flag");
        Path fakePodman = FakePodmanHelper.createFakePodman(
                tempDir,
                "podman-legacy-untouched",
                "if [ \"$1\" = \"inspect\" ]; then\n"
                        + "  echo 'legacy-zc-1|running|0||'\n"
                        + "  exit 0\n"
                        + "elif [ \"$1\" = \"stop\" ] || [ \"$1\" = \"kill\" ]; then\n"
                        + "  touch '" + stateFile.toString() + "'\n"
                        + "  exit 0\n"
                        + "fi\n"
                        + "exit 0\n");

        boolean terminated = PodmanAttemptContainerSupport.terminateContainer(
                fakePodman.toString(), "zc-legacy", "ws-1", "att-1", "legacy-zc-1");

        assertFalse(terminated);
        assertFalse(stateFile.toFile().exists(), "Legacy unlabeled container must never be stopped or killed");
    }
}
