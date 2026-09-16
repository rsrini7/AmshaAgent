#!/usr/bin/env python3
"""rest-http/scripts/test_common_http.py — Contract tests for common_http.sh.

Spawns a local HTTP mock server and exercises:
1. Successful requests and request-scoped isolation.
2. Method-aware 429 safe retries (GET retries, POST does not without --idempotent).
3. Retry-After cap enforcement (>120s does not retry).
4. Concurrent request isolation (no clobbering).
5. Composable cleanup trap execution.
"""

from __future__ import annotations

import http.server
import json
import os
import socketserver
import subprocess
import sys
import threading
import time
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
COMMON_HTTP = SCRIPTS_DIR / "common_http.sh"


class MockHandler(http.server.BaseHTTPRequestHandler):
    get_count = 0
    post_count = 0
    post_idempotent_count = 0
    cap_count = 0

    def log_message(self, format, *args):
        pass  # Suppress logging

    def do_GET(self):
        if self.path == "/normal":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"status": "ok"}')
        elif self.path == "/retry-429":
            MockHandler.get_count += 1
            if MockHandler.get_count == 1:
                self.send_response(429)
                self.send_header("Retry-After", "1")
                self.end_headers()
                self.wfile.write(b'{"error": "rate limited"}')
            else:
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"status": "retried_ok"}')
        elif self.path == "/cap-429":
            MockHandler.cap_count += 1
            self.send_response(429)
            self.send_header("Retry-After", "999")
            self.end_headers()
            self.wfile.write(b'{"error": "too high"}')
        elif self.path == "/slow-concurrent-a":
            time.sleep(0.3)
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"RESPONSE_A")
        elif self.path == "/slow-concurrent-b":
            time.sleep(0.3)
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"RESPONSE_B")
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path == "/post-429-no-retry":
            MockHandler.post_count += 1
            self.send_response(429)
            self.send_header("Retry-After", "1")
            self.end_headers()
            self.wfile.write(b'{"error": "rate limited post"}')
        elif self.path == "/post-429-idempotent":
            MockHandler.post_idempotent_count += 1
            if MockHandler.post_idempotent_count == 1:
                self.send_response(429)
                self.send_header("Retry-After", "1")
                self.end_headers()
                self.wfile.write(b'{"error": "rate limited"}')
            else:
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"status": "post_retried_ok"}')
        else:
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"status": "post_ok"}')


class CommonHttpContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = socketserver.TCPServer(("127.0.0.1", 0), MockHandler)
        cls.port = cls.server.server_address[1]
        cls.server_thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.server_thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def run_bash_snippet(self, snippet: str, extra_env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env["TEST_BASE"] = f"http://127.0.0.1:{self.port}"
        env["AMSHA_DEFAULT_RETRY_WAIT"] = "1"
        if extra_env:
            env.update(extra_env)
        return subprocess.run(
            ["bash", "-c", snippet],
            cwd=SCRIPTS_DIR,
            env=env,
            capture_output=True,
            text=True,
            check=False,
            timeout=15,
        )

    def test_normal_get_request_sets_http_code_and_resp(self):
        snippet = f"""
        source "{COMMON_HTTP}"
        http_call "http://127.0.0.1:{self.port}/normal"
        echo "CODE:$HTTP_CODE"
        echo "RESP:$(cat "$HTTP_RESP")"
        echo "RUN_DIR_EXISTS:$([ -d "$_AMSHA_RUN_DIR" ] && echo true || echo false)"
        """
        res = self.run_bash_snippet(snippet)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertIn("CODE:200", res.stdout)
        self.assertIn('RESP:{"status": "ok"}', res.stdout)
        self.assertIn("RUN_DIR_EXISTS:true", res.stdout)

    def test_get_429_safe_retry(self):
        MockHandler.get_count = 0
        snippet = f"""
        source "{COMMON_HTTP}"
        http_call "http://127.0.0.1:{self.port}/retry-429"
        echo "CODE:$HTTP_CODE"
        echo "RESP:$(cat "$HTTP_RESP")"
        """
        res = self.run_bash_snippet(snippet)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertEqual(MockHandler.get_count, 2)
        self.assertIn("CODE:200", res.stdout)
        self.assertIn('RESP:{"status": "retried_ok"}', res.stdout)

    def test_post_429_does_not_retry_without_idempotent(self):
        MockHandler.post_count = 0
        snippet = f"""
        source "{COMMON_HTTP}"
            code=$(http_call -d 'payload' "http://127.0.0.1:{self.port}/post-429-no-retry")
        echo "CODE:$code"
        """
        res = self.run_bash_snippet(snippet)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertEqual(MockHandler.post_count, 1)
        self.assertIn("CODE:429", res.stdout)

    def test_post_429_retries_with_explicit_idempotent(self):
        MockHandler.post_idempotent_count = 0
        snippet = f"""
        source "{COMMON_HTTP}"
        http_call --idempotent -X POST "http://127.0.0.1:{self.port}/post-429-idempotent"
        echo "CODE:$HTTP_CODE"
        echo "RESP:$(cat "$HTTP_RESP")"
        """
        res = self.run_bash_snippet(snippet)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertEqual(MockHandler.post_idempotent_count, 2)
        self.assertIn("CODE:200", res.stdout)
        self.assertIn('RESP:{"status": "post_retried_ok"}', res.stdout)

    def test_retry_after_cap_enforced(self):
        MockHandler.cap_count = 0
        snippet = f"""
        source "{COMMON_HTTP}"
        code=$(http_call "http://127.0.0.1:{self.port}/cap-429")
        echo "CODE:$code"
        """
        res = self.run_bash_snippet(snippet)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertEqual(MockHandler.cap_count, 1)
        self.assertIn("CODE:429", res.stdout)

    def test_composable_cleanup_trap_executed_and_run_dir_removed(self):
        snippet = f"""
        marker_file="$(mktemp)"
        prior_file="$(mktemp)"
        rm -f /tmp/resp
        trap 'echo PRIOR > "$prior_file"' EXIT
        source "{COMMON_HTTP}"
        register_cleanup "echo HOOK_RAN"
        echo "MARKER:$marker_file"
        echo "RUN_DIR:$_AMSHA_RUN_DIR"
        echo "PRIOR:$prior_file"
        exit 0
        """
        res = self.run_bash_snippet(snippet)
        self.assertEqual(res.returncode, 0, res.stderr)
        marker = None
        run_dir = None
        prior = None
        for line in res.stdout.splitlines():
            if line.startswith("MARKER:"):
                marker = line.split(":", 1)[1]
            elif line.startswith("RUN_DIR:"):
                run_dir = line.split(":", 1)[1]
            elif line.startswith("PRIOR:"):
                prior = line.split(":", 1)[1]

        self.assertIsNotNone(marker)
        Path(marker).unlink(missing_ok=True)
        self.assertIn("HOOK_RAN", res.stdout)

        self.assertIsNotNone(run_dir)
        self.assertFalse(Path(run_dir).exists(), f"Run directory {run_dir} should be cleaned up")
        self.assertIsNotNone(prior)
        self.assertEqual(Path(prior).read_text().strip(), "PRIOR")
        Path(prior).unlink(missing_ok=True)
        self.assertFalse(Path("/tmp/resp").exists())

    def test_concurrent_request_isolation(self):
        snippet = f"""
        source "{COMMON_HTTP}"

        # Subshell 1
        (
            http_call -o "$_AMSHA_RUN_DIR/final_a" "http://127.0.0.1:{self.port}/slow-concurrent-a"
        ) &
        p1=$!

        # Subshell 2
        (
            http_call -o "$_AMSHA_RUN_DIR/final_b" "http://127.0.0.1:{self.port}/slow-concurrent-b"
        ) &
        p2=$!

        wait $p1
        wait $p2

        echo "A:$(cat "$_AMSHA_RUN_DIR/final_a")"
        echo "B:$(cat "$_AMSHA_RUN_DIR/final_b")"
        """
        res = self.run_bash_snippet(snippet)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertIn("A:RESPONSE_A", res.stdout)
        self.assertIn("B:RESPONSE_B", res.stdout)


if __name__ == "__main__":
    unittest.main()
