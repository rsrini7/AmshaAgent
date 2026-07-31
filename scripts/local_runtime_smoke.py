#!/usr/bin/env python3
"""Secret-safe local OMLX and Amsha ONNX smoke checks."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Mapping, Sequence
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


OMLX_API_KEY = "AMSHA_OMLX_API_KEY"
OMLX_BASE_URL = "AMSHA_OMLX_BASE_URL"
OMLX_MODEL = "AMSHA_OMLX_MODEL"
INTERNAL_API_KEY = "AMSHA_API_KEYS_INTERNAL"
DEFAULT_AMSHA_BASE_URL = "http://127.0.0.1:8080"
DEFAULT_NATIVE_PORT = 18080
DEFAULT_BUNDLE = "all-mpnet-base-v2-e8c3b32"
DEFAULT_SPACE_ID = "sentence-transformers/all-mpnet-base-v2"
DEFAULT_SPACE_REVISION = "e8c3b32edf5434bc2275fc9bab85f82640a19130"
DEFAULT_DIMENSIONS = 768
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
PASS_PREFIX = "PASS "


class SmokeFailure(RuntimeError):
    """Expected smoke-check failure whose message is safe for terminal output."""


class RejectRedirects(HTTPRedirectHandler):
    """Do not forward authorization headers through HTTP redirects."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None


HTTP_OPENER = build_opener(RejectRedirects())
Emitter = Callable[[str], None]


@dataclass(frozen=True)
class OmlxConfig:
    api_key: str
    base_url: str
    model: str


def emit_pass(message: str, emit: Emitter) -> None:
    emit(f"{PASS_PREFIX}{message}")


def parse_env_value(raw_value: str, line_number: int) -> str:
    value = raw_value.strip()
    if not value:
        return ""

    def valid_suffix(suffix: str) -> bool:
        stripped = suffix.strip()
        return not stripped or stripped.startswith("#")

    if value[0] == "'":
        end = value.find("'", 1)
        if end < 0 or not valid_suffix(value[end + 1 :]):
            raise SmokeFailure(f"Invalid .env quoting at line {line_number}")
        return value[1:end]
    if value[0] == '"':
        parsed: list[str] = []
        escaped = False
        end = -1
        escapes = {"n": "\n", "r": "\r", "t": "\t", '"': '"', "\\": "\\"}
        for index, character in enumerate(value[1:], start=1):
            if escaped:
                parsed.append(escapes.get(character, character))
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                end = index
                break
            else:
                parsed.append(character)
        if end < 0 or escaped or not valid_suffix(value[end + 1 :]):
            raise SmokeFailure(f"Invalid .env quoting at line {line_number}")
        return "".join(parsed)
    comment = re.search(r"\s+#", value)
    if comment:
        value = value[: comment.start()]
    return value.rstrip()


def read_selected_env(path: Path, names: set[str]) -> dict[str, str]:
    if not path.exists():
        return {}
    if not path.is_file():
        raise SmokeFailure("Configured .env path is not a regular file")

    selected: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        raise SmokeFailure("Unable to read the configured .env file") from error

    for line_number, raw_line in enumerate(lines, start=1):
        if len(raw_line) > 65536:
            raise SmokeFailure(f"Invalid .env line length at line {line_number}")
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        if "=" not in line:
            continue
        name, raw_value = line.split("=", 1)
        name = name.strip()
        if name not in names:
            continue
        selected[name] = parse_env_value(raw_value, line_number)
    return selected


def resolve_settings(path: Path, names: set[str]) -> dict[str, str]:
    values = read_selected_env(path, names)
    for name in names:
        if name in os.environ:
            values[name] = os.environ[name]
    return values


def require_setting(values: Mapping[str, str], name: str) -> str:
    value = values.get(name, "").strip()
    if not value:
        raise SmokeFailure(f"Required setting {name} is missing")
    return value


