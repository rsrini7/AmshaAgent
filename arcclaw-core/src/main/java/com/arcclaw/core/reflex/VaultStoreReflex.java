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
import com.arcclaw.core.security.VaultKey;
import com.arcclaw.common.util.LogUtil;
import org.jboss.logging.Logger;

/**
 * High-priority reflex that intercepts "vault KEY=VALUE" reflexs for known vault keys.
 * This prevents secrets from being sent to the LLM and stored in the RAG knowledge base.
 */
@ApplicationScoped
@ReflexPriority(400) // Before StoreReflex(500)
public class VaultStoreReflex implements AgentReflex {

    private static final Logger log = Logger.getLogger(VaultStoreReflex.class);

    // Matches reflexs like "vault ARCCLAW_TOOLS_BRAVE_API_KEY=sk-xxxx" or "vault ... --force"
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
            return ReflexResult.respond("🔐 *Usage Guide: vault*\n\n" +
                    "Use this to securely store API keys and sensitive tokens. They will be encrypted and never shown to the AI.\n" +
                    "`vault KEY=VALUE` (e.g., `vault GITHUB_TOKEN=...`)\n\n" +
                    "To save general notes or documents for the AI, use `store` instead.", context);
        }

        Matcher m = VAULT_STORE_PATTERN.matcher(trimmed);
        if (!m.find()) {
            return new ReflexResult(Optional.empty(), input);
        }

        String keyName = m.group(1).toUpperCase();
        String value = m.group(2).trim();
        boolean force = m.group(3) != null;

        if (!VaultConstants.KNOWN_TOOL_SECRET_NAMES.contains(keyName)) {
            String knownKeys = String.join(", ", VaultConstants.KNOWN_TOOL_SECRET_NAMES);
            return ReflexResult.respond("⚠️ Unknown vault key: `" + keyName + "`.\n\n" +
                    "Valid system keys are: " + knownKeys + "\n\n" +
                    "Check for typos (e.g., use `ARCCLAW_TOOLS_BRAVE_API_KEY` instead of `ARCCLAW_BRAVE_API_KEY`).", context);
        }

        log.infof("VaultStoreReflex: intercepting secret store for '%s' (session=%s, force=%s)", keyName, sessionId, force);

        // Overwrite protection
        Optional<VaultKey> existing = secretVault.retrieveSystemKey(keyName);
        if (existing.isPresent()) {
            try (var _ = existing.get()) {
                if (!force) {
                    return ReflexResult.respond("⚠️ Secret `" + keyName + "` already exists in vault.\n" +
                            "To overwrite it, append `--force` to your reflex:\n" +
                            "`vault " + keyName + "=" + value + " --force`", context);
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
            log.errorf("VaultStoreReflex: failed to store '%s': %s", keyName, LogUtil.rootMessage(e));
            return ReflexResult.respond("⚠️ Failed to store secret `" + keyName + "`: " + e.getMessage(), context);
        } finally {
            Arrays.fill(secretBytes, (byte) 0);
        }
    }
}
