package com.arcclaw.core.reflex;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultConstants;
import org.jboss.logging.Logger;

/**
 * High-priority reflex that intercepts "store KEY=VALUE" commands for known vault keys.
 * This prevents secrets from being sent to the LLM and stored in the RAG knowledge base.
 */
@ApplicationScoped
@ReflexPriority(400) // Before StoreReflex(500)
public class VaultStoreReflex implements AgentReflex {

    private static final Logger log = Logger.getLogger(VaultStoreReflex.class);

    // Matches commands like "store ARCCLAW_TOOLS_BRAVE_API_KEY=sk-xxxx" or "store ... --force"
    // Supports various dash types (-, --, —) to handle platform auto-formatting.
    private static final Pattern VAULT_STORE_PATTERN =
            Pattern.compile("^store\\s+(\\w+)\\s*=\\s*(.+?)(?:\\s+([-—]{1,2}force))?$", Pattern.CASE_INSENSITIVE);

    @Inject
    SecretVault secretVault;

    @Inject
    AuditService auditService;

    @Override
    public boolean matches(String input) {
        if (input == null) return false;
        String trimmed = input.trim();
        if (trimmed.equalsIgnoreCase("store") || trimmed.equalsIgnoreCase("/store")) {
            return true;
        }
        Matcher m = VAULT_STORE_PATTERN.matcher(trimmed);
        if (m.find()) {
            String keyName = m.group(1).toUpperCase();
            return VaultConstants.KNOWN_TOOL_SECRET_NAMES.contains(keyName);
        }
        return false;
    }

    @Override
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    @ActivateRequestContext
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        String trimmed = input.trim();
        if (trimmed.equalsIgnoreCase("store") || trimmed.equalsIgnoreCase("/store")) {
            return ReflexResult.respond("📝 *Usage Guide: store*\n\n" +
                    "**1. Secure Secrets (Vault)**\n" +
                    "Use this for API keys and sensitive tokens. They will be encrypted and never shown to the AI.\n" +
                    "`store KEY=VALUE` (e.g., `store GITHUB_TOKEN=...`)\n\n" +
                    "**2. General Knowledge (RAG)**\n" +
                    "Use this to save facts or documents for later research.\n" +
                    "`store my favorite color is blue`", context);
        }

        Matcher m = VAULT_STORE_PATTERN.matcher(trimmed);
        if (!m.find()) {
            return new ReflexResult(Optional.empty(), input);
        }

        String keyName = m.group(1).toUpperCase();
        String value = m.group(2).trim();
        boolean force = m.group(3) != null;

        log.infof("VaultStoreReflex: intercepting secret store for '%s' (session=%s, force=%s)", keyName, sessionId, force);

        // Overwrite protection
        Optional<com.arcclaw.core.security.VaultKey> existing = secretVault.retrieveSystemKey(keyName);
        if (existing.isPresent()) {
            try (var _ = existing.get()) {
                if (!force) {
                    return ReflexResult.respond("⚠️ Secret `" + keyName + "` already exists in vault.\n" +
                            "To overwrite it, append `--force` to your command:\n" +
                            "`store " + keyName + "=" + value + " --force`", context);
                }
            }
        }

        byte[] secretBytes = value.getBytes(StandardCharsets.UTF_8);
        try {
            // Store as system key since tool configuration is global
            secretVault.storeSystemKey(keyName, secretBytes);
            
            auditService.record(sessionId, userId, "VaultStoreReflex", 
                SecurityEventType.VAULT_STORE, "keyName=" + keyName + " force=" + force);

            return ReflexResult.respond("🔐 Secret `" + keyName + "` stored in vault.", context);
        } catch (Exception e) {
            log.errorf("VaultStoreReflex: failed to store '%s': %s", keyName, e.getMessage());
            return ReflexResult.respond("⚠️ Failed to store secret `" + keyName + "`: " + e.getMessage(), context);
        } finally {
            Arrays.fill(secretBytes, (byte) 0);
        }
    }
}
