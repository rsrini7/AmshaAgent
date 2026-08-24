#!/usr/bin/env python3
"""local_soak.py — duration-based degradation watch for a running Amsha artifact.

A soak test answers a different question than functional suites: not "does
everything work?" but "does everything KEEP working over time?". This script
boots (or attaches to) an Amsha process and watches four degradation signals
for a configurable window:

  1. Periodic health + authenticated API probes (catches pool/FD/cache decay)
  2. Log-delta scanning (new ERROR/WARN lines between cycles)
  3. RSS sampling (memory slope after warmup)
  4. Optional deep probes: rest-http validator scripts run periodically so the
     full functional surface is exercised against the long-lived process

Pass criteria: every probe green, zero new ERROR lines, RSS growth within
budget, graceful shutdown at the end. Any violation -> non-zero exit.

Usage examples:
  # Attach to an already-running instance (dev server, booted binary):
  python3 scripts/local_soak.py --base-url http://127.0.0.1:8080 --duration-minutes 30

  # Launch the native artifact in a scratch data dir and soak for 2 hours:
  python3 scripts/local_soak.py --binary dist/amsha-sqlite --duration-minutes 120

  # Add deep functional probes every 10th cycle:
  python3 scripts/local_soak.py --base-url http://127.0.0.1:8080 \
      --deep-cmd 'TEST_BASE={BASE} bash rest-http/scripts/validate_onnx.sh'
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

WARN_THRESHOLD = {"ERROR": 0}  # any new ERROR line fails the cycle


def log(message: str) -> None:
    print(f"[soak {time.strftime('%H:%M:%S')}] {message}", flush=True)


def load_env_key(env_file: Path, name: str) -> str:
    """Reads one KEY=VALUE pair from an .env file without printing it."""
    if not env_file.is_file():
        return ""
    for raw in env_file.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = raw.strip()
        if line.startswith(f"{name}="):
            value = line.split("=", 1)[1].strip().strip('"').strip("'")
            return value
    return ""


def http_status(url: str, headers: dict[str, str] | None = None, timeout: float = 10.0) -> int:
    request = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except (urllib.error.URLError, TimeoutError, OSError):
        return -1


def rss_mb(pid: int) -> int:
    try:
        output = subprocess.run(
            ["ps", "-o", "rss=", "-p", str(pid)],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        return int(output.split()[0]) // 1024
    except (subprocess.CalledProcessError, IndexError, ValueError):
        return -1


class LogScanner:
    """Tracks byte offset into a log file and reports new ERROR/WARN lines."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.offset = 0
        self.errors: list[str] = []
        self.warn_count = 0

    def scan(self) -> tuple[int, int]:
        """Returns (new_errors, new_warns) since last scan."""
        if not self.path.is_file():
            return 0, 0
        new_errors = 0
        new_warns = 0
        with self.path.open("r", encoding="utf-8", errors="ignore") as handle:
            handle.seek(self.offset)
            for line in handle:
                if " ERROR " in line or " ERROR:" in line:
                    new_errors += 1
                    self.errors.append(line.strip()[:240])
                elif " WARN " in line or " WARN:" in line:
                    new_warns += 1
            self.offset = handle.tell()
        self.errors.extend([])
        self.warn_count += new_warns
        return new_errors, new_warns


