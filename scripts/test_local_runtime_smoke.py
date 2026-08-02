#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import local_runtime_smoke as smoke  # noqa: E402


class SmokeHandler(BaseHTTPRequestHandler):
    server: "SmokeServer"

    def log_message(self, format: str, *args: object) -> None:
        return

    def send_json(self, payload: object, status: int = 200) -> None:
        encoded = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def authorized(self, header: str, expected: str) -> bool:
        if self.headers.get(header) == expected:
            return True
        self.send_json({"error": "credential was rejected"}, 401)
        return False

    def do_GET(self) -> None:
        if self.path == "/v1/models":
            if self.authorized("Authorization", f"Bearer {self.server.provider_key}"):
                self.send_json({"data": [{"id": "short-model-id"}]})
        elif self.path == "/q/health":
            self.send_json(
                {
                    "status": "UP",
                    "checks": [
                        {"name": "Local ONNX Runtime", "status": "UP", "data": {"status": "ready"}},
                        {
                            "name": "Vector Index Compatibility",
                            "status": "UP",
                            "data": {"vectorStatus": "READY"},
                        },
                    ],
                }
            )
        elif self.path == "/q/metrics":
            if "text/plain" not in self.headers.get("Accept", ""):
                self.send_json({"error": "not acceptable"}, 406)
                return
            value = 3 + self.server.probe_count
            encoded = (
                f'amsha_onnx_embedding_requests_total{{outcome="success"}} {value}.0\n'
            ).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
        elif self.path == "/api/v1/admin/local-ml/onnx/indexes":
            if self.authorized("X-Amsha-API-Key", self.server.internal_key):
                self.send_json(
                    {
                        "status": "ready",
                        "activeDescriptor": {"providerId": "onnx", "vectorDimension": 768},
                        "namespaces": {"rag": {"vectorEnabled": True}},
                    }
                )
        elif self.path == "/api/v1/admin/local-ml/onnx/classifiers":
            if self.authorized("X-Amsha-API-Key", self.server.internal_key):
                self.send_json(
                    {
                        "classifiers": [
                            {
                                "classifier": classifier,
                                "startupEnabled": False,
                                "requestedEnabled": False,
                                "implementationAvailable": False,
                                "effectiveEnabled": False,
                                "acceptingRequests": False,
                                "draining": False,
                                "inFlight": 0,
                                "revision": 0,
                            }
                            for classifier in ("intent", "tool-risk", "answer-quality")
                        ]
                    }
                )
        else:
            self.send_json({"error": "not found"}, 404)

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length) or b"{}")
        if self.path == "/v1/chat/completions":
            if not self.authorized("Authorization", f"Bearer {self.server.provider_key}"):
                return
            if request.get("model") != self.server.model:
                self.send_json({"error": "wrong model"}, 400)
                return
            self.send_json({"choices": [{"message": {"role": "assistant", "content": "ok"}}]})
        elif self.path == "/api/v1/admin/local-ml/onnx/probe":
            if self.authorized("X-Amsha-API-Key", self.server.internal_key):
                self.server.probe_count += 1
                self.send_json({"status": "ready", "dimensions": 768, "l2Norm": 1.0})
        elif self.path == "/api/v1/admin/local-ml/onnx/classifiers/tool-risk/enable":
            if self.authorized("X-Amsha-API-Key", self.server.internal_key):
                self.send_json(
                    {
                        "accepted": False,
                        "changed": False,
                        "reason": "MISSING_READY_IMPLEMENTATION",
                        "classifier": {
                            "classifier": "tool-risk",
                            "implementationAvailable": False,
                            "effectiveEnabled": False,
                        },
                    },
                    409,
                )
        else:
            self.send_json({"error": "not found"}, 404)


class SmokeServer(ThreadingHTTPServer):
    provider_key = "test-provider-credential"
    internal_key = "test-internal-credential"
    model = "namespace/test-model"
    probe_count = 0


class LocalRuntimeSmokeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server = SmokeServer(("127.0.0.1", 0), SmokeHandler)
        self.server.probe_count = 0
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def test_selected_dotenv_values_and_process_precedence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / ".env"
            env_file.write_text(
                "# comment\nexport AMSHA_OMLX_API_KEY='file key'\n"
                'AMSHA_OMLX_BASE_URL="http://127.0.0.1:8000/v1" # local\n'
                "AMSHA_OMLX_MODEL=file-model\nIGNORED_SECRET=do-not-retain\n",
                encoding="utf-8",
            )
            with patch.dict(os.environ, {smoke.OMLX_MODEL: "process-model"}, clear=False):
                values = smoke.resolve_settings(
                    env_file, {smoke.OMLX_API_KEY, smoke.OMLX_BASE_URL, smoke.OMLX_MODEL}
                )
        self.assertEqual("file key", values[smoke.OMLX_API_KEY])
        self.assertEqual("process-model", values[smoke.OMLX_MODEL])
        self.assertNotIn("IGNORED_SECRET", values)

    def test_omlx_and_amsha_checks(self) -> None:
        messages: list[str] = []
        config = smoke.OmlxConfig(
            self.server.provider_key,
            f"{self.base_url}/v1",
            self.server.model,
        )
        smoke.run_omlx_checks(config, 5, messages.append)
        smoke.run_amsha_checks(
            self.base_url,
            self.server.internal_key,
            5,
            768,
            messages.append,
        )
        self.assertEqual(7, len(messages))
        self.assertTrue(all(message.startswith(smoke.PASS_PREFIX) for message in messages))
        self.assertEqual(1, self.server.probe_count)

    def test_http_error_does_not_include_response_or_key(self) -> None:
        wrong_key = "must-never-appear-in-error"
        config = smoke.OmlxConfig(wrong_key, f"{self.base_url}/v1", self.server.model)
        with self.assertRaises(smoke.SmokeFailure) as captured:
            smoke.run_omlx_checks(config, 5, lambda _: None)
        message = str(captured.exception)
        self.assertNotIn(wrong_key, message)
        self.assertNotIn("credential was rejected", message)

    def test_bundle_verification(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            model_root = Path(directory)
            bundle = model_root / smoke.DEFAULT_BUNDLE
            bundle.mkdir()
            model = b"model"
            tokenizer = b"tokenizer"
            (bundle / "model.onnx").write_bytes(model)
            (bundle / "tokenizer.json").write_bytes(tokenizer)
            manifest = {
                "modelFile": "model.onnx",
                "modelBytes": len(model),
                "modelSha256": hashlib.sha256(model).hexdigest(),
                "tokenizerFile": "tokenizer.json",
                "tokenizerBytes": len(tokenizer),
                "tokenizerSha256": hashlib.sha256(tokenizer).hexdigest(),
                "source": {
                    "repository": "https://example.test/model",
                    "revision": "revision-1",
                    "modelPath": "model.onnx",
                    "tokenizerPath": "tokenizer.json",
                    "license": "Apache-2.0",
                },
            }
            (bundle / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            messages: list[str] = []
            smoke.verify_bundle(model_root, smoke.DEFAULT_BUNDLE, messages.append)
        self.assertEqual([f"{smoke.PASS_PREFIX}pinned ONNX bundle sizes and SHA-256 digests"], messages)

    def test_native_environment_forces_generic_openai_and_onnx(self) -> None:
        config = smoke.OmlxConfig("provider-key", "http://127.0.0.1:8000/v1", "model")
        environment = smoke.native_environment(
            {"AMSHA_LLM_PROVIDER": "ollama", "AMSHA_INFINITE_LOOP_LLM_PROVIDERS": "ollama"},
            config,
            Path("/tmp/data"),
            Path("/tmp/models"),
            18080,
            "internal-key",
            smoke.DEFAULT_BUNDLE,
            smoke.DEFAULT_SPACE_ID,
            smoke.DEFAULT_SPACE_REVISION,
            768,
        )
        self.assertEqual("generic-openai", environment["AMSHA_LLM_PROVIDER"])
        self.assertEqual("generic-openai", environment["AMSHA_INFINITE_LOOP_LLM_PROVIDERS"])
        self.assertEqual("onnx", environment["AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS"])
        self.assertEqual("false", environment["AMSHA_TELEGRAM_ENABLED"])
        self.assertEqual(config.api_key, environment["AMSHA_GENERIC_OPENAI_API_KEY"])

    def test_base_url_rejects_embedded_credentials(self) -> None:
        with self.assertRaises(smoke.SmokeFailure):
            smoke.normalized_base_url("http://user:password@127.0.0.1:8000/v1", "test URL")


if __name__ == "__main__":
    unittest.main()
