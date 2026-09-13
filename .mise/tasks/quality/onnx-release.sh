#!/usr/bin/env bash
#MISE description = "Verify signed ONNX release SBOM and SLSA provenance blobs"
set -euo pipefail
exec bash scripts/verify_onnx_release_provenance.sh "$@"