def build_child_env(env_file: Path, data_dir: Path, port: int, log_path: Path) -> dict[str, str]:
    environment = dict(os.environ)
    internal_key = load_env_key(env_file, "AMSHA_API_KEYS_INTERNAL")
    if internal_key:
        environment["AMSHA_API_KEYS_INTERNAL"] = internal_key
    # Mirror the official smoke's provider wiring: the artifact's baked default
    # embedding slots (jina/ollama) need configs we do not have, so pin the
    # local ONNX embedding provider and generic-openai LLM provider.
    omlx_key = load_env_key(env_file, "AMSHA_OMLX_API_KEY")
    omlx_base = load_env_key(env_file, "AMSHA_OMLX_BASE_URL")
    omlx_model = load_env_key(env_file, "AMSHA_OMLX_MODEL")
    model_root = (env_file.parent / "data" / "models").resolve()
    environment.update({
        "QUARKUS_HTTP_PORT": str(port),
        "AMSHA_DATA_DIR": str(data_dir),
        "QUARKUS_LOG_FILE_ENABLE": "true",
        "QUARKUS_LOG_FILE_PATH": str(log_path),
        "AMSHA_TELEGRAM_ENABLED": "false",
        "OTEL_SDK_DISABLED": "true",
        "AMSHA_DOTENV_ENABLED": "false",
        "AMSHA_ONNX_ENABLED": "true",
        "AMSHA_EMBEDDING_ONNX_MODEL": "all-mpnet-base-v2-e8c3b32",
        "AMSHA_ONNX_MODEL_ROOT": str(model_root),
        "AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS": "onnx",
        "AMSHA_INFINITE_LOOP_LLM_PROVIDERS": "generic-openai",
    })
    if omlx_key:
        environment["AMSHA_GENERIC_OPENAI_API_KEY"] = omlx_key
    if omlx_base:
        environment["AMSHA_GENERIC_OPENAI_BASE_URL"] = omlx_base
    if omlx_model:
        environment["AMSHA_GENERIC_OPENAI_MODEL"] = omlx_model
    return environment


def run_deep_probe(command: str, base_url: str, cwd: Path) -> tuple[bool, str]:
    substituted = command.replace("{BASE}", base_url)
    completed = subprocess.run(
        ["/bin/bash", "-c", substituted], cwd=str(cwd),
        capture_output=True, text=True,
    )
    tail = " | ".join(
        chunk.strip() for chunk in completed.stdout.splitlines()[-2:] if chunk.strip()
    )
    return completed.returncode == 0, tail[:200]


