#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/../lib/repo_root.sh" ]; then
  # shellcheck source=../lib/repo_root.sh
  source "$SCRIPT_DIR/../lib/repo_root.sh"
  PROJECT_ROOT="$(get_repo_root "$SCRIPT_DIR")"
else
  PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
fi
TRAINING_DIR="$PROJECT_ROOT/local-ml-onnx/classifier-training"
PYTHON_BIN="${AMSHA_CLASSIFIER_PYTHON:-python3}"
UV_CACHE_PATH="${AMSHA_CLASSIFIER_UV_CACHE_DIR:-$PROJECT_ROOT/data/classifier-cache/uv}"
RUN_ROOT="${AMSHA_CLASSIFIER_RUN_ROOT:-$PROJECT_ROOT/data/classifier-training/runs}"
MODEL_ROOT="${AMSHA_CLASSIFIER_MODEL_ROOT:-$PROJECT_ROOT/data/models}"
EVIDENCE_ROOT="${AMSHA_CLASSIFIER_EVIDENCE_ROOT:-$PROJECT_ROOT/data/classifier-training/evidence}"
BASE_MODEL="$PROJECT_ROOT/data/classifier-training/base/all-MiniLM-L6-v2-1110a243"

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
build-dataset <task> <target.jsonl> <candidate.jsonl> [...]
collect <task> <target.jsonl>
release-candidate <task> <reviewed.jsonl> <approved-budgets.json>
promote <evidence-dir> <human-approval.json> [model-root]
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
    build-dataset)
        [[ $# -ge 3 ]] || { usage; exit 2; }
        task="$1"
        target="$2"
        shift 2
        run_python "$TRAINING_DIR/build_classifier_dataset.py" "$task" "$target" "$@" --assign-splits
        ;;
    collect)
        [[ $# -eq 2 ]] || { usage; exit 2; }
        task="$1"
        target="$2"
        source_name="${task//_/-}.autotest.jsonl"
        source="$PROJECT_ROOT/data/classifier-training/review-working/$source_name"
        require_file "$source"
        run_python "$TRAINING_DIR/build_classifier_dataset.py" "$task" "$target" "$source" --assign-splits
        ;;
    release-candidate)
        [[ $# -eq 3 ]] || { usage; exit 2; }
        task="$1"
        dataset="$2"
        budgets="$3"
        require_file "$dataset"
        require_file "$budgets"
        run_python "$TRAINING_DIR/classifier_data.py" validate "$dataset" \
            --task "$task" --require-approved --release-size
        if [[ ! -f "$BASE_MODEL/source-lock.verified.json" ]]; then
            run_training_python "$TRAINING_DIR/install_base_model.py"
        fi
        run_training_python "$TRAINING_DIR/verify_training_toolchain.py"
        digest="$(run_python "$TRAINING_DIR/classifier_data.py" digest "$dataset" | "$PYTHON_BIN" -c 'import json,sys; print(json.load(sys.stdin)["sha256"])')"
        run_training_python "$TRAINING_DIR/train_classifier.py" train "$task" "$dataset" --output-root "$RUN_ROOT"
        run_dir="$RUN_ROOT/$task-${digest:0:12}"
        run_training_python "$TRAINING_DIR/train_classifier.py" evaluate "$task" "$dataset" "$run_dir" --budgets "$budgets"
        run_training_python "$TRAINING_DIR/package_classifier_bundle.py" "$task" "$dataset" "$run_dir" --model-root "$MODEL_ROOT"
        bundle_dir="$MODEL_ROOT/${task//_/-}-minilm-l6-${digest:0:8}"
        evidence_dir="$EVIDENCE_ROOT/$task-$digest"
        run_python "$TRAINING_DIR/classifier_evidence.py" create \
            "$task" "$dataset" "$run_dir" "$bundle_dir" "$evidence_dir"
        ;;
    promote)
        [[ $# -eq 2 || $# -eq 3 ]] || { usage; exit 2; }
        evidence_dir="$1"
        approval="$2"
        target_root="${3:-$MODEL_ROOT}"
        run_python "$TRAINING_DIR/classifier_evidence.py" promote "$evidence_dir" "$approval" "$target_root"
        ;;
*)
        usage
        exit 2
        ;;
esac
