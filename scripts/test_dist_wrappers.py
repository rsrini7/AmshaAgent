#!/usr/bin/env python3
"""Contract tests for the distribution runtime wrappers.

These tests exercise the wrapper CLIs without requiring a native image, a live
server, credentials, an ONNX bundle, or network access.
"""

from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WRAPPERS = {
    "sqlite": ROOT / "scripts" / "run_dist_sqlite.sh",
    "postgres": ROOT / "scripts" / "run_dist_pg.sh",
    "fs": ROOT / "scripts" / "run_dist_fs.sh",
}


class DistributionWrapperContractTest(unittest.TestCase):
    def run_wrapper(
        self,
        backend: str,
        *arguments: str,
        extra_env: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment.pop("AMSHA_DATA_DIR", None)
        environment.pop("AMSHA_ONNX_MODEL_ROOT", None)
        environment.pop("AMSHA_PERSISTENCE_BACKEND", None)
        if extra_env:
            environment.update(extra_env)
        return subprocess.run(
            [str(WRAPPERS[backend]), *arguments],
            cwd=ROOT,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
            timeout=30,
        )

    def parse_plan(self, result: subprocess.CompletedProcess[str]) -> dict[str, str]:
        self.assertEqual(result.returncode, 0, result.stderr)
        plan: dict[str, str] = {}
        for line in result.stdout.splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                plan[key] = value
        return plan

    def test_fast_validation_is_explicit_and_does_not_need_a_binary(self) -> None:
        for backend in WRAPPERS:
            with self.subTest(backend=backend):
                plan = self.parse_plan(
                    self.run_wrapper(backend, "--dry-run", "--validate", "--fast")
                )
                self.assertEqual(plan["validation_mode"], "fast")
                self.assertEqual(plan["validation_args"], "--fast")

    def test_full_validation_is_distinct_from_fast_validation(self) -> None:
        plan = self.parse_plan(
            self.run_wrapper("sqlite", "--dry-run", "--validate", "--full")
        )
        self.assertEqual(plan["validation_mode"], "full")
        self.assertEqual(plan["validation_args"], "")

    def test_backend_storage_contracts_are_isolated(self) -> None:
        for backend in WRAPPERS:
            with self.subTest(backend=backend):
                plan = self.parse_plan(self.run_wrapper(backend, "--dry-run"))
                data_dir = Path(plan["data_dir"])
                self.assertEqual(plan["backend"], backend)
                self.assertTrue(str(data_dir).endswith(f"data-{backend}"))
                if backend == "fs":
                    self.assertEqual(
                        Path(plan["fs_data_dir"]), data_dir / "filesystem"
                    )
                    self.assertEqual(
                        Path(plan["fs_lucene_index_dir"]), data_dir / "lucene-index"
                    )

    def test_build_only_defaults_to_serve(self) -> None:
        plan = self.parse_plan(self.run_wrapper("sqlite", "--dry-run", "--build"))
        self.assertEqual(plan["command"], "serve")

    def test_safe_env_loader_does_not_execute_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "should-not-exist"
            env_file = Path(directory) / ".env"
            env_file.write_text(
                "AMSHA_DATA_DIR=./data/<random>\n"
                f"AMSHA_TEST_VALUE=$(touch {marker})\n",
                encoding="utf-8",
            )
            plan = self.parse_plan(
                self.run_wrapper(
                    "sqlite",
                    "--dry-run",
                    extra_env={"AMSHA_ENV_FILE": str(env_file)},
                )
            )
            self.assertFalse(marker.exists())
            self.assertTrue(plan["data_dir"].endswith("data/<random>"))

    def test_fast_validation_argument_reaches_existing_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory_path = Path(directory)
            args_file = directory_path / "validation-args"
            validator = directory_path / "validator.sh"
            validator.write_text(
                "#!/usr/bin/env bash\n"
                "printf '%s\\n' \"$@\" > \"$AMSHA_TEST_ARGS_FILE\"\n",
                encoding="utf-8",
            )
            validator.chmod(0o755)
            result = self.run_wrapper(
                "sqlite",
                "--validate",
                "--fast",
                extra_env={
                    "TEST_BASE": "http://127.0.0.1:19999",
                    "AMSHA_VALIDATION_SCRIPT": str(validator),
                    "AMSHA_TEST_ARGS_FILE": str(args_file),
                },
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(args_file.read_text(encoding="utf-8").splitlines(), ["--fast"])

    def test_normal_validation_does_not_install_onnx(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory_path = Path(directory)
            marker = directory_path / "mise-was-called"
            fake_bin_dir = directory_path / "bin"
            fake_bin_dir.mkdir()
            (fake_bin_dir / "mise").write_text(
                "#!/usr/bin/env bash\n"
                "touch \"$AMSHA_MISE_MARKER\"\n",
                encoding="utf-8",
            )
            (fake_bin_dir / "mise").chmod(0o755)
            validator = directory_path / "validator.sh"
            validator.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            validator.chmod(0o755)

            result = self.run_wrapper(
                "sqlite",
                "--validate",
                "--fast",
                extra_env={
                    "TEST_BASE": "http://127.0.0.1:19999",
                    "AMSHA_VALIDATION_SCRIPT": str(validator),
                    "AMSHA_MISE_MARKER": str(marker),
                    "PATH": f"{fake_bin_dir}:{os.environ['PATH']}",
                },
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse(marker.exists())

    def test_runner_and_validators_do_not_execute_or_echo_untrusted_data(self) -> None:
        runner = ROOT / "scripts" / "run_dist_backend.sh"
        self.assertNotIn("source ", runner.read_text(encoding="utf-8"))
        self.assertNotIn("eval ", runner.read_text(encoding="utf-8"))
        self.assertNotIn("kill -9", runner.read_text(encoding="utf-8"))
        self.assertNotIn("lsof -ti", runner.read_text(encoding="utf-8"))
        for validator in (ROOT / "rest-http" / "scripts").glob("validate_*.sh"):
            self.assertNotIn("head -c", validator.read_text(encoding="utf-8"), validator.name)

    def test_mise_adapters_keep_backend_builds_and_fast_validation_separate(self) -> None:
        dist_tasks = ROOT / ".mise" / "tasks" / "dist"
        for backend in ("sqlite", "pg", "fs"):
            serve = (dist_tasks / f"serve-{backend}.ps1").read_text(encoding="utf-8")
            validate = (dist_tasks / f"validate-{backend}.ps1").read_text(encoding="utf-8")
            self.assertNotIn("dist:all", serve)
            self.assertIn("--validate --fast", validate)

    def test_owned_validation_server_is_cleaned_up(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory_path = Path(directory)
            binary = directory_path / "fake-amsha"
            fake_pid_file = directory_path / "fake-pid"
            fake_bin_dir = directory_path / "bin"
            fake_bin_dir.mkdir()
            args_file = directory_path / "validation-args"
            validator = directory_path / "validator.sh"
            (fake_bin_dir / "curl").write_text(
                "#!/usr/bin/env bash\n"
                "exit 0\n",
                encoding="utf-8",
            )
            (fake_bin_dir / "curl").chmod(0o755)
            binary.write_text(
                "#!/usr/bin/env bash\n"
                "if [[ \"$1\" == serve ]]; then\n"
                "  echo $$ > \"$AMSHA_FAKE_PID_FILE\"\n"
                "  trap 'exit 0' TERM INT\n"
                "  while true; do sleep 1; done\n"
                "fi\n"
                "exit 0\n",
                encoding="utf-8",
            )
            binary.chmod(0o755)
            validator.write_text(
                "#!/usr/bin/env bash\n"
                "printf '%s\\n' \"$@\" > \"$AMSHA_TEST_ARGS_FILE\"\n",
                encoding="utf-8",
            )
            validator.chmod(0o755)

            result = self.run_wrapper(
                "sqlite",
                "--validate",
                "--fast",
                extra_env={
                    "AMSHA_DIST_BINARY": str(binary),
                    "AMSHA_FAKE_PID_FILE": str(fake_pid_file),
                    "AMSHA_VALIDATION_PORT": "18765",
                    "AMSHA_VALIDATION_SCRIPT": str(validator),
                    "AMSHA_TEST_ARGS_FILE": str(args_file),
                    "PATH": f"{fake_bin_dir}:{os.environ['PATH']}",
                },
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(args_file.read_text(encoding="utf-8").splitlines(), ["--fast"])
            fake_pid = int(fake_pid_file.read_text(encoding="utf-8"))
            with self.assertRaises(ProcessLookupError):
                os.kill(fake_pid, 0)


if __name__ == "__main__":
    unittest.main()