def main() -> int:
    parser = argparse.ArgumentParser(description="Amsha local soak test")
    parser.add_argument("--binary", help="Artifact to launch (mutually exclusive with --base-url)")
    parser.add_argument("--base-url", help="Attach to an already-running instance")
    parser.add_argument("--duration-minutes", type=float, default=120.0)
    parser.add_argument("--interval-seconds", type=int, default=300)
    parser.add_argument("--deep-every", type=int, default=0,
                        help="Run the deep probe every Nth cycle (0 = never)")
    parser.add_argument("--deep-cmd", default="",
                        help="Shell command for deep probes; {BASE} is substituted; "
                             "e.g. 'TEST_BASE={BASE} bash rest-http/scripts/validate_onnx.sh'")
    parser.add_argument("--rss-grow-limit-mb", type=int, default=256)
    parser.add_argument("--warmup-cycles", type=int, default=2,
                        help="Cycles excluded from the RSS growth baseline")
    parser.add_argument("--port", type=int, default=8491)
    parser.add_argument("--data-dir", default="./data-soak")
    parser.add_argument("--env-file", default=".env")
    args = parser.parse_args()

    if bool(args.binary) == bool(args.base_url):
        parser.error("exactly one of --binary or --base-url is required")

    repo_root = Path(__file__).resolve().parent.parent
    scratch = Path(args.data_dir).resolve()
    scratch.mkdir(parents=True, exist_ok=True)
    env_file = (repo_root / args.env_file).resolve()

    child = None
    if args.binary:
        log_path = scratch / "app.log"
        environment = build_child_env(env_file, scratch, args.port, log_path)
        binary = Path(args.binary).resolve()
        log(f"Launching {binary.name} on port {args.port}; data dir {scratch}")
        child = subprocess.Popen([str(binary), "serve"], env=environment, cwd=str(scratch),
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        base_url = f"http://127.0.0.1:{args.port}"
    else:
        base_url = args.base_url.rstrip("/")
        log_path = scratch / "attach.log"

    internal_key = load_env_key(env_file, "AMSHA_API_KEYS_INTERNAL")
    auth_headers = {"X-Amsha-API-Key": internal_key} if internal_key else {}
    if not auth_headers:
        log("WARNING: no AMSHA_API_KEYS_INTERNAL found; auth probe will be skipped")

    deadline = time.monotonic() + args.duration_minutes * 60
    scanner = LogScanner(log_path if log_path.exists() else Path("/dev/null"))
    rss_samples: list[int] = []
    cycles = 0
    ready = False
    ready_after_cycles = -1
    probe_failures = 0
    deep_failures = 0
    verdict = "PASS"

    log(f"Soaking {base_url} for {args.duration_minutes:g} min "
        f"(interval {args.interval_seconds}s, deep-every {args.deep_every})")

    try:
        while time.monotonic() < deadline:
            cycles += 1
            health = http_status(f"{base_url}/q/health")
            authed = (http_status(f"{base_url}/api/v1/admin/local-ml/onnx/indexes", auth_headers)
                      if auth_headers else -1)

            new_errors, new_warns = scanner.scan()
            if child and child.poll() is not None:
                log(f"FAIL: child exited early with code {child.returncode}")
                verdict = "FAIL"
                break

            sample = rss_mb(child.pid) if child else -1
            # Sample RSS only from the first healthy cycle onward: pre-init
            # samples would count normal model-load/JIT growth as a leak.
            if sample > 0 and health == 200:
                rss_samples.append(sample)

            if health == 200 and not ready:
                ready = True
                ready_after_cycles = cycles
                log(f"Application ready after {cycles} cycle(s); soak window starts now.")
            ok = True
            if ready:
                ok = (health == 200 and (authed in (-1, 200))
                      and new_errors <= WARN_THRESHOLD["ERROR"])
            elif new_errors > WARN_THRESHOLD["ERROR"]:
                # Boot-time errors still matter even before readiness.
                ok = False
            if not ok:
                probe_failures += 1
                verdict = "FAIL"
            deep_note = ""
            if args.deep_cmd and args.deep_every and cycles % args.deep_every == 0:
                passed, note = run_deep_probe(args.deep_cmd, base_url, repo_root)
                deep_note = f" | deep={'OK' if passed else 'FAIL'} {note}"
                if not passed:
                    deep_failures += 1
                    verdict = "FAIL"

            log(f"cycle {cycles}: health={health} auth={authed} "
                f"newErr={new_errors} newWarn={new_warns} "
                f"rss={sample if sample > 0 else 'n/a'}MB{deep_note}")

            if time.monotonic() >= deadline:
                break
            time.sleep(max(5, args.interval_seconds))
    finally:
        if child and child.poll() is None:
            log("Sending SIGTERM for graceful shutdown...")
            child.send_signal(signal.SIGTERM)
            try:
                child.wait(timeout=60)
                log(f"Child exited cleanly (code {child.returncode}).")
            except subprocess.TimeoutExpired:
                child.kill()
                log("FAIL: child did not shut down gracefully; killed.")
                verdict = "FAIL"

    steady = rss_samples[args.warmup_cycles :] or rss_samples
    # Drift = spread across the steady window (a leak shows as monotonic climb;
    # stable working set shows as bounded oscillation around its plateau).
    growth = (max(steady) - min(steady)) if len(steady) >= 2 else 0
    print("\n===== SOAK REPORT =====")
    print(f"cycles            : {cycles} (ready after {ready_after_cycles})")
    print(f"probe failures    : {probe_failures}")
    print(f"deep failures     : {deep_failures}")
    print(f"log WARN lines    : {scanner.warn_count}")
    unique_errors = list(dict.fromkeys(scanner.errors))
    print(f"log ERROR lines   : {len(scanner.errors)} ({len(unique_errors)} unique)")
    for line in unique_errors[:5]:
        print(f"  ! {line}")
    if rss_samples:
        print(f"RSS MB (min/max)  : {min(rss_samples)} / {max(rss_samples)} "
              f"(steady growth vs warmup: {growth:+d} MB, budget {args.rss_grow_limit_mb})")
    print(f"VERDICT           : {verdict}")
    if growth > args.rss_grow_limit_mb:
        verdict = "FAIL"
        print("RSS growth exceeded budget.")
    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
