#!/usr/bin/env python3
"""Focused security and evidence tests for freeze_sarvam_static."""

from __future__ import annotations

import hashlib
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

# Keep direct execution importable while preserving module-mode execution.
SCRIPT_ROOT = Path(__file__).resolve().parents[1]
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

from scripts import freeze_sarvam_static as freezer


class FreezeStaticTests(unittest.TestCase):
    def test_authorized_version_is_fixed(self) -> None:
        self.assertEqual(freezer.AUTHORIZED_VERSION, "0.1.30")
        with patch.object(sys, "argv", ["freeze_sarvam_static.py", "0.1.31"]):
            with self.assertRaises(freezer.FreezeError):
                freezer.main()

    def test_marker_parser_handles_parentheses_and_extras(self) -> None:
        requirements = freezer.parse_requirements(
            [
                "httpx (>=0.21.2)",
                'typing_extensions>=4.5; python_version < "3.13"',
                'exceptiongroup>=1.0; python_version < "3.11"',
                'trio>=1.0; extra == "trio"',
            ]
        )
        self.assertEqual(requirements, [("httpx", ">=0.21.2"), ("typing-extensions", ">=4.5")])

    def test_filename_policy_rejects_traversal_and_windows_edges(self) -> None:
        for value in ("../artifact.whl", "C:artifact.whl", "foo:bar.whl", "CON", "artifact.whl "):
            with self.subTest(value=value), self.assertRaises(freezer.FreezeError):
                freezer.safe_filename(value)
        self.assertEqual(freezer.safe_filename("sarvamai-0.1.30-py3-none-any.whl"), "sarvamai-0.1.30-py3-none-any.whl")

    def test_redirect_is_rejected(self) -> None:
        request = freezer.urllib.request.Request("https://pypi.org/pypi/sarvamai/json")
        with self.assertRaises(freezer.FreezeError):
            freezer.NoRedirect().redirect_request(request, None, 302, "redirect", {}, "https://evil.invalid/")

    def test_existing_digest_mismatch_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "artifact.whl"
            target.write_bytes(b"not-the-pinned-artifact")
            with self.assertRaises(freezer.FreezeError):
                freezer.download_artifact("https://files.pythonhosted.org/artifact.whl", target, "0" * 64)

    def test_missing_digest_fails_closed_before_network(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "artifact.whl"
            with self.assertRaises(freezer.FreezeError):
                freezer.download_artifact("https://files.pythonhosted.org/artifact.whl", target, None)

    def test_partial_file_is_removed_after_download_failure(self) -> None:
        class ExplodingResponse:
            def __enter__(self):
                return self

            def __exit__(self, exception_type, exception, traceback):
                return False

            def read(self, size):
                raise OSError("synthetic stream failure")

        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "artifact.whl"
            with patch.object(freezer, "open_checked", return_value=ExplodingResponse()):
                with self.assertRaises(OSError):
                    freezer.download_artifact("https://files.pythonhosted.org/artifact.whl", target, "0" * 64)
            self.assertFalse(target.exists())
            self.assertFalse(target.with_name(target.name + ".partial").exists())

    def test_osv_malformed_and_short_responses_are_unresolved(self) -> None:
        record = [{"name": "sarvamai", "version": "0.1.30"}]
        for payload in ([], {"results": []}, {"results": ["bad"]}):
            with self.subTest(payload=payload):
                stream = io.StringIO(json.dumps(payload))
                with patch.object(freezer, "open_checked", return_value=stream):
                    result = freezer.query_osv(record)
                self.assertEqual(result["status"], "unresolved")

    def test_archive_licence_content_and_digest_are_captured(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            wheel = Path(directory) / "fixture-1.0-py3-none-any.whl"
            licence = b"Fixture licence\n"
            with zipfile.ZipFile(wheel, "w") as archive:
                archive.writestr("fixture-1.0.dist-info/METADATA", "Name: fixture\nVersion: 1.0\n")
                archive.writestr("fixture-1.0.dist-info/licenses/LICENSE", licence)
            _, _, _, records = freezer.archive_metadata(wheel)
            self.assertEqual(records[0]["content"], licence.decode())
            self.assertEqual(records[0]["sha256"], hashlib.sha256(licence).hexdigest())

    def test_scope_uses_evidence_cache_and_three_native_targets(self) -> None:
        self.assertIn(".scratch", str(freezer.DEFAULT_CACHE))
        self.assertNotIn("macosx_10_12_x86_64.whl", freezer.NATIVE_SUFFIXES)
        self.assertEqual(len(freezer.NATIVE_SUFFIXES), 3)


if __name__ == "__main__":
    unittest.main()
