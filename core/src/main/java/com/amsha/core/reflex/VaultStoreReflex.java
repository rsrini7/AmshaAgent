package com.amsha.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amsha.common.util.LogUtil;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;

import org.jboss.logging.Logger;

/**
 * High-priority reflex that intercepts "vault KEY=VALUE" reflexs for known vault keys.
 * This prevents secrets from being sent to the LLM and stored in the RAG knowledge base.
 */
@ApplicationScoped
@ReflexPriority(400) // Before StoreReflex(500)
public class VaultStoreReflex implements AgentReflex {

    private static final Logger log = Logger.getLogger(VaultStoreReflex.class);

    // Matches reflexs like "vault AMSHA_TOOLS_BRAVE_API_KEY=sk-xxxx" or "vault ... --force"
    // Supports various dash types (-, --, -) to handle platform auto-formatting.
    private static final Pattern VAULT_STORE_PATTERN =
            Pattern.compile("^vault\\s+(\\w+)\\s*=\\s*(.+?)(?:\\s+([--]{1,2}force))?$", Pattern.CASE_INSENSITIVE);

    @Inject
    SecretVault secretVault;

    @Inject
    AuditService auditService;

    @Override
    public boolean matches(String input) {
        if (input == null) return false;
        String trimmed = input.trim();
        if (trimmed.equalsIgnoreCase("vault") || trimmed.equalsIgnoreCase("/vault")) {
            return true;
        }
        return VAULT_STORE_PATTERN.matcher(trimmed).find();
    }

    @Override
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    @ActivateRequestContext
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        String trimmed = input.trim();
        if (trimmed.equalsIgnoreCase("vault") || trimmed.equalsIgnoreCase("/vault")) {
            return ReflexResult.respond("""
                    🔐 *Usage Guide: vault*

                    Use this to securely store API keys and sensitive tokens. They will be encrypted and never shown to the AI.
                    `vault KEY=VALUE` (e.g., `vault GITHUB_TOKEN=...`)

                    To save general notes or documents for the AI, use `store` instead.""", context);
        }

        Matcher m = VAULT_STORE_PATTERN.matcher(trimmed);
        if (!m.find()) {
            return new ReflexResult(Optional.empty(), input);
        }

        String keyName = m.group(1).toUpperCase(Locale.ROOT);
        String value = m.group(2).trim();
        boolean force = m.group(3) != null;

        if (!VaultConstants.KNOWN_TOOL_SECRET_NAMES.contains(keyName)) {
            String knownKeys = String.join(", ", VaultConstants.KNOWN_TOOL_SECRET_NAMES);
            return ReflexResult.respond(
                    "⚠️ Unknown vault key: `" + keyName + "`.\n\n" + "Valid system keys are: "
                            + knownKeys + "\n\n"
                            + "Check for typos (e.g., use `AMSHA_TOOLS_BRAVE_API_KEY` instead of `AMSHA_BRAVE_API_KEY`).",
                    context);
        }

        log.infof(
                "VaultStoreReflex: intercepting secret store for '%s' (session=%s, force=%s)",
                keyName, sessionId, force);

        // Overwrite protection
        Optional<VaultKey> existing = secretVault.retrieveSystemKey(keyName);
        if (existing.isPresent()) {
            try (var _ = existing.get()) {
                if (!force) {
                    return ReflexResult.respond(
                            "⚠️ Secret `" + keyName + "` already exists in vault.\n"
                                    + "To overwrite it, append `--force` to your reflex:\n"
                                    + "`vault "
                                    + keyName + "=" + value + " --force`",
                            context);
                }
            }
        }

        byte[] secretBytes = value.getBytes(StandardCharsets.UTF_8);
        try {
            // Store as system key since tool configuration is global
            secretVault.storeSystemKey(keyName, secretBytes);

            auditService.record(
                    sessionId,
                    userId,
                    "VaultStoreReflex",
                    SecurityEventType.VAULT_STORE,
                    "keyName=" + keyName + " force=" + force);

            return ReflexResult.respond("🔐 Secret `" + keyName + "` stored in vault.", context);
        } catch (Exception e) {
            log.errorf("VaultStoreReflex: failed to store '%s': %s", keyName, LogUtil.rootMessage(e));
            return ReflexResult.respond("⚠️ Failed to store secret `" + keyName + "`: " + e.getMessage(), context);
        } finally {
            Arrays.fill(secretBytes, (byte) 0);
        }
    }
}
