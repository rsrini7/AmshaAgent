#!/usr/bin/env bash
#
# Reproducible provisioning for OMLX-hosted Whisper STT.
#
# WHY THIS EXISTS
# ---------------
# MLX-converted Whisper repositories published under mlx-community ship ONLY
# `config.json` + weights. They omit the HuggingFace feature-extractor configuration and
# tokenizer files that an OpenAI-compatible server needs to run /v1/audio/transcriptions.
#
#   $ hf files mlx-community/whisper-large-v3-turbo
#   .gitattributes, README.md, config.json, weights.safetensors
#   $ hf files mlx-community/whisper-large-v3-mlx
#   .gitattributes, README.md, config.json, weights.npz
#
# The conversion repos are weights-only by design: the converter assumes the caller pairs the
# weights with the tokenizer from the upstream OpenAI repository. OMLX has no runtime Hub
# fallback, so with only the weights present it fails at model load with:
#
#   STT model '<dir>' is missing the HuggingFace processor / feature-extractor configuration
#   (preprocessor_config.json and/or tokenizer files). MLX-converted repositories sometimes
#   omit these. Fix: either use an HF-compatible variant of the model or copy
#   preprocessor_config.json, tokenizer.json and special_tokens_map.json from the upstream
#   HuggingFace repo into the local model directory.
#
# OMLX also CACHES that load failure, so the model stays unavailable until the server is
# reloaded (POST /admin/api/reload, or restart OMLX) even after the files are restored.
#
# This script restores the missing sidecar files from the pinned upstream OpenAI revision.
# It is idempotent: files that already exist are left untouched unless --force is given.
#
# PINNING
# -------
# Downloads are pinned to an immutable commit SHA, not a branch. `main` on HuggingFace is a
# moving target: an upstream edit to any tokenizer file would silently change transcription
# behaviour on the next provisioning run. Bump UPSTREAM_REV deliberately, and re-run the STT
# round-trip evidence before adopting a new revision.
#
# The restored files are cache artifacts under ~/.omlx. They are NEVER committed to this repo
# (see .gitignore); re-running this script reproduces them byte-for-byte on any machine.
#
# USAGE
#   ./scripts/provision_omlx_whisper_stt.sh
#   ./scripts/provision_omlx_whisper_stt.sh --model-dir /path/to/model
#   ./scripts/provision_omlx_whisper_stt.sh --repo openai/whisper-large-v3 --force
#   ./scripts/provision_omlx_whisper_stt.sh --check          # report drift, change nothing
#   mise run models:provision-omlx-whisper-stt               # same thing via mise
#
# After running: reload or restart OMLX, then verify with
#   curl -s http://127.0.0.1:8000/v1/audio/transcriptions \
#        -H "Authorization: Bearer $AMSHA_OMLX_API_KEY" \
#        -F file=@sample.wav -F model=whisper-large-v3-turbo
#
set -euo pipefail

MODEL_DIR="${HOME}/.omlx/models/mlx-community/whisper-large-v3-turbo"
UPSTREAM_REPO="openai/whisper-large-v3-turbo"
# openai/whisper-large-v3-turbo @ 2024-10-04T14:51:11Z (last upstream change).
UPSTREAM_REV="41f01f3fe87f28c78e2fbf8b568835947dd65ed9"
FORCE=0
CHECK=0

# Sidecar files required for STT. tokenizer.json/vocab.json/merges.txt are needed by the
# tokenizer implementation itself; OMLX's error only names the first three but the load
# fails without the rest.
#
# added_tokens.json also exists upstream and is deliberately NOT restored: Whisper's added
# tokens are already embedded in tokenizer.json, so the file is redundant, and this list is
# kept identical to the set that produced the verified TTS -> STT round-trip.
REQUIRED_FILES=(
  "preprocessor_config.json"
  "tokenizer.json"
  "tokenizer_config.json"
  "special_tokens_map.json"
  "vocab.json"
  "merges.txt"
)

while [[ $# -gt 0 ]]; do
  case "$1" in
    --model-dir) MODEL_DIR="$2"; shift 2 ;;
    --repo)      UPSTREAM_REPO="$2"; shift 2 ;;
    --revision)  UPSTREAM_REV="$2"; shift 2 ;;
    --force)     FORCE=1; shift ;;
    --check)     CHECK=1; shift ;;
    -h|--help)   sed -n '2,44p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ ! -d "$MODEL_DIR" ]]; then
  echo "error: model directory does not exist: $MODEL_DIR" >&2
  echo "hint: download the model through OMLX first, or pass --model-dir" >&2
  exit 1
fi

echo "Model dir : $MODEL_DIR"
echo "Upstream  : https://huggingface.co/${UPSTREAM_REPO}/tree/${UPSTREAM_REV}"
echo

missing=0
if [[ "$CHECK" -eq 1 ]]; then
  for f in "${REQUIRED_FILES[@]}"; do
    if [[ -f "${MODEL_DIR}/${f}" ]]; then
      echo "present   $f ($(wc -c < "${MODEL_DIR}/${f}" | tr -d ' ') bytes)"
    else
      echo "MISSING   $f"
      missing=$((missing + 1))
    fi
  done
  echo
  if [[ "$missing" -eq 0 ]]; then
    echo "All ${#REQUIRED_FILES[@]} sidecar files present. Nothing to do."
    exit 0
  fi
  echo "${missing} sidecar file(s) missing. Re-run without --check to restore them."
  exit 1
fi

for f in "${REQUIRED_FILES[@]}"; do
  target="${MODEL_DIR}/${f}"
  if [[ -f "$target" && "$FORCE" -eq 0 ]]; then
    echo "skip      $f (already present, $(wc -c < "$target" | tr -d ' ') bytes)"
    continue
  fi

  url="https://huggingface.co/${UPSTREAM_REPO}/resolve/${UPSTREAM_REV}/${f}"
  tmp="${target}.part"
  if ! curl -fsSL --retry 3 --retry-delay 2 -o "$tmp" "$url"; then
    echo "FAILED    $f -> $url" >&2
    rm -f "$tmp"
    exit 1
  fi

  size=$(wc -c < "$tmp" | tr -d ' ')
  if [[ "$size" -lt 32 ]]; then
    echo "FAILED    $f downloaded only ${size} bytes (likely an error page)" >&2
    rm -f "$tmp"
    exit 1
  fi

  mv "$tmp" "$target"
  echo "restored  $f (${size} bytes) @ ${UPSTREAM_REV}"
done

echo
echo "Done. Sidecar files restored from ${UPSTREAM_REPO}@${UPSTREAM_REV}."
echo
echo "IMPORTANT: OMLX caches load failures. Reload or restart OMLX before retrying:"
echo "  POST http://127.0.0.1:8000/admin/api/reload   (requires admin auth)"
echo "  or restart the OMLX server"
echo
echo "These files are cache artifacts under ~/.omlx and are NOT committed to this repo."
echo "Re-run this script to reproduce them on any machine."
