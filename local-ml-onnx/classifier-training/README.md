# Phase 2 classifier preparation and resumption runbook

This directory is the reproducible preparation path for three independent,
disabled-by-default shadow classifiers:

- intent routing;
- semantic tool risk;
- answer quality.

The shared base is `sentence-transformers/all-MiniLM-L6-v2`, pinned to the immutable
revision in `base-model-lock.json` and licensed Apache-2.0. Each task receives its own
classification head, dataset, calibration, evaluation, model card, error budget, and
rollout decision. Producing one passing model never approves either of the other two.

## Current pause point

The preparation toolchain is ready, but the repository seed packets are intentionally
not release datasets:

| Task | Seed rows | Current review state |
| --- | ---: | --- |
| Intent | 18 | Pending and undersized |
| Tool risk | 12 | Pending and undersized |
| Answer quality | 11 | Pending and undersized |

`error-budgets.proposed.json` also remains
`PROPOSED_REQUIRES_HUMAN_APPROVAL`. No task-specific model is approved, packaged for
release, selected in configuration, or enabled. This data/evaluation lane can remain
paused while later phase implementation continues. It remains a hard gate for
classifier promotion and any Phase 3 behavioral activation.

## One supported entry path

Run classifier operations from the repository root through `mise`:

```text
mise run classifier:<task>
  -> .mise/tasks/classifier/<task>.sh
  -> scripts/classifier_training.sh
  -> classifier-training Python implementation
```

The `mise` files are task-discovery shims. `scripts/classifier_training.sh` is the
single command router used by developers and CI; it applies the dataset gate before
loading the locked ML environment. Do not invoke the Python implementation directly
in normal workflows. The equivalent low-level form, useful when debugging the wrapper,
is `bash scripts/classifier_training.sh <command> ...`.

Available tasks:

| Mise task | Arguments | Purpose |
| --- | --- | --- |
| `classifier:validate-seeds` | none | Validate all three pending repository seed packets. |
| `classifier:prepare-review` | `<raw.jsonl> <packet.jsonl>` | De-identify common direct identifiers and credential shapes and reset every row to `pending`. |
| `classifier:export-review` | `<packet.jsonl> <review.csv>` | Export the fixed human-review CSV schema. |
| `classifier:import-review` | `<review.csv> <reviewed.jsonl>` | Import the completed review without hand-editing JSONL. |
| `classifier:validate` | `<dataset.jsonl> --task <task> [--release]` | Validate schema, labels, de-identification, duplicates, approvals, and release counts. |
| `classifier:install-base` | optional installer arguments | Install and SHA-256-verify the pinned MiniLM source. |
| `classifier:toolchain-check` | none | Offline-check model loading, the classifier head, ONNX export, and PyTorch/ORT parity. |
| `classifier:train` | `<task> <reviewed.jsonl> [options]` | Train one independently gated classifier. |
| `classifier:evaluate` | `<task> <reviewed.jsonl> <run-dir> [options]` | Calibrate on validation data and evaluate held-out data against an approved budget. |
| `classifier:package` | `<task> <reviewed.jsonl> <run-dir> [options]` | Export a passing development bundle under the verified ONNX model root. |
| `classifier:build-dataset` | `<task> <target.jsonl> <candidate.jsonl> [...]` | Merge, de-identify, deduplicate, and deterministically split candidates. |
| `classifier:collect` | `<task> <target.jsonl>` | Build a pending packet from ignored runtime test candidates. |
| `classifier:release-candidate` | `<task> <reviewed.jsonl> <approved-budgets.json>` | Run validation through evidence-bundle creation. |
| `classifier:promote` | `<evidence-dir> <human-approval.json> [model-root]` | Promote only an explicitly approved evidence bundle. |

The task identifier is exactly `intent`, `tool_risk`, or `answer_quality`.

