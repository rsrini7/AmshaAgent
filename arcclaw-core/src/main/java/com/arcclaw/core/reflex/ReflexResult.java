package com.arcclaw.core.reflex;

import java.util.Optional;

public record ReflexResult(
    Optional<String> commandResponse,
    String sanitizedInput
) {}
