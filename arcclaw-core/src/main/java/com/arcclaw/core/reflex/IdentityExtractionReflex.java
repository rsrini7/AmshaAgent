package com.arcclaw.core.reflex;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.pipeline.MessageContext;
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

    private static final Pattern NAME_PATTERN = Pattern.compile("(?i)(?:my name is|i am|i'm)\\s+([a-zA-Z\\s]+)");
    private static final Pattern ALIAS_PATTERN = Pattern.compile("(?i)(?:call me)\\s+([a-zA-Z\\s]+)");
    private static final Pattern HOBBY_PATTERN = Pattern.compile("(?i)(?:i love|i enjoy|my hobby is)\\s+(.+)");
    private static final Pattern PREF_PATTERN = Pattern.compile("(?i)(?:i prefer)\\s+(.+)");
    private static final Pattern EMPLOYER_PATTERN = Pattern.compile("(?i)(?:i work at|i work for)\\s+(.+)");
    private static final Pattern LOCATION_PATTERN = Pattern.compile("(?i)(?:i am based in|i live in|i'm based in)\\s+(.+)");

    @Override
    public boolean matches(String input) {
        if (input == null) return false;
        String lower = input.toLowerCase();
        return NAME_PATTERN.matcher(lower).find() ||
               ALIAS_PATTERN.matcher(lower).find() ||
               HOBBY_PATTERN.matcher(lower).find() ||
               PREF_PATTERN.matcher(lower).find() ||
               EMPLOYER_PATTERN.matcher(lower).find() ||
               LOCATION_PATTERN.matcher(lower).find();
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        extractAndIngest(input, NAME_PATTERN, IdentityLabel.USER_NAME);
        extractAndIngest(input, ALIAS_PATTERN, IdentityLabel.USER_ALIAS);
        extractAndIngest(input, HOBBY_PATTERN, IdentityLabel.USER_HOBBY);
        extractAndIngest(input, PREF_PATTERN, IdentityLabel.USER_PREF);
        extractAndIngest(input, EMPLOYER_PATTERN, IdentityLabel.USER_EMPLOYER);
        extractAndIngest(input, LOCATION_PATTERN, IdentityLabel.USER_LOCATION);

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
                    log.warnf("IdentityExtractionReflex failed to ingest fact: %s", e.getMessage());
                }
            });
        }
    }
    
    private String buildFactText(IdentityLabel label, String value) {
        return switch (label) {
            case USER_NAME -> "The user's name is " + value + ".";
            case USER_ALIAS -> "The user prefers to be called " + value + ".";
            case USER_HOBBY -> "The user loves " + value + ".";
            case USER_PREF -> "The user prefers " + value + ".";
            case USER_EMPLOYER -> "The user works at " + value + ".";
            case USER_LOCATION -> "The user is based in " + value + ".";
            default -> "User identity fact: " + value;
        };
    }
}