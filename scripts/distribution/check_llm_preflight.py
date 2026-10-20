#!/usr/bin/env python3
"""Check that the primary configured chat-provider endpoint is reachable.

This is a transport/readiness probe only: it does not send a prompt, inspect
credentials, or print response bodies.
"""

from __future__ import annotations

import contextlib
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit, urlunsplit
from urllib.request import Request, urlopen


DEFAULT_PROVIDERS = "omlx,groq,openrouter"
DEFAULT_BASE_URLS = {
    "omlx": "http://127.0.0.1:8000/v1",
    "foundry": "http://127.0.0.1:8000/v1",
    "ollama": "http://localhost:11434",
    "llamacpp": "http://127.0.0.1:9000/v1",
    "groq": "https://api.groq.com/openai/v1",
    "openrouter": "https://openrouter.ai/api/v1",
    "nvidia": "https://integrate.api.nvidia.com/v1",
    "cerebras": "https://api.cerebras.ai/v1",
    "gemini": "https://generativelanguage.googleapis.com/v1beta/openai",
    "openai": "https://api.openai.com/v1",
    "generic-openai": "",
    "huggingface": "https://router.huggingface.co/v1",
    "huuggingface": "https://router.huggingface.co/v1",
    "mistral": "https://api.mistral.ai/v1",
    "cloudflare": "",
    "cohere": "https://api.cohere.ai/compatibility/v1",
    "codex": "https://chatgpt.com/backend-api/codex",
    "claude-code": "https://api.anthropic.com",
    "agy": "https://generativelanguage.googleapis.com/v1beta/openai",
    "kimchi": "https://llm.kimchi.dev/openai/v1",
    "sarvam": "https://api.sarvam.ai/v1",
    "opencode": "https://opencode.ai/zen/v1",
}
BASE_URL_ENV_NAMES = {
    "generic-openai": "AMSHA_GENERIC_OPENAI_BASE_URL",
    "claude-code": "AMSHA_CLAUDE_CODE_BASE_URL",
    "llamacpp": "AMSHA_LLAMACPP_BASE_URL",
    "huuggingface": "AMSHA_HUGGINGFACE_BASE_URL",
}
TIMEOUT_SECONDS = 3


def _primary_provider(environment: dict[str, str]) -> str:
    configured = environment.get("AMSHA_INFINITE_LOOP_LLM_PROVIDERS", DEFAULT_PROVIDERS)
    providers = [item.strip().lower() for item in configured.split(",") if item.strip()]
    if not providers:
        raise ValueError("AMSHA_INFINITE_LOOP_LLM_PROVIDERS has no provider names")
    return providers[0]


def _provider_base_url(provider: str, environment: dict[str, str]) -> str:
    env_name = BASE_URL_ENV_NAMES.get(provider)
    if env_name is None:
        env_name = f"AMSHA_{provider.upper().replace('-', '_')}_BASE_URL"
    value = environment.get(env_name, "").strip()
    if value:
        return value
    return DEFAULT_BASE_URLS.get(provider, "")


def _probe_url(provider: str, base_url: str) -> str:
    parsed = urlsplit(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("provider base URL must be an absolute HTTP(S) URL")
    path = parsed.path.rstrip("/")
    if provider == "ollama":
        path = f"{path}/api/tags" if path else "/api/tags"
    elif not path.endswith("/models"):
        path = f"{path}/models" if path else "/models"
    return urlunsplit((parsed.scheme, parsed.netloc, path, parsed.query, ""))


def _safe_origin(url: str) -> str:
    parsed = urlsplit(url)
    host = parsed.hostname or "unknown-host"
    if parsed.port:
        host = f"{host}:{parsed.port}"
    return f"{parsed.scheme}://{host}"


def _safe_provider_name(provider: str) -> str:
    if re.fullmatch(r"[a-z0-9_-]{1,40}", provider):
        return provider
    return "configured provider"


def check(environment: dict[str, str] | None = None) -> int:
    environment = environment if environment is not None else os.environ
    try:
        override = environment.get("AMSHA_LLM_PREFLIGHT_URL", "").strip()
        provider = "custom override" if override else _primary_provider(environment)
        provider_name = _safe_provider_name(provider)
        base_url = override or _provider_base_url(provider, environment)
        if not base_url:
            print(
                f"❌ LLM preflight: no base URL is known for '{provider_name}'; set its "
                "AMSHA_*_BASE_URL or AMSHA_LLM_PREFLIGHT_URL.",
                file=sys.stderr,
            )
            return 2
        probe_url = override or _probe_url(provider, base_url)
        request = Request(probe_url, headers={"Accept": "application/json"})
        try:
            with urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                status = response.status
        except HTTPError as error:
            # A 401/403 still proves the endpoint is reachable, which is all this probe asserts.
            # `status` is captured first, so closing the response is pure cleanup and must never
            # change the outcome: on Python 3.9 `HTTPError.close()` can raise KeyError('file') from
            # tempfile internals, which turned a successful reachability check into a crash.
            status = error.code
            with contextlib.suppress(Exception):
                error.close()
        except (URLError, TimeoutError, OSError) as error:
            reason = error.reason if isinstance(error, URLError) else error
            reason_name = type(reason).__name__
            print(
                f"❌ LLM preflight: primary provider '{provider_name}' is unreachable "
                f"at {_safe_origin(probe_url)} ({reason_name}; timeout {TIMEOUT_SECONDS}s).",
                file=sys.stderr,
            )
            print(
                "   Start the provider or correct its base URL/provider order before running validation.",
                file=sys.stderr,
            )
            return 1

        if status >= 500 or status in {408, 429}:
            print(
                f"❌ LLM preflight: provider '{provider_name}' responded HTTP {status} "
                f"at {_safe_origin(probe_url)}; it is not ready for validation.",
                file=sys.stderr,
            )
            return 1

        if status in {401, 403}:
            detail = "endpoint reachable; authentication/model access not verified"
        elif status >= 400:
            detail = "host reachable; provider-specific model readiness not verified"
        else:
            detail = "model-list endpoint reachable"
        print(
            f"✅ LLM preflight: primary provider '{provider_name}' at {_safe_origin(probe_url)} "
            f"(HTTP {status}; {detail}; no completion requested)."
        )
        return 0
    except ValueError:
        print("❌ LLM preflight: invalid provider URL configuration.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(check())