Tool-risk inputs require the canonical `required_operation` value from `ToolOperation`; the trainer rejects missing or invalid values. The JSON renderer keeps runtime field order and recursively sorts object keys inside `parameters` while preserving array order. Seed rows remain pending human review and are not a release dataset.

## Automated release-candidate pipeline

The supported end-to-end path is:

```text
REST test fixtures / approved source data
  -> classifier:collect or classifier:build-dataset
  -> export-review / import-review
  -> classifier:release-candidate
  -> READY_FOR_HUMAN_REVIEW evidence bundle
  -> classifier:promote after explicit human approval
```

`classifier:collect` only creates sanitized, pending candidates. It never creates
approved ground truth. `classifier:release-candidate` validates an approved
dataset, installs/checks the pinned toolchain, trains, calibrates, evaluates the
held-out split against an approved error budget, packages the ONNX bundle, and
retains an evidence directory with hashes, metrics, metadata, and an approval
template. `classifier:promote` refuses to copy a bundle unless the approval file
matches the evidence digest and contains named human approval for the dataset,
model card, rollback criteria, and shadow evidence.

After review, copy `approval-template.json` to `approval.json`, fill the named
reviewer, approval timestamp, and all four approval booleans, then run:

```bash
mise run classifier:promote -- \
  data/classifier-training/evidence/<task>-<dataset-sha256> \
  data/classifier-training/evidence/<task>-<dataset-sha256>/approval.json
```

The REST collection switch is `amsha.test-data-collection.enabled` (environment
variable `AMSHA_TEST_DATA_COLLECTION_ENABLED`). Collection headers are restricted
to internal callers and are test-label provenance, not a substitute for human
review or independent evaluation.

## Evidence boundary

`datasets/v1/*.candidate.jsonl` contains seed examples copied from human-authored
repository test contracts. Every row is deliberately `pending`. An AI-generated label,
paraphrase, or review must not be changed to `approved`.

A human reviewer must verify the exact rendered input, label, split, source reference,
and license. For an approved row, the reviewer records:

- `review_status=approved`;
- a human reviewer identity that does not begin with `ai`, `auto`, or `model`;
- a non-empty review timestamp, preferably ISO-8601;
- any ambiguity, correction, or exclusion rationale in `notes`.

Release validation requires every retained row to be approved and, for every included
label, at least 20 training, 10 validation, and 20 held-out test examples. These are
lower bounds, not proof that the proposed error budgets are statistically adequate.
Near-duplicates and examples from the same interaction must stay in one split. The
held-out test split must not influence training, threshold selection, or budget design.

## What is committed and what stays local

| Location | Git policy | Contents |
| --- | --- | --- |
| `local-ml-onnx/classifier-training/` | Versioned | Scripts, lockfiles, safe seed packets, review schema, approved de-identified datasets, approved budgets, and retained documentation. |
| `data/classifier-training/review-working/` | Ignored | Raw/private source, working packets, and review CSVs that are not approved for retention. |
| `data/classifier-training/base/` | Ignored | Downloaded pinned MiniLM source files and verification receipt. |
| `data/classifier-training/runs/` | Ignored | Trained weights, draft model cards, metadata, and aggregate evaluation reports. |
| `data/classifier-cache/` | Ignored | `uv` download/build cache. |
| `data/models/` | Ignored | Locally packaged ONNX development bundles. |

Do not put raw operational conversations in a versioned repository path. A final
reviewed dataset may move into a new versioned `datasets/vN/` directory only after
human label review and an independent privacy/retention review. Generated model files
remain outside Git; retain approved release evidence and signed model artifacts in the
governed release-evidence store.

## Resume procedure when enough data exists

Run every command from the repository root. Complete the procedure independently for
each task.

1. Place the raw JSONL in the ignored working area and create a new pending packet:

   ```bash
   mise run classifier:prepare-review -- \
     data/classifier-training/review-working/v2/intent.raw.jsonl \
     data/classifier-training/review-working/v2/intent.packet.jsonl
   ```

   The source must already use the schema demonstrated by the candidate datasets.
   De-identification is defense in depth, not a substitute for privacy review.

