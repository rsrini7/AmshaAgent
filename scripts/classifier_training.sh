#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TRAINING_DIR="$PROJECT_ROOT/local-ml-onnx/classifier-training"
PYTHON_BIN="${AMSHA_CLASSIFIER_PYTHON:-python3}"
UV_CACHE_PATH="${AMSHA_CLASSIFIER_UV_CACHE_DIR:-$PROJECT_ROOT/data/classifier-cache/uv}"

usage() {
    cat <<'USAGE'
Usage: scripts/classifier_training.sh <command> [arguments]

Commands:
  validate-seeds
  validate <dataset.jsonl> --task <intent|tool_risk|answer_quality> [--release]
  prepare-review <raw.jsonl> <review-packet.jsonl>
  export-review <review-packet.jsonl> <review.csv>
  import-review <review.csv> <reviewed.jsonl>
  install-base
  toolchain-check
  train <intent|tool_risk|answer_quality> <reviewed.jsonl>
  evaluate <intent|tool_risk|answer_quality> <reviewed.jsonl> <model-dir>
  package <intent|tool_risk|answer_quality> <reviewed.jsonl> <model-dir>
USAGE
}

require_file() {
    if [[ ! -f "$1" ]]; then
        echo "Required file not found: $1" >&2
        exit 1
    fi
}

run_python() {
    "$PYTHON_BIN" "$@"
}

run_training_python() {
    mkdir -p "$UV_CACHE_PATH"
    UV_CACHE_DIR="$UV_CACHE_PATH" uv run \
        --project "$TRAINING_DIR" \
        --python 3.12 \
        python "$@"
}

command_name="${1:-}"
if [[ -z "$command_name" ]]; then
    usage
    exit 2
fi
shift

case "$command_name" in
    validate-seeds)
        if [[ $# -ne 0 ]]; then
            usage
            exit 2
        fi
        for task in intent tool_risk answer_quality; do
            dataset_name="${task//_/-}.candidate.jsonl"
            if [[ "$task" == "tool_risk" ]]; then dataset_name="tool-risk.candidate.jsonl"; fi
            if [[ "$task" == "answer_quality" ]]; then dataset_name="answer-quality.candidate.jsonl"; fi
            run_python "$TRAINING_DIR/classifier_data.py" validate \
                "$TRAINING_DIR/datasets/v1/$dataset_name" --task "$task"
        done
        ;;
    validate)
        if [[ $# -lt 3 ]]; then
            usage
            exit 2
        fi
        dataset_path="$1"
        shift
        require_file "$dataset_path"
        release_args=()
        forwarded=()
        for argument in "$@"; do
            if [[ "$argument" == "--release" ]]; then
                release_args=(--require-approved --release-size)
            else
                forwarded+=("$argument")
            fi
        done
        run_python "$TRAINING_DIR/classifier_data.py" validate \
            "$dataset_path" "${forwarded[@]}" "${release_args[@]}"
        ;;
    prepare-review)
        if [[ $# -ne 2 ]]; then
            usage
            exit 2
        fi
        require_file "$1"
        if [[ -e "$2" ]]; then
            echo "Refusing to overwrite existing review packet: $2" >&2
            exit 1
        fi
        run_python "$TRAINING_DIR/classifier_data.py" prepare-review "$1" "$2"
        ;;
    export-review)
        if [[ $# -ne 2 ]]; then
            usage
            exit 2
        fi
        require_file "$1"
        if [[ -e "$2" ]]; then
            echo "Refusing to overwrite existing review CSV: $2" >&2
            exit 1
        fi
        run_python "$TRAINING_DIR/classifier_data.py" export-review-csv "$1" "$2"
        ;;
    import-review)
        if [[ $# -ne 2 ]]; then
            usage
            exit 2
        fi
        require_file "$1"
        if [[ -e "$2" ]]; then
            echo "Refusing to overwrite existing reviewed dataset: $2" >&2
            exit 1
        fi
        run_python "$TRAINING_DIR/classifier_data.py" import-review-csv "$1" "$2"
        ;;
    install-base)
        run_python "$TRAINING_DIR/install_base_model.py" "$@"
        ;;
    toolchain-check)
        if [[ $# -ne 0 ]]; then
            usage
            exit 2
        fi
        run_training_python "$TRAINING_DIR/verify_training_toolchain.py"
        ;;
    train)
        if [[ $# -lt 2 ]]; then
            usage
            exit 2
        fi
        run_python "$TRAINING_DIR/classifier_data.py" validate "$2" \
            --task "$1" --require-approved --release-size
        run_training_python "$TRAINING_DIR/train_classifier.py" train "$@"
        ;;
    evaluate)
        if [[ $# -lt 3 ]]; then
            usage
            exit 2
        fi
        run_python "$TRAINING_DIR/classifier_data.py" validate "$2" \
            --task "$1" --require-approved --release-size
        run_training_python "$TRAINING_DIR/train_classifier.py" evaluate "$@"
        ;;
    package)
        if [[ $# -lt 3 ]]; then
            usage
            exit 2
        fi
        run_python "$TRAINING_DIR/classifier_data.py" validate "$2" \
            --task "$1" --require-approved --release-size
        run_training_python "$TRAINING_DIR/package_classifier_bundle.py" "$@"
        ;;
    *)
        usage
        exit 2
        ;;
esac
