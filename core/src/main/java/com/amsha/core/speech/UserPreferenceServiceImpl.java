package com.amsha.core.speech;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Locale;
import java.util.Optional;

import com.amsha.channel.api.preference.UserPreferenceService;
import com.amsha.common.util.LogUtil;
import com.amsha.memory.tiered.TieredBackendLiteral;
import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.persistence.api.CoreMemoryStorePort;
import com.amsha.persistence.api.PersistenceBackend;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class UserPreferenceServiceImpl implements UserPreferenceService {

    private static final Logger log = Logger.getLogger(UserPreferenceServiceImpl.class);
    private static final String PREF_VOICE_KEY = "pref:voice";
    private static final String PREF_OUTPUT_MODE_KEY = "pref:speech_output_mode";

    private final Instance<CoreMemoryStorePort> coreMemoryStore;
    private final String configuredVoice;
    private final OutputMode configuredOutputMode;
    private final String persistenceBackend;

    @Inject
    public UserPreferenceServiceImpl(
            @Any Instance<CoreMemoryStorePort> coreMemoryStore,
            @ConfigProperty(name = "amsha.tts.voice") Optional<String> configuredVoice,
            @ConfigProperty(name = "amsha.tts.default-output-mode", defaultValue = "TEXT_ONLY")
                    OutputMode configuredOutputMode,
            @ConfigProperty(name = PersistenceBackend.PROPERTY, defaultValue = PersistenceBackend.SQLITE)
                    String persistenceBackend) {
        this.coreMemoryStore = coreMemoryStore;
        this.configuredVoice = configuredVoice.filter(v -> !v.isBlank()).orElse(null);
        this.configuredOutputMode = configuredOutputMode != null ? configuredOutputMode : OutputMode.TEXT_ONLY;
        this.persistenceBackend = persistenceBackend;
    }

    private CoreMemoryStorePort resolveStore() {
        if (coreMemoryStore == null || coreMemoryStore.isUnsatisfied()) {
            return null;
        }
        String backend = (persistenceBackend != null && !persistenceBackend.isBlank())
                ? persistenceBackend.trim().toLowerCase(Locale.ROOT)
                : "sqlite";
        Instance<CoreMemoryStorePort> backendSpecific = coreMemoryStore.select(new TieredBackendLiteral(backend));
        if (backendSpecific.isResolvable()) {
            return backendSpecific.get();
        }
        if (coreMemoryStore.isResolvable()) {
            return coreMemoryStore.get();
        }
        return coreMemoryStore.stream()
                .filter(s -> !s.getClass().getSimpleName().startsWith("NoOp"))
                .findFirst()
                .orElse(null);
    }

    private CoreMemoryStorePort requireStore() {
        CoreMemoryStorePort store = resolveStore();
        if (store == null) {
            throw new IllegalStateException("Persistence store is not available for user preferences");
        }
        return store;
    }

    @Override
    public Optional<String> getVoice(String userId) {
        CoreMemoryStorePort store = resolveStore();
        if (userId == null || userId.isBlank() || store == null) {
            return Optional.empty();
        }
        try {
            return store.get(userId.trim(), PREF_VOICE_KEY)
                    .map(CoreMemoryBlockDto::content)
                    .filter(v -> !v.isBlank());
        } catch (Exception e) {
            log.debugf("Failed to read user voice preference: %s", LogUtil.rootMessage(e));
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> getEffectiveVoice(String userId) {
        Optional<String> userVoice = getVoice(userId);
        if (userVoice.isPresent()) {
            return userVoice;
        }
        return Optional.ofNullable(configuredVoice);
    }

    @Override
    public void setVoice(String userId, String voice) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        String normalized = voice != null ? voice.trim().toLowerCase(Locale.ROOT) : "";
        if (normalized.isBlank() || "default".equals(normalized)) {
            clearVoice(userId);
            return;
        }
        if (!isValidVoice(normalized)) {
            throw new IllegalArgumentException(
                    "Unsupported voice: '" + voice + "'. Allowed voices: " + PREDEFINED_VOICES);
        }
        CoreMemoryStorePort store = requireStore();
        savePreference(store, userId.trim(), PREF_VOICE_KEY, normalized);
    }

    @Override
    public void clearVoice(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        CoreMemoryStorePort store = requireStore();
        try {
            store.delete(userId.trim(), PREF_VOICE_KEY);
        } catch (Exception e) {
            log.warnf("Failed to clear voice preference: %s", LogUtil.rootMessage(e));
            throw new IllegalStateException("Failed to clear voice preference: " + LogUtil.rootMessage(e), e);
        }
    }

    @Override
    public Optional<String> getOutputMode(String userId) {
        CoreMemoryStorePort store = resolveStore();
        if (userId == null || userId.isBlank() || store == null) {
            return Optional.empty();
        }
        try {
            return store.get(userId.trim(), PREF_OUTPUT_MODE_KEY)
                    .map(CoreMemoryBlockDto::content)
                    .filter(v -> !v.isBlank());
        } catch (Exception e) {
            log.debugf("Failed to read output mode preference: %s", LogUtil.rootMessage(e));
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> getEffectiveOutputMode(String userId) {
        Optional<String> userMode = getOutputMode(userId);
        if (userMode.isPresent()) {
            return userMode;
        }
        return Optional.of(configuredOutputMode.name());
    }

    @Override
    public void setOutputMode(String userId, String mode) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        String normalized = mode != null ? mode.trim().toUpperCase(Locale.ROOT) : "";
        if (normalized.isBlank() || "DEFAULT".equals(normalized)) {
            clearOutputMode(userId);
            return;
        }
        if (!isValidOutputMode(normalized)) {
            throw new IllegalArgumentException("Unsupported output mode: '" + mode
                    + "'. Allowed modes: TEXT_ONLY, TEXT_AND_AUDIO, AUDIO_ONLY, default");
        }
        CoreMemoryStorePort store = requireStore();
        savePreference(store, userId.trim(), PREF_OUTPUT_MODE_KEY, normalized);
    }

    @Override
    public void clearOutputMode(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        CoreMemoryStorePort store = requireStore();
        try {
            store.delete(userId.trim(), PREF_OUTPUT_MODE_KEY);
        } catch (Exception e) {
            log.warnf("Failed to clear output mode preference: %s", LogUtil.rootMessage(e));
            throw new IllegalStateException("Failed to clear output mode preference: " + LogUtil.rootMessage(e), e);
        }
    }

    @Override
    public void updatePreferences(String userId, String voice, String outputMode) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        // Phase 1: Validate all inputs BEFORE making any state mutation
        String normalizedVoice = null;
        boolean clearVoiceFlag = false;
        if (voice != null) {
            String v = voice.trim().toLowerCase(Locale.ROOT);
            if (v.isBlank() || "default".equals(v)) {
                clearVoiceFlag = true;
            } else if (!isValidVoice(v)) {
                throw new IllegalArgumentException(
                        "Unsupported voice: '" + voice + "'. Allowed voices: " + PREDEFINED_VOICES);
            } else {
                normalizedVoice = v;
            }
        }

        String normalizedMode = null;
        boolean clearModeFlag = false;
        if (outputMode != null) {
            String m = outputMode.trim().toUpperCase(Locale.ROOT);
            if (m.isBlank() || "DEFAULT".equals(m)) {
                clearModeFlag = true;
            } else if (!isValidOutputMode(m)) {
                throw new IllegalArgumentException("Unsupported output mode: '" + outputMode
                        + "'. Allowed modes: TEXT_ONLY, TEXT_AND_AUDIO, AUDIO_ONLY, default");
            } else {
                normalizedMode = m;
            }
        }

        // Phase 2: Apply mutations now that both inputs are verified
        if (clearVoiceFlag) {
            clearVoice(userId);
        } else if (normalizedVoice != null) {
            setVoice(userId, normalizedVoice);
        }

        if (clearModeFlag) {
            clearOutputMode(userId);
        } else if (normalizedMode != null) {
            setOutputMode(userId, normalizedMode);
        }
    }

    private void savePreference(CoreMemoryStorePort store, String userId, String key, String value) {
        try {
            int currentVersion =
                    store.get(userId, key).map(CoreMemoryBlockDto::version).orElse(0);
            store.upsert(userId, key, value, currentVersion);
        } catch (Exception e) {
            log.warnf("Failed to save preference %s: %s", key, LogUtil.rootMessage(e));
            throw new IllegalStateException("Failed to save user preference " + key + ": " + LogUtil.rootMessage(e), e);
        }
    }
}