2. Export the packet, conduct human review, and import the completed CSV:

   ```bash
   mise run classifier:export-review -- \
     data/classifier-training/review-working/v2/intent.packet.jsonl \
     data/classifier-training/review-working/v2/intent.review.csv

   mise run classifier:import-review -- \
     data/classifier-training/review-working/v2/intent.review.csv \
     local-ml-onnx/classifier-training/datasets/v2/intent.reviewed.jsonl
   ```

3. Run the strict approval and size gate:

   ```bash
   mise run classifier:validate -- \
     local-ml-onnx/classifier-training/datasets/v2/intent.reviewed.jsonl \
     --task intent --release
   ```

   Stop if this fails. Do not reduce the minimum counts, mark automated reviews as
   human, move related examples across splits, or fabricate examples to satisfy it.

4. Review `error-budgets.proposed.json`. Create a separately versioned budget document,
   obtain explicit human approval, and set its top-level status to `APPROVED`. Budget
   approval must happen before looking at held-out test results.

5. Install the pinned base once, then verify the locked environment offline:

   ```bash
   mise run classifier:install-base
   mise run classifier:toolchain-check
   ```

   `classifier:install-base` is the only step that downloads the base model. It refuses
   to replace a mismatched artifact and verifies every file against
   `base-model-lock.json`. Subsequent model loading is local-only.

6. Train one task. The command prints the immutable run directory, derived from the
   approved dataset digest:

   ```bash
   mise run classifier:train -- \
     intent local-ml-onnx/classifier-training/datasets/v2/intent.reviewed.jsonl
   ```

   The default result is
   `data/classifier-training/runs/intent-<dataset-sha256-prefix>` and is never
   overwritten.

7. Calibrate and evaluate using the separately approved budget document:

   ```bash
   mise run classifier:evaluate -- \
     intent \
     local-ml-onnx/classifier-training/datasets/v2/intent.reviewed.jsonl \
     data/classifier-training/runs/intent-<dataset-sha256-prefix> \
     --budgets local-ml-onnx/classifier-training/error-budgets.v1.json
   ```

   Evaluation writes aggregate counts, calibration, confusion matrices, and bounded
   metrics to `evaluation.json`. It returns nonzero unless the validation and held-out
   results pass an exactly `APPROVED` budget document.

8. Review and complete the draft model card, privacy analysis, per-label errors,
   adversarial cases, supported-platform resource evidence, rollback criteria, and
   retained shadow-evidence plan. Then package the passing development bundle:

   ```bash
   mise run classifier:package -- \
     intent \
     local-ml-onnx/classifier-training/datasets/v2/intent.reviewed.jsonl \
     data/classifier-training/runs/intent-<dataset-sha256-prefix>
   ```

   Packaging revalidates the dataset digest, training metadata, evaluation gate,
   PyTorch/ONNX numerical parity, tokenizer, and manifest. The output is
   `data/models/<task>-minilm-l6-<dataset-sha256-prefix>`. It is a development bundle,
   not approval for behavioral use.

9. Repeat steps 1–8 for `tool_risk` and `answer_quality`. Never reuse another task's
   thresholds or evidence. Enabling a passing bundle remains shadow-only until the ADR's
   Phase 3 promotion gates are separately approved.

## Safety rules

- No raw conversation text, credentials, labels, model answers, or exception details may
  appear in application logs, metrics, or traces.
- Classifier outputs never replace deterministic authorization, HITL, tool guards,
  answer guards, routing fallback, or the successful-tool `FINISH` invariant.
- Training, evaluation, packaging, and shadow evidence do not authorize Phase 3 behavior.
- A model or dataset change creates a new immutable run and requires fresh evaluation.
- Failed or missing local inference produces `NoOpinion` and preserves the existing
  authoritative path.
