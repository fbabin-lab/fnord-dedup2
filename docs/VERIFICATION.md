# Verification

Run the full local equivalent of the Linux CI suite:

```bash
./gradlew --no-daemon test installDist distTar
python3 scripts/process-smoke.py
python3 scripts/rollback-smoke.py
```

The Gradle suite contains 25 tests covering API, actual embedded DuckDB persistence and CLI behavior. The two Python suites launch the packaged Java/Groovy applications in separate Linux processes. They do not mock DuckDB recovery.

The five process cases are:

- SIGKILL after a committed discovery chunk, followed by replay of the unfinished directory.
- SIGKILL after a committed checksum chunk, followed by reuse of completed hashes.
- SIGTERM of the installed CLI during discovery, followed by resume.
- SIGKILL after appender data is flushed but before the first bulk transaction commits.
- The same uncommitted-flush case immediately after a prior checkpoint, with no intervening caller SQL to mask a missing transaction start.

Uncommitted metadata and checkpoint changes must disappear on reopening; previously committed work must remain. Subsequent resume must produce exactly one inventory/hash row per file. The two BulkWriter unit regressions separately verify rollback during normal close, before and after a checkpoint.

## Appender transaction regression

The initial implementation passed 23 tests and three process cases in [run 36907876866](https://github.com/fbabin-lab/fnord-dedup2/actions/runs/36907876866). An additional uncommitted-flush crash probe then exposed an integration bug: JDBC's `setAutoCommit(false)` changes a Java-side flag, but its native transaction begins lazily when a JDBC statement executes. An appender created before that first statement can therefore flush outside the intended transaction. The same gap recurs after a commit.

`BulkWriter` now deliberately activates the JDBC transaction with a lightweight `SELECT 1` before creating any appender, both on construction and immediately after every checkpoint commit. Do not remove these statements as apparently redundant work. Tests flush rows before issuing any caller SQL, which ensures this exact regression is exercised rather than hidden.

Consult the workflow for the exact commit under review rather than assuming a historical passing run validates new code. Tests use temporary fixtures only. Testing on an Ubuntu x86-64 runner is not a hardware performance benchmark or proof of power-loss durability on every filesystem/storage stack.
