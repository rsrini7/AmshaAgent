#!/usr/bin/env python3
"""Offline contract tests for the distribution LLM preflight."""

from __future__ import annotations

import contextlib
import importlib.util
import io
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError, URLError


SCRIPT = Path(__file__).with_name("check_llm_preflight.py")
SPEC = importlib.util.spec_from_file_location("check_llm_preflight", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
preflight = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preflight)


class LlmPreflightTest(unittest.TestCase):
    def test_probes_first_configured_provider_models_route(self) -> None:
        output = io.StringIO()
        response = MagicMock()
        response.__enter__.return_value.status = 200
        with patch.object(preflight, "urlopen", return_value=response) as open_url:
            with contextlib.redirect_stdout(output):
                result = preflight.check(
                    {
                        "AMSHA_INFINITE_LOOP_LLM_PROVIDERS": "omlx,groq",
                        "AMSHA_OMLX_BASE_URL": "http://127.0.0.1:8000/v1",
                    }
                )

        self.assertEqual(result, 0)
        self.assertEqual(open_url.call_args.args[0].full_url, "http://127.0.0.1:8000/v1/models")
        self.assertIn("no completion requested", output.getvalue())

    def test_http_auth_response_confirms_reachability_without_claiming_auth(self) -> None:
        output = io.StringIO()
        auth_required = HTTPError("http://provider.invalid/models", 401, "Unauthorized", {}, None)
        with patch.object(preflight, "urlopen", side_effect=auth_required):
            with contextlib.redirect_stdout(output):
                result = preflight.check(
                    {"AMSHA_LLM_PREFLIGHT_URL": "https://provider.invalid/models"}
                )
        auth_required.close()

        self.assertEqual(result, 0)
        self.assertIn("authentication/model access not verified", output.getvalue())

    def test_unreachable_primary_fails_without_echoing_url_secrets(self) -> None:
        error = io.StringIO()
        with patch.object(
            preflight, "urlopen", side_effect=URLError(ConnectionRefusedError("not printed"))
        ):
            with contextlib.redirect_stderr(error):
                result = preflight.check(
                    {
                        "AMSHA_INFINITE_LOOP_LLM_PROVIDERS": "omlx",
                        "AMSHA_OMLX_BASE_URL": "http://user:secret@127.0.0.1:8000/v1",
                    }
                )

        self.assertEqual(result, 1)
        self.assertIn("unreachable", error.getvalue())
        self.assertNotIn("secret", error.getvalue())


if __name__ == "__main__":
    unittest.main()
