#!/usr/bin/env python3
"""Export pinned SentenceTransformers reference vectors for ONNX equivalence tests."""

from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path


MODEL_ID = "sentence-transformers/all-mpnet-base-v2"
MODEL_REVISION = "e8c3b32edf5434bc2275fc9bab85f82640a19130"
REQUIRED_PARTITIONS = [
    "batching",
    "language-han",
    "language-latin",
    "length-empty-very-short",
    "length-long-truncation",
    "unicode-symbols",
]
TEST_CASES = [
    {
        "id": "short-english-a",
        "partitions": ["language-latin", "batching"],
        "input": "Amsha runs local ONNX embedding inference.",
        "canonical": True,
        "relevantIds": ["short-english-b"],
    },
    {
        "id": "short-english-b",
        "partitions": ["language-latin", "batching"],
        "input": "Amsha performs embedding inference locally with ONNX.",
        "canonical": True,
        "relevantIds": ["short-english-a"],
    },
    {
        "id": "unicode-han-a",
        "partitions": ["language-han", "unicode-symbols", "batching"],
        "input": "本地 ONNX 推理生成文本向量 🤖",
        "canonical": True,
        "relevantIds": ["unicode-han-b"],
    },
    {
        "id": "unicode-han-b",
        "partitions": ["language-han", "unicode-symbols", "batching"],
        "input": "使用 ONNX 在本机计算文本嵌入 🚀",
        "canonical": False,
        "relevantIds": ["unicode-han-a"],
    },
    {
        "id": "empty-input",
        "partitions": ["length-empty-very-short"],
        "input": "",
        "canonical": True,
        "relevantIds": [],
    },
    {
        "id": "very-short-input",
        "partitions": ["length-empty-very-short", "batching"],
        "input": "ONNX",
        "canonical": True,
        "relevantIds": [],
    },
    {
        "id": "unicode-normalization",
        "partitions": ["unicode-symbols", "language-latin", "batching"],
        "input": "Café naïve — embeddings ✨",
        "canonical": True,
        "relevantIds": [],
    },
    {
        "id": "long-truncated",
        "partitions": ["length-long-truncation", "language-latin", "batching"],
        "input": " ".join(
            ["enterprise autonomous agent software architecture invariants"] * 120
        ),
        "canonical": True,
        "relevantIds": [],
    },
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Generate Python SentenceTransformers reference vectors. Downloads are "
            "disabled unless --allow-download is explicitly supplied."
        )
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("data/models/golden-corpus-v1.json"),
    )
    parser.add_argument(
        "--model-path",
        type=Path,
        help="Local SentenceTransformers/PyTorch model directory for the pinned revision.",
    )
    parser.add_argument("--allow-download", action="store_true")
    return parser.parse_args()


def load_reference_model(args: argparse.Namespace):
    try:
        import sentence_transformers
        from sentence_transformers import SentenceTransformer
    except ImportError as error:
        raise SystemExit(
            "sentence-transformers is required in the controlled evidence environment"
        ) from error

    if args.model_path is None and not args.allow_download:
        raise SystemExit("provide --model-path or explicitly authorize --allow-download")
    if args.model_path is not None and not args.model_path.is_dir():
        raise SystemExit(f"reference model directory does not exist: {args.model_path}")

    if not args.allow_download:
        os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["DO_NOT_TRACK"] = "1"
    model_source = str(args.model_path) if args.model_path is not None else MODEL_ID
    model = SentenceTransformer(
        model_source,
        revision=None if args.model_path is not None else MODEL_REVISION,
        local_files_only=not args.allow_download,
        trust_remote_code=False,
    )
    return model, sentence_transformers.__version__, model_source


def main() -> None:
    args = parse_args()
    model, generator_version, model_source = load_reference_model(args)
    vectors = model.encode(
        [test_case["input"] for test_case in TEST_CASES],
        batch_size=len(TEST_CASES),
        convert_to_numpy=True,
        normalize_embeddings=True,
        show_progress_bar=False,
    )
    if len(vectors) != len(TEST_CASES):
        raise SystemExit("reference generator returned the wrong number of vectors")

    exported_cases = []
    for test_case, vector in zip(TEST_CASES, vectors, strict=True):
        reference_vector = [float(value) for value in vector.tolist()]
        if not reference_vector or not all(math.isfinite(value) for value in reference_vector):
            raise SystemExit(f"non-finite reference vector for {test_case['id']}")
        exported_cases.append({**test_case, "referenceVector": reference_vector})

    vector_dimension = len(exported_cases[0]["referenceVector"])
    if any(len(case["referenceVector"]) != vector_dimension for case in exported_cases):
        raise SystemExit("reference vectors have inconsistent dimensions")

    corpus = {
        "version": "1.0.0",
        "modelId": MODEL_ID,
        "revision": MODEL_REVISION,
        "precision": "FP32",
        "vectorDimension": vector_dimension,
        "cosineThreshold": 0.9999,
        "minimumPartitionPassRate": 0.995,
        "nearestNeighborK": 3,
        "requiredPartitions": REQUIRED_PARTITIONS,
        "generator": {
            "implementation": "sentence-transformers",
            "version": generator_version,
            "modelSource": model_source,
            "downloadsAuthorized": args.allow_download,
        },
        "testCases": exported_cases,
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.write_text(
        json.dumps(corpus, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    temporary.replace(args.output)
    print(f"Generated Python-reference golden corpus: {args.output}")


if __name__ == "__main__":
    main()