def normalized_base_url(raw_url: str, label: str) -> str:
    parsed = urlsplit(raw_url.strip())
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise SmokeFailure(f"{label} must be an HTTP(S) URL")
    if parsed.username is not None or parsed.password is not None:
        raise SmokeFailure(f"{label} must not contain credentials")
    if parsed.query or parsed.fragment:
        raise SmokeFailure(f"{label} must not contain a query or fragment")
    path = parsed.path.rstrip("/")
    return urlunsplit((parsed.scheme, parsed.netloc, path, "", ""))


def endpoint(base_url: str, relative_path: str) -> str:
    return f"{base_url.rstrip('/')}/{relative_path.lstrip('/')}"


def load_omlx_config(env_file: Path) -> OmlxConfig:
    values = resolve_settings(env_file, {OMLX_API_KEY, OMLX_BASE_URL, OMLX_MODEL})
    return OmlxConfig(
        api_key=require_setting(values, OMLX_API_KEY),
        base_url=normalized_base_url(require_setting(values, OMLX_BASE_URL), OMLX_BASE_URL),
        model=require_setting(values, OMLX_MODEL),
    )


def read_limited(response) -> bytes:  # noqa: ANN001
    payload = response.read(MAX_RESPONSE_BYTES + 1)
    if len(payload) > MAX_RESPONSE_BYTES:
        raise SmokeFailure("HTTP response exceeded the smoke-runner size limit")
    return payload


def request_bytes_with_status(
    url: str,
    label: str,
    timeout: float,
    expected_status: int | None,
    *,
    method: str = "GET",
    headers: Mapping[str, str] | None = None,
    json_body: Mapping[str, object] | None = None,
) -> bytes:
    request_headers = {"Accept": "application/json"}
    if headers:
        request_headers.update(headers)
    body = None
    if json_body is not None:
        request_headers["Content-Type"] = "application/json"
        body = json.dumps(json_body, separators=(",", ":")).encode("utf-8")
    request = Request(url, data=body, headers=request_headers, method=method)
    try:
        with HTTP_OPENER.open(request, timeout=timeout) as response:
            status = response.status
            payload = read_limited(response)
    except HTTPError as error:
        status = error.code
        if expected_status != status:
            error.close()
            raise SmokeFailure(f"{label} returned HTTP {status}") from None
        try:
            payload = read_limited(error)
        finally:
            error.close()
    except (URLError, TimeoutError, OSError):
        raise SmokeFailure(f"{label} is unreachable") from None
    if expected_status is not None and status != expected_status:
        raise SmokeFailure(f"{label} returned HTTP {status}")
    if expected_status is None and not 200 <= status < 300:
        raise SmokeFailure(f"{label} returned HTTP {status}")
    return payload


def request_bytes(
    url: str,
    label: str,
    timeout: float,
    *,
    method: str = "GET",
    headers: Mapping[str, str] | None = None,
    json_body: Mapping[str, object] | None = None,
) -> bytes:
    return request_bytes_with_status(
        url,
        label,
        timeout,
        None,
        method=method,
        headers=headers,
        json_body=json_body,
    )


def decode_json_object(payload: bytes, label: str) -> dict[str, object]:
    try:
        decoded = json.loads(payload)
    except (UnicodeError, json.JSONDecodeError):
        raise SmokeFailure(f"{label} returned invalid JSON") from None
    if not isinstance(decoded, dict):
        raise SmokeFailure(f"{label} returned an unexpected JSON shape")
    return decoded


def request_json(
    url: str,
    label: str,
    timeout: float,
    *,
    method: str = "GET",
    headers: Mapping[str, str] | None = None,
    json_body: Mapping[str, object] | None = None,
) -> dict[str, object]:
    payload = request_bytes(
        url,
        label,
        timeout,
        method=method,
        headers=headers,
        json_body=json_body,
    )
    return decode_json_object(payload, label)


