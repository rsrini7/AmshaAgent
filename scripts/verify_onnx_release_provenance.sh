#!/usr/bin/env bash
# Verify signed ONNX release SBOM and SLSA provenance blobs against an explicit trust root.

set -euo pipefail

usage() {
  echo "Usage: $0 <cosign-public-key> <sbom.json> <sbom.sig> <provenance.json> <provenance.sig>" >&2
}

if [ "$#" -ne 5 ]; then
  usage
  exit 2
fi

TRUST_ROOT="$1"
SBOM="$2"
SBOM_SIGNATURE="$3"
PROVENANCE="$4"
PROVENANCE_SIGNATURE="$5"

for path in "$TRUST_ROOT" "$SBOM" "$SBOM_SIGNATURE" "$PROVENANCE" "$PROVENANCE_SIGNATURE"; do
  if [ ! -f "$path" ]; then
    echo "Missing release-evidence file: $path" >&2
    exit 1
  fi
done
if ! command -v cosign >/dev/null 2>&1; then
  echo "cosign is required to verify signed release evidence" >&2
  exit 1
fi

cosign verify-blob --key "$TRUST_ROOT" --signature "$SBOM_SIGNATURE" "$SBOM" >/dev/null
cosign verify-blob --key "$TRUST_ROOT" --signature "$PROVENANCE_SIGNATURE" "$PROVENANCE" >/dev/null

python3 - "$SBOM" "$PROVENANCE" <<'PY'
import json
import pathlib
import re
import sys

sbom_path = pathlib.Path(sys.argv[1])
provenance_path = pathlib.Path(sys.argv[2])
sbom = json.loads(sbom_path.read_text(encoding="utf-8"))
provenance = json.loads(provenance_path.read_text(encoding="utf-8"))

if sbom.get("bomFormat") == "CycloneDX":
    component_names = {
        str(component.get("name", "")).lower()
        for component in sbom.get("components", [])
    }
elif str(sbom.get("spdxVersion", "")).startswith("SPDX-"):
    component_names = {
        str(package.get("name", "")).lower()
        for package in sbom.get("packages", [])
    }
else:
    raise SystemExit("SBOM must be CycloneDX or SPDX JSON")

required_components = ("onnxruntime", "tokenizers")
for required in required_components:
    if not any(required in name for name in component_names):
        raise SystemExit(f"SBOM does not contain required component: {required}")

statement_type = str(provenance.get("_type", ""))
predicate_type = str(provenance.get("predicateType", ""))
if "in-toto.io/Statement" not in statement_type:
    raise SystemExit("provenance is not an in-toto statement")
if "slsa.dev/provenance" not in predicate_type:
    raise SystemExit("provenance predicate is not SLSA provenance")

subjects = provenance.get("subject", [])
if not subjects:
    raise SystemExit("provenance has no subjects")
sha256_pattern = re.compile(r"[0-9a-f]{64}")
for subject in subjects:
    digest = subject.get("digest", {}).get("sha256", "")
    if not sha256_pattern.fullmatch(str(digest)):
        raise SystemExit(f"provenance subject lacks SHA-256: {subject.get('name', '<unknown>')}")

subject_names = {pathlib.PurePosixPath(str(subject.get("name", ""))).name for subject in subjects}
for required_subject in ("model.onnx", "tokenizer.json"):
    if required_subject not in subject_names:
        raise SystemExit(f"provenance does not cover {required_subject}")
PY

echo "Signed ONNX SBOM and SLSA provenance verified against $TRUST_ROOT"
