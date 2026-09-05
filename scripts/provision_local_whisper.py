#!/usr/bin/env python3
"""
Reproducible offline provisioning for local in-process Whisper STT.

Downloads and verifies the pinned Whisper ONNX model bundle ahead-of-time.
Zero runtime downloads; verified against immutable SHA-256 checksums.

USAGE:
  python3 scripts/provision_local_whisper.py
  python3 scripts/provision_local_whisper.py --check
  python3 scripts/provision_local_whisper.py --target-dir /path/to/dir --force
"""

import argparse
import hashlib
import json
import os
import pathlib
import sys
import urllib.request

DEFAULT_TARGET_DIR = pathlib.Path(__file__).resolve().parent.parent / "data" / "models" / "whisper-tiny-en-v1"
BUNDLE_ID = "whisper-tiny-en-v1"
UPSTREAM_REPO = "Xenova/whisper-tiny.en"
UPSTREAM_REV = "79fb389fc764e7c395bd330e9531d9d32ada7049"
UPSTREAM_LICENSE = "Apache-2.0"

BUNDLE_FILES = {
    "encoder_model.onnx": {
        "remote_path": "onnx/encoder_model_quantized.onnx",
        "sha256": "8cc3c6f8563d1b3fbd2c5af9f64c2bed8b020bc593c402d1ef53b9f08fbf1b90",
        "size": 10124913,
    },
    "decoder_model.onnx": {
        "remote_path": "onnx/decoder_model_quantized.onnx",
        "sha256": "62b1761a6c23961cdabbf7caa25495525d78528a64baacf7e4283c83cb2941f1",
        "size": 30459718,
    },
    "tokenizer.json": {
        "remote_path": "tokenizer.json",
        "sha256": "c6ee8f089220a5b1188f6426456772572671c6141ae007eecb83c6a8349f5deb",
        "size": 2128494,
    },
    "preprocessor_config.json": {
        "remote_path": "preprocessor_config.json",
        "sha256": "a6a76d28c93edb273669eb9e0b0636a2bddbb1272c3261e47b7ca6dfdbac1b8d",
        "size": 339,
    },
    "config.json": {
        "remote_path": "config.json",
        "sha256": "37a1073be00d19118c06557896c7c148598f4d8277edc0f5bc07c9f5554839f1",
        "size": 2202,
    },
}


def compute_sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def generate_manifest(target_dir: pathlib.Path) -> dict:
    total_bytes = sum(meta["size"] for meta in BUNDLE_FILES.values())
    return {
        "bundleId": BUNDLE_ID,
        "revision": UPSTREAM_REV,
        "modelFile": "encoder_model.onnx",
        "modelSha256": BUNDLE_FILES["encoder_model.onnx"]["sha256"],
        "modelBytes": BUNDLE_FILES["encoder_model.onnx"]["size"],
        "tokenizerFile": "tokenizer.json",
        "tokenizerSha256": BUNDLE_FILES["tokenizer.json"]["sha256"],
        "tokenizerBytes": BUNDLE_FILES["tokenizer.json"]["size"],
        "estimatedResidentBytes": total_bytes * 3,
        "audioTranscription": {
            "decoderFile": "decoder_model.onnx",
            "decoderSha256": BUNDLE_FILES["decoder_model.onnx"]["sha256"],
            "decoderBytes": BUNDLE_FILES["decoder_model.onnx"]["size"],
            "preprocessorConfigFile": "preprocessor_config.json",
            "preprocessorConfigSha256": BUNDLE_FILES["preprocessor_config.json"]["sha256"],
            "configFile": "config.json",
            "configSha256": BUNDLE_FILES["config.json"]["sha256"],
            "sampleRate": 16000,
            "numChannels": 1,
            "numMelBins": 80,
            "windowLength": 400,
            "hopLength": 160,
            "maxAudioDurationSeconds": 30,
            "maxSequenceLength": 448,
            "suppressBlank": True,
        },
        "source": {
            "repository": f"https://huggingface.co/{UPSTREAM_REPO}",
            "revision": UPSTREAM_REV,
            "modelPath": BUNDLE_FILES["encoder_model.onnx"]["remote_path"],
            "tokenizerPath": BUNDLE_FILES["tokenizer.json"]["remote_path"],
            "decoderPath": BUNDLE_FILES["decoder_model.onnx"]["remote_path"],
            "license": UPSTREAM_LICENSE,
        },
    }


def main():
    parser = argparse.ArgumentParser(description="Provision local offline Whisper ONNX bundle")
    parser.add_argument("--target-dir", type=pathlib.Path, default=DEFAULT_TARGET_DIR, help="Target bundle directory")
    parser.add_argument("--force", action="store_true", help="Re-download files even if present")
    parser.add_argument("--check", action="store_true", help="Check integrity without downloading")
    args = parser.parse_args()

    target_dir: pathlib.Path = args.target_dir
    target_dir.mkdir(parents=True, exist_ok=True)

    print(f"Target dir : {target_dir}")
    print(f"Upstream   : https://huggingface.co/{UPSTREAM_REPO}/tree/{UPSTREAM_REV}")
    print(f"License    : {UPSTREAM_LICENSE}")
    print()

    missing = 0
    corrupt = 0

    for local_name, meta in BUNDLE_FILES.items():
        file_path = target_dir / local_name
        if not file_path.exists():
            if args.check:
                print(f"MISSING    {local_name}")
                missing += 1
                continue
            print(f"fetching   {local_name}...")
            url = f"https://huggingface.co/{UPSTREAM_REPO}/resolve/{UPSTREAM_REV}/{meta['remote_path']}"
            tmp_path = target_dir / f"{local_name}.part"
            try:
                urllib.request.urlretrieve(url, tmp_path)
            except Exception as e:
                print(f"FAILED     {local_name} -> {url}: {e}", file=sys.stderr)
                if tmp_path.exists():
                    tmp_path.unlink()
                sys.exit(1)
            tmp_path.rename(file_path)

        # Check integrity
        actual_sha = compute_sha256(file_path)
        actual_size = file_path.stat().st_size
        if actual_sha != meta["sha256"] or actual_size != meta["size"]:
            print(f"CORRUPT    {local_name} (size: {actual_size}, sha: {actual_sha})", file=sys.stderr)
            corrupt += 1
            if not args.check:
                file_path.unlink()
                print(f"deleted    corrupt file {local_name}", file=sys.stderr)
        else:
            print(f"verified   {local_name} ({actual_size:,} bytes, sha: {actual_sha[:16]}...)")

    if missing > 0 or corrupt > 0:
        print(f"\nFailed check: {missing} missing, {corrupt} corrupt file(s).", file=sys.stderr)
        sys.exit(1)

    manifest_path = target_dir / "manifest.json"
    if args.check:
        if not manifest_path.exists():
            print("MISSING    manifest.json", file=sys.stderr)
            sys.exit(1)
        try:
            stored = json.loads(manifest_path.read_text(encoding="utf-8"))
            if stored.get("bundleId") != BUNDLE_ID:
                print("CORRUPT    manifest.json: bundleId mismatch", file=sys.stderr)
                sys.exit(1)
            print("verified   manifest.json")
        except Exception as e:
            print(f"CORRUPT    manifest.json: {e}", file=sys.stderr)
            sys.exit(1)
        print("\nLocal Whisper bundle check passed.")
    else:
        # Write manifest.json
        manifest = generate_manifest(target_dir)
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        print(f"manifest   written to {manifest_path}")
        print("\nLocal Whisper bundle successfully provisioned and verified.")


if __name__ == "__main__":
    main()
