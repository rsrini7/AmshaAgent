# Amsha Agent Autotest

## Usage

```bash
# Unified parameterized runner
autotest/run.sh --backend sqlite
autotest/run.sh --backend pg --rows 1-20
autotest/run.sh --suite skillsjars
autotest/run.sh --suite loka

# SQLite (default) — full run via Python directly
python3 autotest.py -f telegram-test-amsha.txt -j results.json -l run.log -v --backend sqlite

# PostgreSQL — auto-starts Podman container
python3 autotest.py -f telegram-test-amsha.txt -j results-pg.json -l run-pg.log -v --backend pg

# Filesystem — no database needed
python3 autotest.py -f telegram-test-amsha.txt -j results-fs.json -l run-fs.log -v --backend fs

# Quick sanity: first 20 cases, fail-fast
python3 autotest.py -r 1-20 --fail-fast --backend sqlite

# Skip heavy browser/code-exec tests
python3 autotest.py --skip-categories browse,code_exec -j quick.json --backend sqlite
```

## Shell Wrappers

The following wrappers forward to `autotest/run.sh`:

| Script | Backend | Rows | Suite |
|---|---|---|---|
| `autotest-sqlite.sh` | SQLite | All | Telegram |
| `autotest-pg.sh` | PostgreSQL | All | Telegram |
| `autotest-fs.sh` | Filesystem | All | Telegram |
| `autotest-sqlite-rows.sh` | SQLite | 1-20 | Telegram |
| `autotest-pg-rows.sh` | PostgreSQL | 1-20 | Telegram |
| `autotest-fs-rows.sh` | Filesystem | 1-20 | Telegram |
| `autotest-skillsjars.sh` | SQLite | All | SkillsJars |
| `autotest-loka.sh` | SQLite | All | Loka |

## Remarks

- For PostgreSQL, the Podman container is auto-started by `mise run jar:pg:chat` if not already running.
- Log files: `amsha-cli.log` (SQLite), `amsha-pg.log` (PostgreSQL), `amsha-fs.log` (Filesystem)
