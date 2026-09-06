package com.amsha.core.security;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

import io.quarkus.arc.Unremovable;

/**
 * Default fail-closed implementation of {@link CredentialDetector}.
 *
 * <p>Scans text for provider key literals, authorization headers, bearer tokens,
 * JWTs, cloud storage credentials, passwords, private keys, and API credentials
 * before provider egress.
 *
 * <p>Note: This detector provides best-effort, fail-closed defense-in-depth secret
 * rejection for operator-facing prompt surfaces.
 */
@ApplicationScoped
@Unremovable
public class DefaultCredentialDetector implements CredentialDetector {

    private static final int MAX_SCAN_LENGTH = 65536;

    private record DetectionRule(Pattern pattern, String category) {}

    private static final List<DetectionRule> RULES = List.of(
            new DetectionRule(
                    Pattern.compile(
                            "\\b(?:sk|ghp|gho|ghu|ghs|ghr|xoxb|xoxp|xoxs|github_pat|glpat|xapp|rk_live)[-_][A-Za-z0-9_-]{8,}\\b"
                                    + "|\\b(?:npm_|pypi-|hf_)[A-Za-z0-9_-]{8,}\\b"
                                    + "|\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"
                                    + "|\\bAIza[0-9A-Za-z_-]{20,}\\b"),
                    "PROVIDER_KEY"),
            new DetectionRule(Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9\\-._~+/]{10,}=*"), "BEARER_TOKEN"),
            new DetectionRule(
                    Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?:\\.[A-Za-z0-9_\\-+/=]+)?\\b"),
                    "JWT_TOKEN"),
            new DetectionRule(Pattern.compile("(?i)https?://[^/\\s:@]+:[^/\\s:@]{4,}@[^/\\s:]+"), "BASIC_AUTH_URL"),
            new DetectionRule(
                    Pattern.compile(
                            "(?i)\\bauthorization\\s*[:=]\\s*(?:Bearer\\s+[A-Za-z0-9\\-._~+/]+=*|Basic\\s+[A-Za-z0-9+/=]{8,}|[\"']?[A-Za-z0-9\\-_.~+/]{8,})"),
                    "AUTHORIZATION_HEADER"),
            new DetectionRule(
                    Pattern.compile(
                            "(?i)\\b(?:x-amsha-[\\w-]+|x-api-key|x-auth-token)\\s*[:=]\\s*[\"']?[A-Za-z0-9\\-_.~+/]{8,}"),
                    "API_KEY_HEADER"),
            new DetectionRule(
                    Pattern.compile("(?i)(?:AccountKey|SharedAccessKey)\\s*=\\s*[A-Za-z0-9+/]{20,}={0,2}"),
                    "CLOUD_CREDENTIAL"),
            new DetectionRule(
                    Pattern.compile(
                            "(?i)(?<![A-Za-z0-9_.-])(?:audit[_-]?key|hmac[_-]?key|signing[_-]?key)\\s*[:=]\\s*[\"']?[A-Za-z0-9-_.~+/]{8,}"),
                    "SIGNING_KEY"),
            new DetectionRule(
                    Pattern.compile(
                            "(?i)(?<![A-Za-z0-9_.-])(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|private[_-]?key)\\s*[:=]\\s*[\"']?[A-Za-z0-9-_.~+/]{8,}"),
                    "PASSWORD_OR_SECRET"),
            new DetectionRule(
                    Pattern.compile("(?i)(?<![A-Za-z0-9_.-])(?:password|passwd)\\s*[:=]\\s*[\"']?[^\\s\"']{6,}"),
                    "PASSWORD_OR_SECRET"),
            new DetectionRule(
                    Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY(?: BLOCK)?-----"),
                    "PRIVATE_KEY_BLOCK"));

    @Override
    public CredentialDetectionResult detect(String text) {
        if (text == null || text.isBlank()) {
            return CredentialDetectionResult.CLEAN;
        }
        try {
            String scanTarget = text.length() > MAX_SCAN_LENGTH ? text.substring(0, MAX_SCAN_LENGTH) : text;
            for (DetectionRule rule : RULES) {
                if (rule.pattern().matcher(scanTarget).find()) {
                    return CredentialDetectionResult.of(rule.category());
                }
            }
            return CredentialDetectionResult.CLEAN;
        } catch (Throwable _) {
            return CredentialDetectionResult.of("DETECTION_ERROR");
        }
    }
}
