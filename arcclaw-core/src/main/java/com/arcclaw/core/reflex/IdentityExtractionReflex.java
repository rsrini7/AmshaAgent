package com.arcclaw.core.reflex;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.common.util.LogUtil;
import com.arcclaw.persistence.api.MemoryEntryDto;
import com.arcclaw.persistence.api.MemoryRetrievalPort;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

@ApplicationScoped
@ReflexPriority(250)
public class IdentityExtractionReflex implements AgentReflex {

    private static final Logger log = Logger.getLogger(IdentityExtractionReflex.class);

    @Inject MemoryRetrievalPort memoryPort;
    
    @Inject ManagedExecutor managedExecutor;

    private static final Pattern NAME_PATTERN = Pattern.compile("(?i)\\b(?:my name is|i am|i'm)\\s+([a-zA-Z]+)\\b");
    private static final Pattern ALIAS_PATTERN = Pattern.compile("(?i)\\b(?:call me)\\s+([a-zA-Z]+)\\b");
    private static final Pattern HOBBY_PATTERN = Pattern.compile("(?i)\\b(?:my hobby is|i enjoy doing)\\s+(.+)");
    private static final Pattern PREF_PATTERN = Pattern.compile("(?i)\\b(?:i prefer)\\s+(.+)");
    private static final Pattern LIKE_PATTERN = Pattern.compile("(?i)\\b(?:i like|i love|i enjoy)\\s+(.+)");
    private static final Pattern EMPLOYER_PATTERN = Pattern.compile("(?i)\\b(?:i work at|i work for)\\s+(.+)");
    private static final Pattern LOCATION_PATTERN = Pattern.compile("(?i)\\b(?:i am based in|i live in|i'm based in)\\s+(.+)");
    private static final Pattern ROLE_PATTERN = Pattern.compile("(?i)\\bi (?:teach|study|tutor|mentor|coach|am a|work as)\\s+(.+)");

    @Override
    public boolean matches(String input) {
        if (input == null || input.isBlank()) return false;
        String trimmed = input.trim();
        
        // 1. Guard: If it ends with a question mark, it's a query, not a fact to ingest.
        if (trimmed.endsWith("?")) return false;

        String lower = trimmed.toLowerCase();
        
        // 2. Guard: Common question starters
        if (lower.startsWith("what ") || lower.startsWith("who ") || lower.startsWith("how ") || 
            lower.startsWith("do you ") || lower.startsWith("can you ")) {
            return false;
        }

        return NAME_PATTERN.matcher(lower).find() ||
               ALIAS_PATTERN.matcher(lower).find() ||
               HOBBY_PATTERN.matcher(lower).find() ||
               PREF_PATTERN.matcher(lower).find() ||
               LIKE_PATTERN.matcher(lower).find() ||
               EMPLOYER_PATTERN.matcher(lower).find() ||
               LOCATION_PATTERN.matcher(lower).find() ||
               ROLE_PATTERN.matcher(lower).find();
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        // §Bug-4 Fix: Prevent label pollution by splitting compound sentences.
        String[] clauses = input.split("(?i)\\b(and|but)\\b|[,\\.]");
        boolean prevWasFirstPerson = false;
        
        for (String rawClause : clauses) {
            String clause = rawClause.trim();
            if (clause.isBlank()) continue;
            
            // Re-inject dropped pronouns for compound verbs (e.g. "i am Srini and love to study")
            String lower = clause.toLowerCase();
            boolean isFirstPerson = lower.startsWith("i ") || lower.startsWith("i'm") || lower.startsWith("my ") || lower.contains(" i ") || lower.contains(" i'm");
            
            if (!isFirstPerson && prevWasFirstPerson) {
                clause = "i " + clause;
            }
            
            prevWasFirstPerson = isFirstPerson || (!isFirstPerson && prevWasFirstPerson);

            extractAndIngest(clause, NAME_PATTERN, IdentityLabel.USER_NAME);
            extractAndIngest(clause, ALIAS_PATTERN, IdentityLabel.USER_ALIAS);
            extractAndIngest(clause, HOBBY_PATTERN, IdentityLabel.USER_HOBBY);
            extractAndIngest(clause, PREF_PATTERN, IdentityLabel.USER_PREF);
            extractAndIngest(clause, LIKE_PATTERN, IdentityLabel.USER_LIKE);
            extractAndIngest(clause, EMPLOYER_PATTERN, IdentityLabel.USER_EMPLOYER);
            extractAndIngest(clause, LOCATION_PATTERN, IdentityLabel.USER_LOCATION);
            extractAndIngest(clause, ROLE_PATTERN, IdentityLabel.USER_ROLE);
        }

        // Forward to the LLM without blocking
        return ReflexResult.forward(context);
    }

    private void extractAndIngest(String input, Pattern pattern, IdentityLabel label) {
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            String value = matcher.group(1).trim();
            String fullText = buildFactText(label, value);
            
            MemoryEntryDto dto = new MemoryEntryDto(
                null, 
                fullText, 
                Map.of("label", label.name().toLowerCase())
            );
            
            managedExecutor.submit(() -> {
                try {
                    memoryPort.index(dto);
                    log.infof("IdentityExtractionReflex ingested fact silently: label=%s, fact='%s'", label, fullText);
                } catch (Exception e) {
                    log.warnf("IdentityExtractionReflex failed to ingest fact: %s", LogUtil.rootMessage(e));
                }
            });
        }
    }
    
    private String buildFactText(IdentityLabel label, String value) {
        return switch (label) {
            case USER_NAME -> "The user's name is " + value + ".";
            case USER_ALIAS -> "The user prefers to be called " + value + ".";
            case USER_HOBBY -> "The user's hobby is " + value + ".";
            case USER_PREF -> "The user prefers " + value + ".";
            case USER_LIKE -> "The user enjoys " + value + ".";
            case USER_EMPLOYER -> "The user works at " + value + ".";
            case USER_LOCATION -> "The user is based in " + value + ".";
            case USER_ROLE -> "The user's role involves " + value + ".";
            default -> "User identity fact: " + value;
        };
    }
}
