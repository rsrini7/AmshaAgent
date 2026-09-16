# LLM Provider Configuration Migration

- **Status**: In progress

## Observation

The current configuration model uses provider-specific environment variables:

- `AMSHA_GROQ_API_KEY`
- `AMSHA_OPENAI_API_KEY`
- `AMSHA_OPENROUTER_API_KEY`
- `AMSHA_GEMINI_API_KEY`
- `AMSHA_NVIDIA_API_KEY`
- `AMSHA_CEREBRAS_API_KEY`
- `AMSHA_GENERIC_OPENAI_API_KEY`
- `AMSHA_OMLX_API_KEY`

`AMSHA_LLM_API_KEY` is not present in the current `.env.example` or native
`dist/.env`. The selected provider's canonical key is used by onboarding and
the reasoning-provider factories. A legacy placeholder check, some routing
factory error messages, Docker configuration, startup validation, and test
fixtures still mention the old variable; those references are migration debt,
not a supported replacement for `AMSHA_{PROVIDER}_API_KEY`.

The variable is therefore a legacy compatibility path, not an alias that is
automatically derived from provider-specific keys. Exporting it alone does not
prove that the selected provider can route requests; deployments must migrate
to the canonical provider-specific key before relying on automatic skip.

The internal vault key `VaultConstants.LLM_API_KEY` is different: it is the
stable vault slot used by onboarding and vault-priority runtime routing. It
must not be removed as part of removing the legacy environment variable.

## Follow-up

Refactor the remaining exact `AMSHA_LLM_API_KEY` references to use one
provider-aware resolver. The resolver should:

1. Resolve the selected provider's canonical API-key environment variable.
2. Treat keyless providers and infinite-loop slot configuration explicitly.
3. Respect vault-priority semantics: vault configuration wins and environment
   configuration should not suppress the wizard when vault priority is enabled.
4. Update startup validation, onboarding/settings status, Docker, REST examples,
   tests, and documentation together.
5. Add regression coverage proving provider-specific keys configure runtime and
   the legacy variable is no longer required or recognized.

Until that refactor is complete, do not add `AMSHA_LLM_API_KEY` to a new
`.env`; use the provider-specific variable and, for UI onboarding, enable
`AMSHA_LLM_VAULT_PRIORITY=true`.