def request_json_with_status(
    url: str,
    label: str,
    timeout: float,
    expected_status: int,
    *,
    method: str = "GET",
    headers: Mapping[str, str] | None = None,
    json_body: Mapping[str, object] | None = None,
) -> dict[str, object]:
    payload = request_bytes_with_status(
        url,
        label,
        timeout,
        expected_status,
        method=method,
        headers=headers,
        json_body=json_body,
    )
    return decode_json_object(payload, label)


def authorization_header(api_key: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {api_key}"}


def run_omlx_checks(config: OmlxConfig, timeout: float, emit: Emitter = print) -> None:
    headers = authorization_header(config.api_key)
    catalog = request_json(endpoint(config.base_url, "models"), "OMLX model catalog", timeout, headers=headers)
    models = catalog.get("data")
    if not isinstance(models, list) or not models:
        raise SmokeFailure("OMLX model catalog did not contain any models")
    if not any(isinstance(model, dict) and isinstance(model.get("id"), str) for model in models):
        raise SmokeFailure("OMLX model catalog entries were invalid")
    emit_pass("OMLX authenticated model catalog", emit)

    completion = request_json(
        endpoint(config.base_url, "chat/completions"),
        "OMLX minimal completion",
        timeout,
        method="POST",
        headers=headers,
        json_body={
            "model": config.model,
            "messages": [{"role": "user", "content": "Reply briefly to this local smoke check."}],
            "max_tokens": 16,
            "temperature": 0,
            "stream": False,
        },
    )
    choices = completion.get("choices")
    if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
        raise SmokeFailure("OMLX minimal completion returned no choice")
    choice = choices[0]
    if not isinstance(choice.get("message"), dict) and not isinstance(choice.get("text"), str):
        raise SmokeFailure("OMLX minimal completion returned an invalid choice")
    emit_pass("OMLX minimal OpenAI-compatible completion", emit)


def safe_bundle_file(bundle_directory: Path, name: object, label: str) -> Path:
    if not isinstance(name, str) or not name or Path(name).name != name:
        raise SmokeFailure(f"Bundle manifest has an invalid {label}")
    try:
        path = (bundle_directory / name).resolve(strict=True)
        directory = bundle_directory.resolve(strict=True)
    except OSError as error:
        raise SmokeFailure(f"Bundle {label} is missing") from error
    if path.parent != directory or not path.is_file():
        raise SmokeFailure(f"Bundle {label} is not a regular direct child")
    return path


def verify_digest(path: Path, expected_bytes: object, expected_digest: object, label: str) -> None:
    if not isinstance(expected_bytes, int) or expected_bytes < 0 or path.stat().st_size != expected_bytes:
        raise SmokeFailure(f"Bundle {label} byte count did not match its manifest")
    if not isinstance(expected_digest, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_digest):
        raise SmokeFailure(f"Bundle {label} digest declaration is invalid")
    try:
        with path.open("rb") as stream:
            actual_digest = hashlib.file_digest(stream, "sha256").hexdigest()
    except OSError as error:
        raise SmokeFailure(f"Unable to hash bundle {label}") from error
    if not secrets.compare_digest(actual_digest, expected_digest):
        raise SmokeFailure(f"Bundle {label} digest did not match its manifest")


def verify_bundle(model_root: Path, bundle_id: str, emit: Emitter = print) -> None:
    bundle_directory = model_root.expanduser().resolve() / bundle_id
    manifest_path = bundle_directory / "manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise SmokeFailure("Pinned ONNX bundle manifest is missing") from None
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise SmokeFailure("Pinned ONNX bundle manifest is unreadable or invalid") from None
    if not isinstance(manifest, dict):
        raise SmokeFailure("Pinned ONNX bundle manifest has an invalid shape")

    for prefix, label in (("model", "model file"), ("tokenizer", "tokenizer file")):
        path = safe_bundle_file(bundle_directory, manifest.get(f"{prefix}File"), label)
        verify_digest(path, manifest.get(f"{prefix}Bytes"), manifest.get(f"{prefix}Sha256"), label)
    emit_pass("pinned ONNX bundle sizes and SHA-256 digests", emit)


def health_check(health: Mapping[str, object], name: str) -> Mapping[str, object]:
    checks = health.get("checks")
    if not isinstance(checks, list):
        raise SmokeFailure("Amsha health response did not contain checks")
    matching = [check for check in checks if isinstance(check, dict) and check.get("name") == name]
    if len(matching) != 1:
        raise SmokeFailure(f"Amsha health response did not contain exactly one {name} check")
    return matching[0]


def require_onnx_health(health: Mapping[str, object]) -> None:
    if health.get("status") != "UP":
        raise SmokeFailure("Amsha overall health is not UP")
    runtime = health_check(health, "Local ONNX Runtime")
    runtime_data = runtime.get("data")
    if runtime.get("status") != "UP" or not isinstance(runtime_data, dict) or runtime_data.get("status") != "ready":
        raise SmokeFailure("Local ONNX Runtime health is not ready")
    indexes = health_check(health, "Vector Index Compatibility")
    index_data = indexes.get("data")
    if indexes.get("status") != "UP" or not isinstance(index_data, dict) or index_data.get("vectorStatus") != "READY":
        raise SmokeFailure("ONNX vector index compatibility is not READY")


def successful_embedding_count(metrics: str) -> float:
    total = 0.0
    found = False
    for line in metrics.splitlines():
        if not line.startswith("amsha_onnx_embedding_requests_total{"):
            continue
        tags, separator, raw_value = line.partition("} ")
        if not separator or 'outcome="success"' not in tags:
            continue
        try:
            total += float(raw_value.strip())
        except ValueError as error:
            raise SmokeFailure("Amsha ONNX success metric was invalid") from error
        found = True
    return total if found else 0.0


def request_metrics(base_url: str, timeout: float) -> float:
    payload = request_bytes(
        endpoint(base_url, "q/metrics"),
        "Amsha metrics",
        timeout,
        headers={"Accept": "text/plain"},
    )
    try:
        metrics = payload.decode("utf-8")
    except UnicodeError:
        raise SmokeFailure("Amsha metrics returned invalid text") from None
    return successful_embedding_count(metrics)


def require_index_diagnostics(payload: Mapping[str, object], dimensions: int) -> None:
    descriptor = payload.get("activeDescriptor")
    namespaces = payload.get("namespaces")
    if payload.get("status") != "ready" or not isinstance(descriptor, dict):
        raise SmokeFailure("ONNX index diagnostics are not ready")
    if descriptor.get("providerId") != "onnx" or descriptor.get("vectorDimension") != dimensions:
        raise SmokeFailure("ONNX active index descriptor does not match the smoke configuration")
    if not isinstance(namespaces, dict) or not namespaces:
        raise SmokeFailure("ONNX index diagnostics did not contain active namespaces")
    if any(not isinstance(value, dict) or value.get("vectorEnabled") is not True for value in namespaces.values()):
        raise SmokeFailure("At least one ONNX index namespace is not vector-enabled")


def require_classifier_scaffold(payload: Mapping[str, object]) -> None:
    classifiers = payload.get("classifiers")
    expected = {"intent", "tool-risk", "answer-quality"}
    if not isinstance(classifiers, list) or len(classifiers) != len(expected):
        raise SmokeFailure("ONNX classifier status did not contain exactly three classifiers")
    if any(not isinstance(classifier, dict) for classifier in classifiers):
        raise SmokeFailure("ONNX classifier status contained an invalid snapshot")
    identifiers = {classifier.get("classifier") for classifier in classifiers}
    if identifiers != expected:
        raise SmokeFailure("ONNX classifier status contained unexpected classifier identifiers")
    for classifier in classifiers:
        identifier = classifier.get("classifier")
        available = classifier.get("implementationAvailable")
        if identifier == "intent" and available is not False:
            raise SmokeFailure("ONNX intent classifier unexpectedly reported an available implementation")
        elif identifier in {"tool-risk", "answer-quality"} and available is not True:
            raise SmokeFailure(f"ONNX {identifier} classifier did not report an available implementation")
    if any(classifier.get("effectiveEnabled") is not False for classifier in classifiers):
        raise SmokeFailure("An ONNX classifier unexpectedly reported effective enablement")


def run_amsha_checks(
    base_url: str,
    internal_api_key: str,
    timeout: float,
    dimensions: int,
    emit: Emitter = print,
) -> None:
    health = request_json(endpoint(base_url, "q/health"), "Amsha health", timeout)
    require_onnx_health(health)
    emit_pass("Amsha ONNX runtime and vector-index readiness", emit)

    internal_headers = {"X-Amsha-API-Key": internal_api_key}
    indexes = request_json(
        endpoint(base_url, "api/v1/admin/local-ml/onnx/indexes"),
        "Amsha ONNX index diagnostics",
        timeout,
        headers=internal_headers,
    )
    require_index_diagnostics(indexes, dimensions)
    emit_pass("Amsha protected ONNX index descriptor diagnostics", emit)

    classifiers = request_json(
        endpoint(base_url, "api/v1/admin/local-ml/onnx/classifiers"),
        "Amsha ONNX classifier status",
        timeout,
        headers=internal_headers,
    )
    require_classifier_scaffold(classifiers)
    emit_pass("Amsha Phase 3 active classifier implementations exposed", emit)

    enable = request_json_with_status(
        endpoint(base_url, "api/v1/admin/local-ml/onnx/classifiers/tool-risk/enable"),
        "Amsha ONNX classifier enable",
        timeout,
        200,
        method="POST",
        headers=internal_headers,
    )
    if enable.get("accepted") is not True:
        raise SmokeFailure("ONNX classifier enable was not accepted")
    emit_pass("Amsha active classifier enable succeeds with an approved implementation", emit)

    before = request_metrics(base_url, timeout)
    probe = request_json(
        endpoint(base_url, "api/v1/admin/local-ml/onnx/probe"),
        "Amsha ONNX inference probe",
        timeout,
        method="POST",
        headers=internal_headers,
    )
    if probe.get("status") != "ready" or probe.get("dimensions") != dimensions:
        raise SmokeFailure("Amsha ONNX inference probe returned an unexpected result")
    norm = probe.get("l2Norm")
    if not isinstance(norm, (int, float)) or not math.isfinite(norm) or norm <= 0:
        raise SmokeFailure("Amsha ONNX inference probe returned an invalid norm")
    after = request_metrics(base_url, timeout)
    if after <= before:
        raise SmokeFailure("Amsha ONNX success metric did not increment after inference")
    emit_pass("Amsha fixed-text ONNX inference and success-metric increment", emit)


def available_port(port: int) -> bool:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(("127.0.0.1", port))
    except OSError:
        return False
    return True


def native_environment(
    inherited: Mapping[str, str],
    config: OmlxConfig,
    data_directory: Path,
    model_root: Path,
    port: int,
    internal_api_key: str,
    bundle_id: str,
    space_id: str,
    space_revision: str,
    dimensions: int,
) -> dict[str, str]:
    environment = dict(inherited)
    environment.update(
        {
            "AMSHA_LLM_PROVIDER": "generic-openai",
            "AMSHA_GENERIC_OPENAI_API_KEY": config.api_key,
            "AMSHA_GENERIC_OPENAI_BASE_URL": config.base_url,
            "AMSHA_GENERIC_OPENAI_MODEL": config.model,
            "AMSHA_INFINITE_LOOP_LLM_PROVIDERS": "generic-openai",
            "AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS": "onnx",
            "AMSHA_TELEGRAM_ENABLED": "false",
            "AMSHA_WEB_ENABLED": "false",
            "OTEL_SDK_DISABLED": "true",
            "AMSHA_DATA_DIR": str(data_directory),
            "AMSHA_ONNX_ENABLED": "true",
            "AMSHA_ONNX_MODEL_ROOT": str(model_root),
            "AMSHA_EMBEDDING_ONNX_MODEL": bundle_id,
            "AMSHA_EMBEDDING_ONNX_SPACE_ID": space_id,
            "AMSHA_EMBEDDING_ONNX_SPACE_REVISION": space_revision,
            "AMSHA_EMBED_DIMENSIONS": str(dimensions),
            "AMSHA_API_KEYS_INTERNAL": internal_api_key,
            "AMSHA_API_RATE_LIMIT_REQUESTS_PER_MINUTE": "1000",
            "AMSHA_API_RATE_LIMIT_MUTATION_PER_MINUTE": "1000",
            "QUARKUS_HTTP_PORT": str(port),
        }
    )
    return environment


def wait_for_health(base_url: str, process: subprocess.Popen[bytes], startup_timeout: float) -> None:
    start_time = time.monotonic()
    while time.monotonic() - start_time < 150:
        if process.poll() is not None:
            raise SmokeFailure("Native process exited before health became available")
        try:
            status = request_bytes_with_status(
                f"{base_url}/q/health", "Health check", timeout=2.0, expected_status=None
            )
            if status is not None:
                return
        except SmokeFailure:
            pass
        time.sleep(0.5)
    print("Native process did not become healthy within the timeout", file=sys.stderr)
    print("Sending SIGQUIT to get thread dump...", file=sys.stderr)
    process.send_signal(signal.SIGQUIT)
    time.sleep(5)
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
    sys.exit(1)


def stop_native_process(process: subprocess.Popen[bytes]) -> bool:
    if process.poll() is not None:
        return False
    process.terminate()
    try:
        process.wait(timeout=30)
        return True
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)
        return False


