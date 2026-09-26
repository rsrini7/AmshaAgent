#!/usr/bin/env python3
"""Regression tests for the FQN import cleaner."""

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("fix_fqns.py")
SPEC = importlib.util.spec_from_file_location("fix_fqns", SCRIPT)
FIX_FQNS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIX_FQNS)


class FixFqnsTests(unittest.TestCase):
    def test_same_name_supertype_keeps_fqn_without_generator_comment(self):
        source = """package com.amsha.security.http;

public class OutboundHttpException extends com.amsha.core.security.http.OutboundHttpException {
    public OutboundHttpException() { super(Category.POLICY_DENIED, \"policy\"); }
}
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            java_file = Path(temp_dir) / "OutboundHttpException.java"
            java_file.write_text(source, encoding="utf-8")

            self.assertFalse(FIX_FQNS.process_file(str(java_file)))
            normalized = java_file.read_text(encoding="utf-8")
            self.assertNotIn("fully qualified to disambiguate", normalized)
            self.assertNotIn("keep FQN:", normalized)
            self.assertIn(
                "extends com.amsha.core.security.http.OutboundHttpException",
                normalized,
            )
            self.assertFalse(FIX_FQNS.process_file(str(java_file)))

    def test_legacy_comment_is_removed_without_changing_the_fqn(self):
        source = """package com.amsha.security.http;

public class OutboundHttpException extends com.amsha.core.security.http.OutboundHttpException { // fully qualified to disambiguate
}
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            java_file = Path(temp_dir) / "OutboundHttpException.java"
            java_file.write_text(source, encoding="utf-8")

            self.assertTrue(FIX_FQNS.process_file(str(java_file)))
            normalized = java_file.read_text(encoding="utf-8")
            self.assertEqual(normalized.count("OutboundHttpException"), 2)
            self.assertNotIn("keep FQN", normalized)
            self.assertNotIn("fully qualified to disambiguate", normalized)
            self.assertFalse(FIX_FQNS.process_file(str(java_file)))

    def test_caffeine_special_case_keeps_fqn_without_comment(self):
        source = """package example;

public class UsesCaffeine {
    com.github.benmanes.caffeine.cache.RemovalCause cause;
}
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            java_file = Path(temp_dir) / "UsesCaffeine.java"
            java_file.write_text(source, encoding="utf-8")

            self.assertFalse(FIX_FQNS.process_file(str(java_file)))
            normalized = java_file.read_text(encoding="utf-8")
            self.assertIn("com.github.benmanes.caffeine.cache.RemovalCause", normalized)
            self.assertNotIn("fully qualified to disambiguate", normalized)
            self.assertFalse(FIX_FQNS.process_file(str(java_file)))


if __name__ == "__main__":
    unittest.main()
