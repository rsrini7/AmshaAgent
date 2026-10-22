# Contributing to Amsha

Thanks for considering a contribution. This guide covers the usual path for a bug fix, documentation change, or feature. The deeper engineering rules are in [AGENTS.md](./AGENTS.md) and [Architecture Invariants](./docs/Architecture_Invariants.md).

## Before starting

- For a small, well-scoped fix, you can open a pull request directly.
- For a new feature, public API change, persistence change, or architectural change, open an issue or discussion first and describe the intended behavior. This helps agree on the design before implementation.
- Check existing issues and documentation so the change fits the current module boundaries and behavior.

## Set up

- Use JDK 25. The repository includes the Maven wrapper, so use `./mvnw` rather than installing a separate Maven version.
- See [Development Environment Setup](./docs/MISE_SETUP.md) for mise tasks and [Setup](./docs/SETUP.md) for prerequisites.
- Keep credentials and local configuration out of Git. Put placeholder values in `.env.example`; never commit `.env` files, tokens, or real user data.

## Make the change

- Keep each pull request focused and include tests for behavior changes and bug fixes.
- Preserve the module boundaries, security checks, approval flow, and persistence contracts described in [Architecture Invariants](./docs/Architecture_Invariants.md).
- If you add a configuration property, document it in `application.yaml`, `.env.example`, and the relevant module README.
- Persistence changes must preserve SQLite, PostgreSQL, and filesystem behavior where those implementations exist, including matching migration semantics.
- Register new types in the owning module's reflection configuration when required for native builds. Follow the reflection rules in [AGENTS.md](./AGENTS.md).
- Update relevant user and operator documentation when behavior or configuration changes.

## Verify your changes

Run the checks that match the files you changed. Use JDK 25 and the Maven wrapper.

For Java changes:

```bash
python3 ./scripts/fix_fqns.py
./mvnw spotless:apply -q
./mvnw -pl <module> -am test
./mvnw -pl <module> -am pmd:check -DskipTests
```

Replace `<module>` with the affected Maven module. For a focused test class, add `-Dtest=ClassNameTest -Dsurefire.failIfNoSpecifiedTests=false` to the test command. If you change a shared dependency module, also run:

```bash
./mvnw clean install -DskipTests
```

For Markdown changes:

```bash
python3 ./scripts/verify_docs.py
```

Before submitting, also run `git diff --check` and review the complete diff. See [TESTING_GUIDE.md](./docs/TESTING_GUIDE.md) for broader test workflows. If a check cannot run in your environment, include the reason in the pull request.

## Open a pull request

Include:

- The problem being solved and the user-visible effect.
- A concise summary of the implementation.
- The exact verification commands you ran and their results.
- Related issue or design discussion, when applicable.
- Screenshots or a short recording for UI changes.
- Any configuration, migration, compatibility, or operational impact.

Do not include secrets, private user data, generated native binaries, or unrelated formatting changes. For suspected security vulnerabilities, do not publish exploit details in a public issue; use the repository host's private vulnerability-reporting feature if enabled, or contact the maintainers privately.