def run_native(args: argparse.Namespace, project_root: Path, config: OmlxConfig, emit: Emitter = print) -> None:
    binary = (project_root / args.binary).resolve() if not args.binary.is_absolute() else args.binary.resolve()
    model_root = (project_root / args.model_root).resolve() if not args.model_root.is_absolute() else args.model_root.resolve()
    if not binary.is_file() or not os.access(binary, os.X_OK):
        raise SmokeFailure("Native binary is missing or not executable")
    if not available_port(args.port):
        raise SmokeFailure("Requested native smoke port is already in use")

    verify_bundle(model_root, args.bundle, emit)
    run_omlx_checks(config, args.request_timeout, emit)

    smoke_root = project_root / "data" / "smoke"
    smoke_root.mkdir(parents=True, exist_ok=True)
    run_directory = Path(tempfile.mkdtemp(prefix="local-runtime-", dir=smoke_root))
    data_directory = run_directory / "data"
    data_directory.mkdir()
    log_path = run_directory / "native.log"
    internal_api_key = secrets.token_urlsafe(32)
    environment = native_environment(
        os.environ,
        config,
        data_directory,
        model_root,
        args.port,
        internal_api_key,
        args.bundle,
        args.space_id,
        args.space_revision,
        args.dimensions,
    )
    base_url = f"http://127.0.0.1:{args.port}"
    process: subprocess.Popen[bytes] | None = None
    succeeded = False
    try:
        with log_path.open("wb") as log_stream:
            process = subprocess.Popen(
                [str(binary), "serve"],
                cwd=project_root,
                env=environment,
                stdin=subprocess.DEVNULL,
                stdout=log_stream,
                stderr=subprocess.STDOUT,
            )
            wait_for_health(base_url, process, args.startup_timeout)
            emit_pass("isolated native process startup", emit)
            run_amsha_checks(base_url, internal_api_key, args.request_timeout, args.dimensions, emit)
            if not stop_native_process(process):
                raise SmokeFailure("Native process did not stop gracefully")
        succeeded = True
        emit_pass("isolated native process graceful shutdown", emit)
    finally:
        if process is not None and process.poll() is None:
            stop_native_process(process)
        internal_api_key = ""
        environment["AMSHA_API_KEYS_INTERNAL"] = ""
        environment["AMSHA_GENERIC_OPENAI_API_KEY"] = ""
        if succeeded and not args.keep_artifacts:
            shutil.rmtree(run_directory)
        elif run_directory.exists():
            emit(f"INFO Diagnostic artifacts retained at {run_directory.relative_to(project_root)}")


