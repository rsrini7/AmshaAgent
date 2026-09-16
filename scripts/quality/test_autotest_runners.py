#!/usr/bin/env python3
"""Contract tests for autotest runner scripts, shims, and repo root resolution.

Verifies:
- scripts/autotest/run.sh dry-run across all backends, suites, and row ranges.
- All 8 forwarding shims in scripts/autotest/.
- Root forwarding shim scripts/autotest.sh.
- Invocation from external working directory (e.g. /tmp).
- Python repo_root discovery for scripts/autotest/autotest.py from external working directory.
- Input validation and error handling for invalid backends, suites, and flags.
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

_LIB_DIR = Path(__file__).resolve().parents[1] / "lib"
if str(_LIB_DIR) not in sys.path:
    sys.path.insert(0, str(_LIB_DIR))
from repo_root import get_repo_root

ROOT = get_repo_root(__file__)
AUTOTEST_DIR = ROOT / "scripts" / "autotest"
RUN_SH = AUTOTEST_DIR / "run.sh"
TOP_LEVEL_SH = ROOT / "scripts" / "autotest.sh"


class AutotestRunnerContractTest(unittest.TestCase):
    def run_cmd(
        self,
        cmd: list[str],
        cwd: Path | str | None = None,
        extra_env: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env["AUTOTEST_DRY_RUN"] = "true"
        if extra_env:
            env.update(extra_env)
        return subprocess.run(
            cmd,
            cwd=str(cwd) if cwd else str(ROOT),
            env=env,
            text=True,
            capture_output=True,
            check=False,
            timeout=15,
        )

    def parse_dry_run_output(self, stdout: str) -> dict[str, str]:
        fields: dict[str, str] = {}
        for line in stdout.splitlines():
            line = line.strip()
            if "=" in line and not line.startswith("#"):
                key, value = line.split("=", 1)
                fields[key] = value
        return fields

    def test_run_sh_defaults(self) -> None:
        proc = self.run_cmd(["bash", str(RUN_SH)])
        self.assertEqual(proc.returncode, 0, f"Stderr: {proc.stderr}")
        fields = self.parse_dry_run_output(proc.stdout)
        self.assertEqual(fields.get("AUTOTEST_DRY_RUN"), "true")
        self.assertEqual(fields.get("BACKEND"), "sqlite")
        self.assertEqual(fields.get("SUITE"), "telegram")
        self.assertEqual(fields.get("ROWS"), "all")
        self.assertEqual(fields.get("PORT"), "8999")
        self.assertEqual(fields.get("EXTRA_ARGS"), "")
        self.assertTrue(fields.get("INPUT_FILE", "").endswith("telegram-test-amsha.txt"))
        self.assertTrue(fields.get("RESULTS_FILE", "").endswith("results.json"))
        self.assertTrue(fields.get("LOG_FILE", "").endswith("run.log"))
        self.assertTrue(fields.get("TARGET_SCRIPT", "").endswith("autotest.py"))
        command = fields.get("COMMAND", "")
        self.assertTrue(command.startswith("python3 "), f"Command should start with python3: {command}")
        self.assertIn("--port 8999", command)
        self.assertIn("--backend sqlite", command)
        self.assertIn("telegram-test-amsha.txt", command)

    def test_run_sh_backend_pg(self) -> None:
        proc = self.run_cmd(["bash", str(RUN_SH), "--backend", "pg"])
        self.assertEqual(proc.returncode, 0, f"Stderr: {proc.stderr}")
        fields = self.parse_dry_run_output(proc.stdout)
        self.assertEqual(fields.get("BACKEND"), "pg")
        self.assertTrue(fields.get("RESULTS_FILE", "").endswith("results-pg.json"))
        self.assertTrue(fields.get("LOG_FILE", "").endswith("run-pg.log"))
        self.assertIn("--backend pg", fields.get("COMMAND", ""))

    def test_run_sh_backend_fs(self) -> None:
        proc = self.run_cmd(["bash", str(RUN_SH), "--backend", "fs"])
        self.assertEqual(proc.returncode, 0, f"Stderr: {proc.stderr}")
        fields = self.parse_dry_run_output(proc.stdout)
        self.assertEqual(fields.get("BACKEND"), "fs")
        self.assertTrue(fields.get("RESULTS_FILE", "").endswith("results-fs.json"))
        self.assertTrue(fields.get("LOG_FILE", "").endswith("run-fs.log"))
        self.assertIn("--backend fs", fields.get("COMMAND", ""))

    def test_run_sh_suite_skillsjars(self) -> None:
        proc = self.run_cmd(["bash", str(RUN_SH), "--suite", "skillsjars"])
        self.assertEqual(proc.returncode, 0, f"Stderr: {proc.stderr}")
        fields = self.parse_dry_run_output(proc.stdout)
        self.assertEqual(fields.get("SUITE"), "skillsjars")
        self.assertTrue(fields.get("INPUT_FILE", "").endswith("skillsjars-test.txt"))
        self.assertTrue(fields.get("RESULTS_FILE", "").endswith("results-skillsjars.json"))
        self.assertTrue(fields.get("LOG_FILE", "").endswith("run-skillsjars.log"))
        self.assertIn("skillsjars-test.txt", fields.get("COMMAND", ""))

    def test_run_sh_suite_loka(self) -> None:
        proc = self.run_cmd(["bash", str(RUN_SH), "--suite", "loka"])
        self.assertEqual(proc.returncode, 0, f"Stderr: {proc.stderr}")
        fields = self.parse_dry_run_output(proc.stdout)
        self.assertEqual(fields.get("SUITE"), "loka")
        self.assertTrue(fields.get("INPUT_FILE", "").endswith("loka-connectivity-test.txt"))
        self.assertTrue(fields.get("RESULTS_FILE", "").endswith("results-loka.json"))
        self.assertTrue(fields.get("LOG_FILE", "").endswith("run-loka.log"))
        self.assertIn("loka-connectivity-test.txt", fields.get("COMMAND", ""))

    def test_run_sh_rows_and_port(self) -> None:
        proc = self.run_cmd(["bash", str(RUN_SH), "--rows", "1-20", "--port", "9099"])
        self.assertEqual(proc.returncode, 0, f"Stderr: {proc.stderr}")
        fields = self.parse_dry_run_output(proc.stdout)
        self.assertEqual(fields.get("ROWS"), "1-20")
        self.assertEqual(fields.get("PORT"), "9099")
        self.assertIn("-r 1-20", fields.get("COMMAND", ""))
        self.assertIn("--port 9099", fields.get("COMMAND", ""))

    def test_top_level_shim(self) -> None:
        proc = self.run_cmd(["bash", str(TOP_LEVEL_SH), "--backend", "pg", "--rows", "5-10"])
        self.assertEqual(proc.returncode, 0, f"Stderr: {proc.stderr}")
        fields = self.parse_dry_run_output(proc.stdout)
        self.assertEqual(fields.get("BACKEND"), "pg")
        self.assertEqual(fields.get("ROWS"), "5-10")
        self.assertIn("--backend pg", fields.get("COMMAND", ""))
        self.assertIn("-r 5-10", fields.get("COMMAND", ""))

    def test_all_eight_shims(self) -> None:
        expected = {
            "autotest-sqlite.sh": {"BACKEND": "sqlite", "SUITE": "telegram", "ROWS": "all"},
            "autotest-pg.sh": {"BACKEND": "pg", "SUITE": "telegram", "ROWS": "all"},
            "autotest-fs.sh": {"BACKEND": "fs", "SUITE": "telegram", "ROWS": "all"},
            "autotest-sqlite-rows.sh": {"BACKEND": "sqlite", "SUITE": "telegram", "ROWS": "1-20"},
            "autotest-pg-rows.sh": {"BACKEND": "pg", "SUITE": "telegram", "ROWS": "1-20"},
            "autotest-fs-rows.sh": {"BACKEND": "fs", "SUITE": "telegram", "ROWS": "1-20"},
            "autotest-skillsjars.sh": {"BACKEND": "sqlite", "SUITE": "skillsjars", "ROWS": "all"},
            "autotest-loka.sh": {"BACKEND": "sqlite", "SUITE": "loka", "ROWS": "all"},
        }
        for shim_name, exp_fields in expected.items():
            shim_path = AUTOTEST_DIR / shim_name
            self.assertTrue(shim_path.is_file(), f"Missing shim: {shim_path}")
            proc = self.run_cmd(["bash", str(shim_path)])
            self.assertEqual(proc.returncode, 0, f"Shim {shim_name} failed: {proc.stderr}")
            fields = self.parse_dry_run_output(proc.stdout)
            for k, v in exp_fields.items():
                self.assertEqual(fields.get(k), v, f"Shim {shim_name} mismatch for {k}")

    def test_invocation_from_external_directory(self) -> None:
        external_dir = Path(tempfile.gettempdir())
        proc = self.run_cmd(["bash", str(RUN_SH), "--backend", "sqlite"], cwd=external_dir)
        self.assertEqual(proc.returncode, 0, f"Stderr: {proc.stderr}")
        fields = self.parse_dry_run_output(proc.stdout)
        self.assertEqual(fields.get("BACKEND"), "sqlite")
        self.assertEqual(fields.get("AUTOTEST_DRY_RUN"), "true")

        proc_top = self.run_cmd(["bash", str(TOP_LEVEL_SH), "--suite", "skillsjars"], cwd=external_dir)
        self.assertEqual(proc_top.returncode, 0, f"Stderr: {proc_top.stderr}")
        fields_top = self.parse_dry_run_output(proc_top.stdout)
        self.assertEqual(fields_top.get("SUITE"), "skillsjars")

    def test_python_repo_root_discovery_from_external_directory(self) -> None:
        external_dir = Path(tempfile.gettempdir())
        autotest_py_path = AUTOTEST_DIR / "autotest.py"
        self.assertTrue(autotest_py_path.is_file())

        # Test in Python process running with cwd = external_dir
        cmd = [
            sys.executable,
            "-c",
            (
                "import sys, pathlib; "
                f"lib_dir = pathlib.Path({str(AUTOTEST_DIR)!r}).parents[0] / 'lib'; "
                "sys.path.insert(0, str(lib_dir)); "
                "from repo_root import get_repo_root; "
                f"target = pathlib.Path({str(autotest_py_path)!r}); "
                "print(get_repo_root(target))"
            ),
        ]
        proc = subprocess.run(cmd, cwd=str(external_dir), text=True, capture_output=True, check=False)
        self.assertEqual(proc.returncode, 0, f"Error: {proc.stderr}")
        resolved_root = Path(proc.stdout.strip())
        self.assertEqual(resolved_root, ROOT)

    def test_run_sh_help(self) -> None:
        proc = self.run_cmd(["bash", str(RUN_SH), "--help"])
        self.assertEqual(proc.returncode, 0)
        self.assertIn("Usage:", proc.stdout)
        self.assertIn("--backend", proc.stdout)
        self.assertIn("--suite", proc.stdout)

    def test_invalid_backend(self) -> None:
        proc = self.run_cmd(["bash", str(RUN_SH), "--backend", "oracle"])
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("Unknown backend", proc.stderr)

    def test_invalid_suite(self) -> None:
        proc = self.run_cmd(["bash", str(RUN_SH), "--suite", "unknown_suite"])
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("Unknown suite", proc.stderr)

    def test_extra_argument_forwarding(self) -> None:
        # 1. Forwarding via run.sh directly
        proc = self.run_cmd(
            ["bash", str(RUN_SH), "--backend", "sqlite", "--fail-fast", "--delay", "3", "--role", "coordinator"]
        )
        self.assertEqual(proc.returncode, 0, f"Stderr: {proc.stderr}")
        fields = self.parse_dry_run_output(proc.stdout)
        self.assertEqual(fields.get("EXTRA_ARGS"), "--fail-fast --delay 3 --role coordinator")
        command = fields.get("COMMAND", "")
        self.assertTrue(command.endswith("--fail-fast --delay 3 --role coordinator"), command)

        # 2. Forwarding via shim
        shim_path = AUTOTEST_DIR / "autotest-sqlite.sh"
        proc_shim = self.run_cmd(["bash", str(shim_path), "--fail-fast", "--autonomy", "full"])
        self.assertEqual(proc_shim.returncode, 0, f"Stderr: {proc_shim.stderr}")
        fields_shim = self.parse_dry_run_output(proc_shim.stdout)
        self.assertEqual(fields_shim.get("EXTRA_ARGS"), "--fail-fast --autonomy full")
        self.assertTrue(fields_shim.get("COMMAND", "").endswith("--fail-fast --autonomy full"))

        # 3. Forwarding via top-level shim
        proc_top = self.run_cmd(["bash", str(TOP_LEVEL_SH), "--skip-categories", "security,cron"])
        self.assertEqual(proc_top.returncode, 0, f"Stderr: {proc_top.stderr}")
        fields_top = self.parse_dry_run_output(proc_top.stdout)
        self.assertEqual(fields_top.get("EXTRA_ARGS"), "--skip-categories security,cron")
        self.assertTrue(fields_top.get("COMMAND", "").endswith("--skip-categories security,cron"))

    def test_autotest_py_rejects_unrecognized_arguments(self) -> None:
        autotest_py = AUTOTEST_DIR / "autotest.py"
        proc = subprocess.run(
            [sys.executable, str(autotest_py), "--bogus-flag-12345"],
            cwd=str(ROOT),
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("unrecognized arguments", proc.stderr)


if __name__ == "__main__":
    unittest.main()
