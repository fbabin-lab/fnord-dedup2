#!/usr/bin/env python3
"""Kill after bulk-writer flush but before commit, including after a prior checkpoint."""
import json
from pathlib import Path
import signal
import subprocess
import tempfile
import time

PROJECT = Path(__file__).resolve().parents[1]
APP = PROJECT / 'build/install/fnord-dedup2/bin/fnord-dedup2'
GROOVY = PROJECT / 'build/install/fnord-dedup2/bin/fnord-dedup2-groovy'


def command(db, *args):
    result = subprocess.run([str(APP), '--db', str(db), '--memory-limit', '128MB',
                             '--database-threads', '1', *args], text=True,
                            capture_output=True, timeout=60)
    assert result.returncode == 0, (result.returncode, result.stdout, result.stderr)
    return result.stdout


def scenario(commit_first):
    with tempfile.TemporaryDirectory(prefix='fnord-dedup2-rollback-') as temp:
        base = Path(temp)
        root = base / 'input'
        root.mkdir()
        (root / 'a').write_text('same')
        (root / 'b').write_text('same')
        db = base / 'scan.duckdb'
        marker = base / 'flushed-not-committed'
        script = base / 'uncommitted.groovy'
        script.write_text('''
import fnord.dedup.*
import fnord.dedup.store.BulkWriter
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
boolean commitFirst = args[3] == 'yes'
def options = new ScanOptions(databaseThreads: 1, memoryLimit: '128MB')
Dedup.open(Path.of(args[0]), options).withCloseable { d ->
    def root = Path.of(args[1])
    def scan = d.createScan('rollback', root)
    long id = scan.scan_id as long
    def writer = new BulkWriter(d.store, id, 'discovery', 2L, options, { event -> })
    if (commitFirst) {
        writer.entry(writer.allocateId(), 1L, 'a', 'a', 'FILE', Files.readAttributes(root.resolve('a'), BasicFileAttributes))
        writer.checkpoint(1L)
    }
    def name = commitFirst ? 'b' : 'a'
    writer.entry(writer.allocateId(), 1L, name, name, 'FILE', Files.readAttributes(root.resolve(name), BasicFileAttributes))
    // No SQL between the previous commit and this flush: that would conceal
    // JDBC's lazy transaction-start behavior and fail to test BulkWriter itself.
    writer.flushPendingRows()
    d.store.exec('UPDATE scans SET active_dir=1,next_entry_id=? WHERE scan_id=?', writer.nextEntryId, id)
    assert d.store.rows("SELECT count(*) AS n FROM entries WHERE kind='FILE'")[0].n == (commitFirst ? 2 : 1)
    Files.writeString(Path.of(args[2]), 'flushed but uncommitted')
    Thread.sleep(600000)
}
''', encoding='utf-8')
        process = subprocess.Popen([str(GROOVY), str(script), str(db), str(root), str(marker),
                                    'yes' if commit_first else 'no'],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 60
            while not marker.exists():
                if process.poll() is not None:
                    raise AssertionError(('harness failed before marker', *process.communicate()))
                if time.monotonic() >= deadline:
                    raise AssertionError('uncommitted transaction marker timed out')
                time.sleep(0.05)
            process.kill()
            process.communicate(timeout=15)
            assert process.returncode == -signal.SIGKILL
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate(timeout=15)
        before = json.loads(command(db, 'status', '--name', 'rollback'))
        assert before['phase'] == 'DISCOVERING', before
        assert before['files'] == (1 if commit_first else 0), before
        assert before['directories'] == 1 and before['pending_directories'] == 1, before
        after = json.loads(command(db, 'resume', '--name', 'rollback', '--quiet'))
        assert after['phase'] == 'COMPLETE', after
        assert after['files'] == 2 and after['hashes_completed'] == 2, after
        matches = [json.loads(line) for line in command(db, 'duplicates', '--name', 'rollback', '--format', 'jsonl').splitlines()]
        assert len(matches) == 2 and len({row['path'] for row in matches}) == 2
    print('PASS: SIGKILL rolls back flushed uncommitted rows',
          'after a prior checkpoint' if commit_first else 'in the initial transaction')


if __name__ == '__main__':
    scenario(False)
    scenario(True)
