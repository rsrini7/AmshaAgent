package com.arcclaw.core.structured;

import java.util.List;
import io.quarkus.qute.TemplateData;

/**
 * Summarizes conflicts between different agent findings.
 */
@TemplateData
public record ConflictSummary(
    String description,
    List<String> conflictingRoles
) {}
