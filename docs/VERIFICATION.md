# Verification

Run the full local equivalent of the Linux CI suite:

```bash
./gradlew --no-daemon test installDist distTar
python3 scripts/process-smoke.py
python3 scripts/rollback-smoke.py
```

The Gradle suite contains 23 tests covering API, actual embedded DuckDB persistence and CLI behavior. The two Python suites launch the packaged Java/Groovy applications in separate Linux processes. They do not mock DuckDB recovery.

The process cases are:

- SIGKILL after a committed discovery chunk, followed by replay of the unfinished directory.
- SIGKILL after a committed checksum chunk, followed by reuse of completed hashes.
- SIGTERM of the installed CLI during discovery, followed by resume.
- SIGKILL after appender data is flushed but before the transaction commits. The uncommitted metadata and checkpoint must disappear on reopening; the previously committed scan/root must remain, and a subsequent resume must produce exactly one inventory/hash row per file.

The first implementation commit, `749d6f0be3255969df6322ba11cb981862ce59b7`, passed the 23 Gradle tests and the first three process cases in GitHub Actions run [36907876866](https://github.com/fbabin-lab/fnord-dedup2/actions/runs/36907876866). The fourth case was added subsequently and is run by the workflow on each relevant push/PR. Consult the run for the exact commit under review rather than assuming a historical passing run validates new code.

Tests use temporary fixtures only. No production/source directories are modified. Testing on an Ubuntu x86-64 runner is not a hardware performance benchmark or proof of power-loss durability on every filesystem/storage stack.