def add_connection_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--env-file", type=Path, default=Path(".env"), help="dotenv source (process env wins)")
    parser.add_argument("--request-timeout", type=float, default=180.0, help="per-request timeout in seconds")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--project-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help=argparse.SUPPRESS,
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    omlx = subparsers.add_parser("omlx", help="check authenticated OMLX catalog and completion")
    add_connection_arguments(omlx)

    running = subparsers.add_parser("running", help="check OMLX and an already-running Amsha process")
    add_connection_arguments(running)
    running.add_argument("--amsha-base-url", default=DEFAULT_AMSHA_BASE_URL)
    running.add_argument("--model-root", type=Path, default=Path("data/models"))
    running.add_argument("--bundle", default=DEFAULT_BUNDLE)
    running.add_argument("--dimensions", type=int, default=DEFAULT_DIMENSIONS)

    native = subparsers.add_parser("native", help="start, verify, and stop an isolated native process")
    add_connection_arguments(native)
    native.add_argument("--binary", type=Path, default=Path("dist/amsha-sqlite"))
    native.add_argument("--model-root", type=Path, default=Path("data/models"))
    native.add_argument("--bundle", default=DEFAULT_BUNDLE)
    native.add_argument("--space-id", default=DEFAULT_SPACE_ID)
    native.add_argument("--space-revision", default=DEFAULT_SPACE_REVISION)
    native.add_argument("--dimensions", type=int, default=DEFAULT_DIMENSIONS)
    native.add_argument("--port", type=int, default=DEFAULT_NATIVE_PORT)
    native.add_argument("--startup-timeout", type=float, default=180.0)
    native.add_argument("--keep-artifacts", action="store_true")
    return parser


