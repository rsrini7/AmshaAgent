package com.amsha.security.api;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * GraalVM Native Image reflection configuration for amsha-security-api.
 * Vocabulary types moved here from core (extract-core-subsystems S4); the
 * {@code OutboundPolicyDecision}/{@code OutboundPurpose} entries were relocated
 * from CoreReflectionConfig.
 */
@RegisterForReflection(
        targets = {
            SecretVault.class,
            VaultKey.class,
            VaultConstants.class,
            CryptoConstants.class,
            RequiresSecret.class,
            RequiresSecrets.class,
            ToolOperation.class,
            OutboundPurpose.class,
            OutboundPolicyDecision.class,
            AutonomyLevel.class,
            RiskLevel.class,
            DownloadedMedia.class,
            MediaDownloadPort.class,
            MediaBudgetLimits.class
        })
public class SecurityApiReflectionConfig {}
