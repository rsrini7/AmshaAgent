# Amsha Agent Autotest

## Usage

```bash
# Unified parameterized runner
scripts/autotest.sh --backend sqlite
scripts/autotest/run.sh --backend sqlite
scripts/autotest/run.sh --backend pg --rows 1-20
scripts/autotest/run.sh --suite skillsjars
scripts/autotest/run.sh --suite loka

# SQLite (default) — full run via Python directly
python3 scripts/autotest/autotest.py -f scripts/autotest/files/telegram-test-amsha.txt -j results.json -l run.log -v --backend sqlite

# PostgreSQL — auto-starts Podman container
python3 scripts/autotest/autotest.py -f scripts/autotest/files/telegram-test-amsha.txt -j results-pg.json -l run-pg.log -v --backend pg

# Filesystem — no database needed
python3 scripts/autotest/autotest.py -f scripts/autotest/files/telegram-test-amsha.txt -j results-fs.json -l run-fs.log -v --backend fs

# Quick sanity: first 20 cases, fail-fast
python3 scripts/autotest/autotest.py -r 1-20 --fail-fast --backend sqlite

# Skip heavy browser/code-exec tests
python3 scripts/autotest/autotest.py --skip-categories browse,code_exec -j quick.json --backend sqlite
```

## Shell Wrappers

The following wrappers forward to `scripts/autotest/run.sh`:

| Script | Backend | Rows | Suite |
|---|---|---|---|
| `scripts/autotest/autotest-sqlite.sh` | SQLite | All | Telegram |
| `scripts/autotest/autotest-pg.sh` | PostgreSQL | All | Telegram |
| `scripts/autotest/autotest-fs.sh` | Filesystem | All | Telegram |
| `scripts/autotest/autotest-sqlite-rows.sh` | SQLite | 1-20 | Telegram |
| `scripts/autotest/autotest-pg-rows.sh` | PostgreSQL | 1-20 | Telegram |
| `scripts/autotest/autotest-fs-rows.sh` | Filesystem | 1-20 | Telegram |
| `scripts/autotest/autotest-skillsjars.sh` | SQLite | All | SkillsJars |
| `scripts/autotest/autotest-loka.sh` | SQLite | All | Loka |

## Remarks

- For PostgreSQL, the Podman container is auto-started by `mise run jar:pg:chat` if not already running.
- Log files are written to the process working directory: `amsha-prod-sqlite.log` and `amsha-cli-sqlite.log` (SQLite), `amsha-postgresql-postgres.log` (PostgreSQL), and `amsha-filesystem-fs.log` (Filesystem).
