#!/usr/bin/env python3
"""Contract tests for the distribution runtime wrappers.

These tests exercise the wrapper CLIs without requiring a native image, a live
server, credentials, an ONNX bundle, or network access.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


_lib_dir = Path(__file__).resolve().parents[1] / "lib"
if str(_lib_dir) not in sys.path:
    sys.path.insert(0, str(_lib_dir))
from repo_root import get_repo_root
ROOT = get_repo_root(__file__)
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
        environment.pop("AMSHA_ONNX_ENABLED", None)
        environment.pop("AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS", None)
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
                self.assertEqual(plan["embedding_providers"], "jina")

    def test_full_validation_is_distinct_from_fast_validation(self) -> None:
        plan = self.parse_plan(
            self.run_wrapper("sqlite", "--dry-run", "--validate", "--full")
        )
        self.assertEqual(plan["validation_mode"], "full")
        self.assertEqual(plan["validation_args"], "")
        validation_log = Path(plan["validation_log"])
        self.assertEqual(validation_log.parent, ROOT)
        self.assertRegex(validation_log.name, r"^amsha-sqlite-validation\.\d+\.log$")

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

    def test_no_argument_launch_reaches_the_binary(self) -> None:
        """A bare wrapper invocation must launch the server, not abort.

        Regression: macOS ships bash 3.2, where an *empty* array expanded as
        `"${ARR[@]}"` aborts under `set -u` with `unbound variable`.  The serve
        path's final exec expanded the (empty) passthrough array exactly that
        way, so `scripts/run_dist_sqlite.sh` with no arguments died before the
        binary was ever reached.
        """
        with tempfile.TemporaryDirectory() as directory:
            capture = Path(directory) / "argv.txt"
            fake_binary = Path(directory) / "amsha-fake"
            fake_binary.write_text(
                "#!/usr/bin/env bash\n"
                f'printf "%s\\n" "$@" > "{capture}"\n',
                encoding="utf-8",
            )
            fake_binary.chmod(0o755)

            result = self.run_wrapper(
                "sqlite",
                extra_env={
                    "AMSHA_DIST_BINARY": str(fake_binary),
                    "AMSHA_DATA_DIR": str(Path(directory) / "data"),
                },
            )

            self.assertNotIn("unbound variable", result.stderr)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(capture.read_text(encoding="utf-8").split(), ["serve"])

    def test_serve_path_exec_guards_the_empty_passthrough_array(self) -> None:
        """The final exec must use the portable empty-array guard.

        The behavioural test above only fails on an interpreter that actually
        rejects the unguarded construct, and that depends on which bash the
        `#!/usr/bin/env bash` shebang resolves to.  On a host where bash 5 is
        first on PATH the unguarded form is legal and the behavioural test would
        pass anyway, so assert the guard directly as well — a regression must not
        be able to hide behind a newer interpreter.
        """
        script = (ROOT / "scripts" / "distribution" / "run_dist_backend.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn('${PASSTHROUGH_ARGS[@]+"${PASSTHROUGH_ARGS[@]}"}', script)
        self.assertNotIn('exec "$BINARY" "$COMMAND" "${PASSTHROUGH_ARGS[@]}"', script)

    def test_clean_flag_is_reflected_in_plan(self) -> None:
        for backend in WRAPPERS:
            with self.subTest(backend=backend):
                plan = self.parse_plan(
                    self.run_wrapper(backend, "--dry-run", "--clean")
                )
                self.assertEqual(plan["clean"], "true")

    def test_full_validation_preflight_runs_before_clean(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            data_dir = Path(directory) / "data"
            data_dir.mkdir()
            sentinel = data_dir / "preserve-me"
            sentinel.write_text("untouched", encoding="utf-8")

            result = self.run_wrapper(
                "sqlite",
                "--validate",
                "--full",
                "--clean",
                extra_env={
                    "AMSHA_DATA_DIR": str(data_dir),
                    "AMSHA_LLM_PREFLIGHT_URL": "http://127.0.0.1:1/models",
                },
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("LLM preflight", result.stderr)
            self.assertTrue(sentinel.exists(), "preflight failure must happen before --clean")

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

    def test_onnx_flag_installs_both_embedding_and_reranker_bundles_when_missing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            directory_path = Path(directory)
            calls_file = directory_path / "mise-calls.log"
            fake_models_dir = directory_path / "models"
            fake_models_dir.mkdir()
            fake_bin_dir = directory_path / "bin"
            fake_bin_dir.mkdir()
            (fake_bin_dir / "mise").write_text(
                "#!/usr/bin/env bash\n"
                "printf '%s\\n' \"$*\" >> \"$AMSHA_MISE_CALLS_FILE\"\n"
                "if [[ \"$*\" == *\"models:install-mpnet\"* ]]; then\n"
                "  mkdir -p \"$AMSHA_ONNX_MODEL_ROOT/all-mpnet-base-v2-e8c3b32\"\n"
                "  touch \"$AMSHA_ONNX_MODEL_ROOT/all-mpnet-base-v2-e8c3b32/model.onnx\"\n"
                "fi\n"
                "if [[ \"$*\" == *\"models:install-bge-reranker\"* ]]; then\n"
                "  mkdir -p \"$AMSHA_ONNX_MODEL_ROOT/bge-reranker-v1\"\n"
                "  touch \"$AMSHA_ONNX_MODEL_ROOT/bge-reranker-v1/model.onnx\"\n"
                "fi\n",
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
                "--onnx",
                extra_env={
                    "TEST_BASE": "http://127.0.0.1:19999",
                    "AMSHA_VALIDATION_SCRIPT": str(validator),
                    "AMSHA_ONNX_MODEL_ROOT": str(fake_models_dir),
                    "AMSHA_MISE_CALLS_FILE": str(calls_file),
                    "PATH": f"{fake_bin_dir}:{os.environ['PATH']}",
                },
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue(calls_file.exists())
            calls = calls_file.read_text(encoding="utf-8").splitlines()
            self.assertIn("run models:install-mpnet", calls)
            self.assertIn("run models:install-bge-reranker", calls)

    def test_runner_and_validators_do_not_execute_or_echo_untrusted_data(self) -> None:
        runner = ROOT / "scripts" / "run_dist_backend.sh"
        self.assertNotIn("source ", runner.read_text(encoding="utf-8"))
        self.assertNotIn("eval ", runner.read_text(encoding="utf-8"))
        self.assertNotIn("kill -9", runner.read_text(encoding="utf-8"))
        self.assertNotIn("lsof -ti", runner.read_text(encoding="utf-8"))
        for validator in (ROOT / "rest-http" / "scripts").glob("validate_*.sh"):
            self.assertNotIn("head -c", validator.read_text(encoding="utf-8"), validator.name)

    def test_every_validator_is_registered_in_the_master_runner(self) -> None:
        """A validator no profile can reach is invisible coverage.

        The classifier control plane hid behind --onnx for exactly this reason:
        validate_onnx.sh was the only caller, so the default profile never ran it.
        """
        scripts_dir = ROOT / "rest-http" / "scripts"
        runner = (scripts_dir / "run_all_validations.sh").read_text(encoding="utf-8")
        on_disk = sorted(path.name for path in scripts_dir.glob("validate_*.sh"))
        self.assertTrue(on_disk, "no validator scripts found")
        assigned = set(re.findall(r"SCRIPT_DIR/(validate_[a-z0-9_]+\.sh)", runner))
        self.assertEqual(
            [name for name in on_disk if name not in assigned],
            [],
            "these validators have no *_SCRIPT assignment in run_all_validations.sh",
        )
        self.assertEqual(
            sorted(name for name in assigned if not (scripts_dir / name).is_file()),
            [],
            "run_all_validations.sh references validator scripts that do not exist",
        )

    def test_zero_token_decision_plane_validator_runs_in_the_fast_profile(self) -> None:
        """The decision-plane contract needs no model and no LLM, so it must not be opt-in."""
        runner = (ROOT / "rest-http" / "scripts" / "run_all_validations.sh").read_text(encoding="utf-8")
        fast_case = next(line for line in runner.splitlines() if line.strip().startswith("--fast)"))
        self.assertIn("RUN_DECISION_PLANE=true", fast_case)

    def test_decision_plane_validator_never_posts_evaluation_requests(self):
        validator = (ROOT / "rest-http" / "scripts" / "validate_decision_plane.sh").read_text(
            encoding="utf-8"
        )
        executable_lines = "\n".join(
            line for line in validator.splitlines() if not line.lstrip().startswith("#")
        )
        self.assertNotIn("evidence/run", executable_lines)
        self.assertNotIn("/api/v1/admin/decision/evaluate", executable_lines)
        self.assertNotIn("DECISION_EVALUATE_URL", executable_lines)
        self.assertNotIn("DECISION_EVAL_BODY", executable_lines)
        self.assertIn("Decision-evidence corpus v1 loaded:", validator)
        registry_sources = (
            ROOT
            / "core"
            / "src"
            / "main"
            / "java"
            / "com"
            / "amsha"
            / "core"
            / "classifier"
            / "evaluation"
            / "DecisionEvidenceCorpusRegistry.java",
            ROOT
            / "core"
            / "src"
            / "main"
            / "java"
            / "com"
            / "amsha"
            / "core"
            / "classifier"
            / "evaluation"
            / "DecisionPriceScheduleRegistry.java",
        )
        for source in registry_sources:
            self.assertIn("is not present on the classpath", source.read_text(encoding="utf-8"))
        self.assertIn("DecisionEvaluationDisabledPathIT", executable_lines)
        self.assertIn("DecisionEvaluationPathIT", executable_lines)
        self.assertIn("decision-evidence/corpus-v1.json is not present on the classpath", executable_lines)
        self.assertIn("decision-evidence/corpus-v3.json is not present on the classpath", executable_lines)
        self.assertIn("Decision-evidence corpus v3 loaded:", executable_lines)
        self.assertIn(
            "decision-evidence/price-schedule-v1.json is not present on the classpath",
            executable_lines,
        )

    def test_validation_run_always_sets_the_inert_plane_diagnostic_expectation(self) -> None:
        """Leaving the expectation unset makes the startup-diagnostic check vacuous.

        The validator reads an unset AMSHA_DECISION_PLANE_EXPECT_INERT_WARNING as "switches
        disabled", so a wrapper that does not derive it from the loaded configuration would
        pass even when the diagnostic is missing. It must therefore always be exported.
        """
        switches = {
            "AMSHA_DECISION_SHADOW_INTENT_ENABLED": "false",
            "AMSHA_DECISION_SHADOW_TOOL_RISK_ENABLED": "false",
            "AMSHA_DECISION_SHADOW_ANSWER_QUALITY_ENABLED": "false",
            "AMSHA_ROUTING_INTENT_DECISION_ROUTER_ENABLED": "false",
        }
        with tempfile.TemporaryDirectory() as directory:
            directory_path = Path(directory)
            captured = directory_path / "captured-expectation"
            validator = directory_path / "validator.sh"
            validator.write_text(
                "#!/usr/bin/env bash\n"
                'printf \'%s\\n\' "${AMSHA_DECISION_PLANE_EXPECT_INERT_WARNING:-UNSET}"'
                ' > "$AMSHA_CAPTURE_FILE"\n',
                encoding="utf-8",
            )
            validator.chmod(0o755)
            base_env = {
                "AMSHA_VALIDATION_SCRIPT": str(validator),
                "AMSHA_CAPTURE_FILE": str(captured),
                # An existing runtime: the wrapper runs the suite without owning a server.
                "TEST_BASE": "http://127.0.0.1:1",
                **switches,
            }

            for enabled, expected in (
                (None, "false"),
                ("AMSHA_DECISION_SHADOW_INTENT_ENABLED", "true"),
                ("AMSHA_ROUTING_INTENT_DECISION_ROUTER_ENABLED", "true"),
            ):
                extra_env = dict(base_env)
                if enabled:
                    extra_env[enabled] = "true"
                result = self.run_wrapper("sqlite", "--validate", "--fast", extra_env=extra_env)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(
                    captured.read_text(encoding="utf-8").strip(),
                    expected,
                    f"expectation wrong when {enabled or 'no switch'} is enabled",
                )

    def test_readiness_counter_counts_every_warning_record(self):
        validator = (
            ROOT / "rest-http" / "scripts" / "validate_decision_plane.sh"
        ).read_text(encoding="utf-8")
        count_line = next(
            line for line in validator.splitlines() if "COUNT=$(awk " in line
        )
        awk_program = count_line.split("awk '", 1)[1].split("' \"$LOG_FILE\")", 1)[0]
        self.assertEqual(
            "/DecisionPlaneReadiness/ { count++ } END { print count+0 }",
            awk_program,
        )

        console_record = (
            "2026-10-10 00:00:00,000 WARN "
            "[com.amsha.core.classifier.DecisionPlaneReadiness] readiness warning"
        )
        file_record = (
            "amsha-sqlite[10106] WARN "
            "[com.ams.cor.cla.DecisionPlaneReadiness] readiness warning"
        )

        def count_records(records):
            result = subprocess.run(
                ["awk", awk_program],
                input="".join(f"{record}\n" for record in records),
                text=True,
                capture_output=True,
                check=True,
            )
            return result.stdout.strip()

        self.assertEqual("1", count_records([file_record]))
        self.assertEqual("2", count_records([console_record, file_record]))
        self.assertEqual("2", count_records([file_record, file_record]))

    def test_validation_wrapper_separates_console_and_quarkus_logs(self):
        runner = (ROOT / "scripts" / "distribution" / "run_dist_backend.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn('console_log_file="${log_file}.console"', runner)
        self.assertIn('export QUARKUS_LOG_FILE_PATH="$log_file"', runner)
        self.assertIn('export AMSHA_VALIDATION_LOG_FILE="$log_file"', runner)
        self.assertIn('> "$console_log_file" 2>&1 &', runner)
        self.assertNotIn('> "$log_file" 2>&1 &', runner)

    def test_decision_switch_lookup_tolerates_unset_variables(self) -> None:
        """A clean checkout has no root .env, so the four decision switches are simply absent.

        run_dist_backend.sh runs under `set -u`, and an indirect lookup without an inline
        default aborts the whole run with "unbound variable" before any suite starts. The
        repository .env defines these keys, which masks the failure locally, so this runs the
        real wrapper from a project root that has no env files at all.
        """
        switch_names = (
            "AMSHA_DECISION_SHADOW_INTENT_ENABLED",
            "AMSHA_DECISION_SHADOW_TOOL_RISK_ENABLED",
            "AMSHA_DECISION_SHADOW_ANSWER_QUALITY_ENABLED",
            "AMSHA_ROUTING_INTENT_DECISION_ROUTER_ENABLED",
        )
        with tempfile.TemporaryDirectory() as directory:
            directory_path = Path(directory)
            project_root = directory_path / "project"
            shutil.copytree(ROOT / "scripts", project_root / "scripts")
            captured = directory_path / "captured-expectation"
            validator = directory_path / "validator.sh"
            validator.write_text(
                "#!/usr/bin/env bash\n"
                'printf \'%s\\n\' "${AMSHA_DECISION_PLANE_EXPECT_INERT_WARNING:-UNSET}"'
                ' > "$AMSHA_CAPTURE_FILE"\n',
                encoding="utf-8",
            )
            validator.chmod(0o755)
            environment = os.environ.copy()
            for name in (
                "AMSHA_DATA_DIR",
                "AMSHA_ONNX_MODEL_ROOT",
                "AMSHA_ONNX_ENABLED",
                "AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS",
                "AMSHA_PERSISTENCE_BACKEND",
                *switch_names,
            ):
                environment.pop(name, None)
            environment.update(
                {
                    "AMSHA_VALIDATION_SCRIPT": str(validator),
                    "AMSHA_CAPTURE_FILE": str(captured),
                    # An existing runtime: the wrapper runs the suite without owning a server.
                    "TEST_BASE": "http://127.0.0.1:1",
                }
            )
            result = subprocess.run(
                [str(project_root / "scripts" / "run_dist_sqlite.sh"), "--validate", "--fast"],
                cwd=project_root,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
                timeout=60,
            )
            self.assertEqual(
                result.returncode,
                0,
                f"an absent decision switch must read as disabled, not abort: {result.stderr}",
            )
            self.assertEqual(captured.read_text(encoding="utf-8").strip(), "false")

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
            fake_env_file = directory_path / "runtime-env"
            fake_bin_dir = directory_path / "bin"
            fake_bin_dir.mkdir()
            args_file = directory_path / "validation-args"
            validator = directory_path / "validator.sh"
            (fake_bin_dir / "curl").write_text(
                "#!/usr/bin/env bash\n"
                "for _ in $(seq 1 500); do\n"
                "  [[ -f \"$AMSHA_FAKE_ENV_FILE\" ]] && exit 0\n"
                "  sleep 0.01\n"
                "done\n"
                "exit 1\n",
                encoding="utf-8",
            )
            (fake_bin_dir / "curl").chmod(0o755)
            binary.write_text(
                "#!/usr/bin/env bash\n"
                "if [[ \"$1\" == serve ]]; then\n"
                "  echo $$ > \"$AMSHA_FAKE_PID_FILE\"\n"
                "  printf '%s\\n' \"$AMSHA_UI_SKIP_ONBOARDING\" \"$AMSHA_ONNX_ENABLED\" \"$AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS\" \"$QUARKUS_HTTP_PORT\" > \"$AMSHA_FAKE_ENV_FILE\"\n"
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
                    "AMSHA_FAKE_ENV_FILE": str(fake_env_file),
                    "AMSHA_VALIDATION_PORT": "18765",
                    "AMSHA_VALIDATION_SCRIPT": str(validator),
                    "AMSHA_TEST_ARGS_FILE": str(args_file),
                    "AMSHA_VALIDATION_LOG_FILE": str(directory_path / "server.log"),
                    "PATH": f"{fake_bin_dir}:{os.environ['PATH']}",
                },
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(args_file.read_text(encoding="utf-8").splitlines(), ["--fast"])
            self.assertEqual(
                fake_env_file.read_text(encoding="utf-8").splitlines(),
                ["true", "false", "jina", "18765"],
            )
            fake_pid = int(fake_pid_file.read_text(encoding="utf-8"))
            with self.assertRaises(ProcessLookupError):
                os.kill(fake_pid, 0)

    def test_mise_task_permissions_and_twins(self) -> None:
        dist_task_dir = ROOT / ".mise" / "tasks" / "dist"
        expected_dist_tasks = [
            "serve.ps1",
            "serve-sqlite.ps1",
            "serve-pg.ps1",
            "serve-fs.ps1",
            "validate-sqlite.ps1",
            "validate-pg.ps1",
            "validate-fs.ps1",
        ]
        for task_name in expected_dist_tasks:
            task_path = dist_task_dir / task_name
            with self.subTest(task=task_name):
                self.assertTrue(task_path.is_file(), f"Task file {task_path} does not exist")
                self.assertTrue(
                    os.access(task_path, os.X_OK),
                    f"Task file {task_name} missing executable bit (required for Unix mise discovery)",
                )

        smoke_dir = ROOT / ".mise" / "tasks" / "smoke"
        sh_task = smoke_dir / "native-matrix.sh"
        ps1_task = smoke_dir / "native-matrix.ps1"
        self.assertTrue(sh_task.is_file(), "smoke/native-matrix.sh missing")
        self.assertTrue(
            os.access(sh_task, os.X_OK),
            "smoke/native-matrix.sh missing executable bit for Unix mise discovery",
        )
        self.assertTrue(ps1_task.is_file(), "smoke/native-matrix.ps1 missing")
        ps1_content = ps1_task.read_text(encoding="utf-8")
        self.assertIn("pwsh", ps1_content)
        self.assertIn("verify_native_matrix.ps1", ps1_content)


if __name__ == "__main__":
    unittest.main()
