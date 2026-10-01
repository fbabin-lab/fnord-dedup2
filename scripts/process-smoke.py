#!/usr/bin/env python3
"""Real Linux subprocess tests: installed CLI, SIGTERM, SIGKILL and WAL recovery."""
import json
import os
from pathlib import Path
import selectors
import signal
import subprocess
import tempfile
import time

PROJECT = Path(__file__).resolve().parents[1]
APP = PROJECT / 'build/install/fnord-dedup2/bin/fnord-dedup2'
GROOVY = PROJECT / 'build/install/fnord-dedup2/bin/fnord-dedup2-groovy'


def command(db, *args, accepted=(0,)):
    result = subprocess.run([str(APP), '--db', str(db), '--memory-limit', '128MB',
                             '--database-threads', '1', *args], text=True,
                            capture_output=True, timeout=90)
    assert result.returncode in accepted, (result.returncode, result.stdout, result.stderr)
    return result


def wait_marker(process, marker):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if marker.exists():
            return
        if process.poll() is not None:
            out, err = process.communicate()
            raise AssertionError(('harness exited before checkpoint', out, err))
        time.sleep(0.05)
    process.kill()
    raise AssertionError('checkpoint marker was not created')


def crash_test(base, stage):
    root = base / ('crash-' + stage)
    root.mkdir()
    for i in range(39):
        (root / f'file-{i}').write_text('duplicate-data')
    (root / 'nested').mkdir()
    (root / 'nested' / 'last').write_text('duplicate-data')
    db = base / (stage + '.duckdb')
    marker = base / (stage + '.marker')
    script = base / (stage + '.groovy')
    script.write_text('''
import fnord.dedup.*
import java.nio.file.*
def db = Path.of(args[0]); def root = Path.of(args[1]); def marker = Path.of(args[2])
def stage = args[3]
Dedup.open(db, new ScanOptions(batchSize: 3, databaseThreads: 1, memoryLimit: '128MB')).withCloseable { d ->
    d.createScan('crash', root)
    if (stage == 'hashing') d.discover('crash')
    d.progress = { event ->
        if (event.stage == stage) {
            Files.writeString(marker, 'committed')
            Thread.sleep(600000)
        }
    }
    if (stage == 'hashing') d.hash('crash')
    else d.discover('crash')
}
''', encoding='utf-8')
    process = subprocess.Popen([str(GROOVY), str(script), str(db), str(root), str(marker), stage],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        wait_marker(process, marker)
        process.kill()  # SIGKILL deliberately bypasses Java shutdown hooks.
        process.communicate(timeout=15)
        assert process.returncode == -signal.SIGKILL
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=15)
    before = json.loads(command(db, 'status', '--name', 'crash').stdout)
    if stage == 'hashing':
        assert before['hashes_completed'] == 3, before
    else:
        assert before['phase'] == 'DISCOVERING', before
    after = json.loads(command(db, 'resume', '--name', 'crash', '--quiet').stdout)
    assert after['phase'] == 'COMPLETE', after
    assert after['files'] == 40 and after['directories'] == 2, after
    assert after['hashes_completed'] == 40, after
    matches = [json.loads(line) for line in command(db, 'duplicates', '--name', 'crash', '--format', 'jsonl').stdout.splitlines()]
    assert len(matches) == 40 and len({row['path'] for row in matches}) == 40
    print('PASS: SIGKILL and recovery during', stage)


def graceful_test(base):
    root = base / 'graceful'
    root.mkdir()
    for i in range(3000):
        (root / f'file-{i}').write_text('same')
    db = base / 'graceful.duckdb'
    process = subprocess.Popen([str(APP), '--db', str(db), '--memory-limit', '128MB',
                                '--database-threads', '1', '--batch-size', '16',
                                'scan', '--name', 'graceful', '--root', str(root), '--discover-only'],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        selector = selectors.DefaultSelector()
        selector.register(process.stderr, selectors.EVENT_READ)
        assert selector.select(timeout=60), 'no CLI checkpoint before timeout'
        first = process.stderr.readline()
        selector.close()
        assert json.loads(first)['stage'] == 'discovery', first
        process.terminate()
        out, err = process.communicate(timeout=40)
        assert process.returncode in (128 + signal.SIGTERM, 130, -signal.SIGTERM), (process.returncode, out, err)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=15)
    status = json.loads(command(db, 'resume', '--name', 'graceful', '--quiet').stdout)
    assert status['phase'] == 'COMPLETE' and status['files'] == 3000, status
    print('PASS: installed CLI SIGTERM and resume')


if __name__ == '__main__':
    assert os.name == 'posix', 'This suite is for Linux'
    with tempfile.TemporaryDirectory(prefix='fnord-dedup2-smoke-') as temp:
        base = Path(temp)
        crash_test(base, 'discovery')
        crash_test(base, 'hashing')
        graceful_test(base)
    print('All process smoke tests passed')
