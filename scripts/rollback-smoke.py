#!/usr/bin/env python3
"""Kill after appender flush but before commit; metadata/checkpoint must roll back."""
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


def main():
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
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
Dedup.open(Path.of(args[0]), new ScanOptions(databaseThreads: 1, memoryLimit: '128MB')).withCloseable { d ->
    def root = Path.of(args[1])
    def scan = d.createScan('rollback', root)
    long id = scan.scan_id as long
    def attrs = Files.readAttributes(root.resolve('a'), BasicFileAttributes)
    def modified = attrs.lastModifiedTime().toInstant()
    // createScan committed the empty queue/root. Everything below is deliberately
    // uncommitted, including data that has been flushed out of the bulk appender.
    d.store.connection.autoCommit = false
    d.store.connection.createAppender('main', 'entries').withCloseable { a ->
        a.beginRow()
        a.append(id); a.append(2L); a.append(1L)
        a.append('a'); a.append('a'); a.append('FILE')
        a.append(attrs.size()); a.append(modified.epochSecond); a.append(modified.nano)
        a.endRow()
    }
    d.store.exec('UPDATE scans SET active_dir=1,next_entry_id=3 WHERE scan_id=?', id)
    assert d.store.rows("SELECT count(*) AS n FROM entries WHERE kind='FILE'")[0].n == 1
    Files.writeString(Path.of(args[2]), 'flushed but uncommitted')
    Thread.sleep(600000)
}
''', encoding='utf-8')
        process = subprocess.Popen([str(GROOVY), str(script), str(db), str(root), str(marker)],
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
        assert before['files'] == 0 and before['directories'] == 1, before
        assert before['pending_directories'] == 1, before
        after = json.loads(command(db, 'resume', '--name', 'rollback', '--quiet'))
        assert after['phase'] == 'COMPLETE', after
        assert after['files'] == 2 and after['hashes_completed'] == 2, after
        matches = [json.loads(line) for line in command(db, 'duplicates', '--name', 'rollback', '--format', 'jsonl').splitlines()]
        assert len(matches) == 2 and len({row['path'] for row in matches}) == 2
    print('PASS: SIGKILL rolls back flushed but uncommitted appender data and checkpoint')


if __name__ == '__main__':
    main()
