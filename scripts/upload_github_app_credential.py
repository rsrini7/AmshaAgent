#!/usr/bin/env python3
"""Upload a local GitHub App PEM to Amsha's internal SecretVault endpoint."""

from __future__ import annotations

import argparse
import http.client
import os
import re
import stat
import sys
from pathlib import Path
from urllib.parse import urlsplit

ENDPOINT_PATH = "/api/v1/admin/connectors/github-readme/app-credential"
MAX_PRIVATE_KEY_BYTES = 32_768
LOOPBACK_HOSTS = {"127.0.0.1", "::1", "localhost"}


class CredentialUploadError(Exception):
    """Safe-to-display credential upload failure without secret values."""


def read_env_values(env_file: Path) -> dict[str, str]:
    try:
        lines = env_file.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise CredentialUploadError("Could not read the server .env file.") from exc

    values: dict[str, str] = {}
    for line in lines:
        candidate = line.strip()
        if not candidate or candidate.startswith("#"):
            continue
        if candidate.startswith("export "):
            candidate = candidate[7:].lstrip()
        name, separator, value = candidate.partition("=")
        if not separator:
            continue
        if name.strip() not in {"AMSHA_GITHUB_APP_ID", "AMSHA_API_KEYS_INTERNAL"}:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[name.strip()] = value
    return values


def validate_base_url(base_url: str) -> tuple[str, str, int]:
    try:
        parsed = urlsplit(base_url)
        port = parsed.port
    except ValueError as exc:
        raise CredentialUploadError("The Amsha base URL is invalid.") from exc
    host = parsed.hostname
    if (
        parsed.scheme not in {"http", "https"}
        or host not in LOOPBACK_HOSTS
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
    ):
        raise CredentialUploadError("The upload target must be a loopback Amsha URL.")
    return parsed.scheme, host, port or (443 if parsed.scheme == "https" else 80)


def validate_key_file(key_file: Path) -> bytearray:
    if key_file.is_symlink() or not key_file.is_file():
        raise CredentialUploadError("The PEM path must be a regular file, not a symlink.")
    if os.name != "nt":
        if stat.S_IMODE(key_file.stat().st_mode) & 0o077:
            raise CredentialUploadError("Restrict the PEM file to owner-only permissions (chmod 600).")
        if stat.S_IMODE(key_file.parent.stat().st_mode) & 0o077:
            raise CredentialUploadError("Restrict the PEM directory to owner-only permissions (chmod 700).")
    size = key_file.stat().st_size
    if size < 1 or size > MAX_PRIVATE_KEY_BYTES:
        raise CredentialUploadError("The PEM file is empty or exceeds the 32 KiB upload limit.")
    try:
        return bytearray(key_file.read_bytes())
    except OSError as exc:
        raise CredentialUploadError("Could not read the PEM file.") from exc


def upload_credential(key_file: Path, env_file: Path, base_url: str) -> int:
    settings = read_env_values(env_file)
    app_id = settings.get("AMSHA_GITHUB_APP_ID", "")
    if not re.fullmatch(r"[0-9]{1,20}", app_id):
        raise CredentialUploadError("AMSHA_GITHUB_APP_ID must be set to the numeric GitHub App ID in the server .env.")
    internal_keys = [
        value.strip()
        for value in settings.get("AMSHA_API_KEYS_INTERNAL", "").split(",")
        if value.strip()
    ]
    if not internal_keys or "changeme" in internal_keys[0].lower():
        raise CredentialUploadError("AMSHA_API_KEYS_INTERNAL is not configured in the server .env.")

    scheme, host, port = validate_base_url(base_url)
    payload = validate_key_file(key_file)
    connection: http.client.HTTPConnection
    if scheme == "https":
        connection = http.client.HTTPSConnection(host, port, timeout=20)
    else:
        connection = http.client.HTTPConnection(host, port, timeout=20)
    try:
        connection.request(
            "PUT",
            ENDPOINT_PATH,
            body=payload,
            headers={
                "Content-Type": "application/octet-stream",
                "X-Amsha-API-Key": internal_keys[0],
            },
        )
        response = connection.getresponse()
        status = response.status
        response.read(4096)
        if status != 200:
            raise CredentialUploadError(f"Amsha returned HTTP {status}; the credential was not confirmed stored.")
        return status
    except OSError as exc:
        raise CredentialUploadError("Could not reach the local Amsha server; start it and retry.") from exc
    finally:
        connection.close()
        for index in range(len(payload)):
            payload[index] = 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--key-file", required=True, type=Path, help="Owner-only PEM file outside the repository")
    parser.add_argument("--env-file", required=True, type=Path, help=".env file used by the running Amsha server")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080", help="Loopback Amsha URL (default: %(default)s)")
    arguments = parser.parse_args(argv)
    try:
        upload_credential(arguments.key_file, arguments.env_file, arguments.base_url)
    except CredentialUploadError as exc:
        print(f"GitHub App credential upload failed: {exc}", file=sys.stderr)
        return 1
    print("GitHub App private key stored in Amsha SecretVault.")
    print("The PEM path is only a protected local backup; Amsha does not read it at runtime.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
