# Sarvam GraalPy evidence cache

The private SGP-01 artifact cache is deliberately separate from Amsha runtime
smoke data:

```text
.scratch/sarvam-graalpy-phase-0/cache/
```

`.scratch/` is gitignored and local-only. The cache contains only downloaded
archive bytes for static metadata, licence/provenance, SBOM, hash, and
vulnerability evidence. It is not a runtime package repository and must never
be copied into an Amsha distribution or shared CI artifact.

Run the authorization-pinned capture from the repository root:

```text
mise run sarvam:freeze
```

The current private-PoC scope permits only `sarvamai==0.1.30`. A different
version requires an updated human authorization before changing the helper's
pin or collecting artifacts.