def positive_number(value: float, label: str) -> None:
    if not math.isfinite(value) or value <= 0:
        raise SmokeFailure(f"{label} must be a positive number")


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    project_root = args.project_root.expanduser().resolve()
    env_file = args.env_file
    if not env_file.is_absolute():
        env_file = project_root / env_file
    args.env_file = env_file
    try:
        positive_number(args.request_timeout, "request timeout")
        config = load_omlx_config(env_file)
        if args.command == "omlx":
            run_omlx_checks(config, args.request_timeout)
        elif args.command == "running":
            if args.dimensions <= 0:
                raise SmokeFailure("dimensions must be positive")
            model_root = args.model_root if args.model_root.is_absolute() else project_root / args.model_root
            settings = resolve_settings(env_file, {INTERNAL_API_KEY})
            internal_api_key = require_setting(settings, INTERNAL_API_KEY)
            base_url = normalized_base_url(args.amsha_base_url, "Amsha base URL")
            run_omlx_checks(config, args.request_timeout)
            verify_bundle(model_root, args.bundle)
            run_amsha_checks(base_url, internal_api_key, args.request_timeout, args.dimensions)
            internal_api_key = ""
        elif args.command == "native":
            if not 1 <= args.port <= 65535:
                raise SmokeFailure("port must be between 1 and 65535")
            if args.dimensions <= 0:
                raise SmokeFailure("dimensions must be positive")
            positive_number(args.startup_timeout, "startup timeout")
            run_native(args, project_root, config)
        else:
            raise SmokeFailure("Unknown smoke command")
    except SmokeFailure as error:
        print(f"FAIL {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("FAIL Smoke check interrupted", file=sys.stderr)
        return 130
    except Exception:
        print("FAIL Unexpected smoke-runner error; no response or credential data was printed", file=sys.stderr)
        return 1
    finally:
        config = None
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
