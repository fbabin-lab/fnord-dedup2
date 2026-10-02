#!/usr/bin/env python3
"""Installed cross-scan command: actual signals, committed-hash replay, offline streaming.
All writes are confined to generated fixture directories/databases.
"""
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time

PROJECT = Path(__file__).resolve().parents[1]
INSTALL = PROJECT / 'build/install/fnord-dedup2'
APP = INSTALL / 'bin/fnord-dedup2'
GROOVY = INSTALL / 'bin/fnord-dedup2-groovy'
EXAMPLE = INSTALL / 'examples/cross-duplicates.groovy'


def run_groovy(script, *args):
    p = subprocess.run([str(GROOVY), str(script), *map(str, args)], capture_output=True, text=True, timeout=120)
    assert p.returncode == 0, (p.returncode, p.stdout, p.stderr)
    return p


def compare(db, **kwargs):
    command = [str(APP), '--db', str(db), '--database-threads', '1', '--memory-limit', '128MB',
               '--batch-size', '3', 'cross-duplicates', '--scan', 'A', '--scan', 'B', '--format', 'jsonl', '--quiet']
    return subprocess.run(command, stderr=subprocess.PIPE, text=True, timeout=120, **kwargs)


SEED = r'''
import fnord.dedup.*
import groovy.json.JsonOutput
import java.nio.file.*
def base=Path.of(args[1]); def options=new ScanOptions(batchSize:3,databaseThreads:1,memoryLimit:'128MB')
Dedup.open(Path.of(args[0]),options).withCloseable { d ->
 for (String name : ['A','B','ignored']) {
  Path root=Files.createDirectories(base.resolve(name))
  for (int i=0;i<24;i++) Files.writeString(root.resolve('file-'+i),'same')
  d.scan(name,root,new StopToken(),true)
 }
 println JsonOutput.toJson(d.store.rows('SELECT * FROM scans ORDER BY scan_id'))
}
'''

SLOW = r'''
import fnord.dedup.*
import fnord.dedup.cross.*
import java.nio.file.*
import java.util.concurrent.*
StopToken stop=new StopToken(); CountDownLatch done=new CountDownLatch(1)
Thread hook=new Thread({stop.cancel();done.await(30,TimeUnit.SECONDS)} as Runnable)
Runtime.runtime.addShutdownHook(hook)
try {
 Dedup.open(Path.of(args[0]),new ScanOptions(batchSize:3,workers:1,databaseThreads:1,memoryLimit:'128MB')).withCloseable { d ->
  d.progress={ Map e -> if(e.phase=='HASHES_COMMITTED' && e.hashes_completed_this_run==6) {
   Files.writeString(Path.of(args[1]),'six hashes committed')
   while(!stop.cancelled) Thread.sleep(10)
  }}
  d.crossDuplicates(['A','B'],new CrossScanOptions(),stop) { row -> }
 }
} finally {done.countDown(); try {Runtime.runtime.removeShutdownHook(hook)} catch(IllegalStateException ignored){}}
'''

CHECK = r'''
import fnord.dedup.*
import groovy.json.JsonOutput
import java.nio.file.*
Dedup.open(Path.of(args[0]),new ScanOptions(databaseThreads:1,memoryLimit:'128MB')).withCloseable { d ->
 println JsonOutput.toJson([scans:d.store.rows('SELECT * FROM scans ORDER BY scan_id'),
  hashes:d.store.rows('SELECT count(*) n FROM hashes')[0].n,
  duplicate_keys:d.store.rows('SELECT count(*) n FROM (SELECT scan_id,entry_id FROM hashes GROUP BY scan_id,entry_id HAVING count(*)>1)')[0].n,
  ignored_hashes:d.store.rows("SELECT count(*) n FROM hashes JOIN scans USING(scan_id) WHERE name='ignored'")[0].n,
  errors:d.store.rows('SELECT count(*) n FROM scan_errors')[0].n])
}
'''

LARGE = r'''
import fnord.dedup.*
import java.nio.file.*
Dedup.open(Path.of(args[0]),new ScanOptions(databaseThreads:1,memoryLimit:'128MB')).withCloseable { d ->
 for (String name : ['A','B']) {
  def root=Files.createDirectories(Path.of(args[1]).resolve(name))
  long id=d.createScan(name,root).scan_id
  d.store.transaction {
   d.store.exec("INSERT INTO entries SELECT ?,i,1,'f-'||i,'f-'||i,'FILE',4,1,0 FROM range(2,50002) t(i)",id)
   d.store.exec('UPDATE directories SET completed=true WHERE scan_id=?',id)
   d.store.exec("UPDATE scans SET phase='READY',next_entry_id=50002 WHERE scan_id=?",id)
   d.store.exec("INSERT INTO hashes SELECT scan_id,entry_id,repeat('a',64) FROM entries WHERE scan_id=? AND kind='FILE'",id)
  }
  // Historical fixture: the root is deliberately unavailable during the comparison.
  Files.delete(root)
 }
}
'''


def main():
    assert os.name == 'posix'
    assert EXAMPLE.is_file(), 'cross-scan example must be packaged'
    with tempfile.TemporaryDirectory(prefix='fnord-cross-smoke-') as tmp:
        base = Path(tmp)
        scripts = {}
        for name, contents in [('seed', SEED), ('slow', SLOW), ('check', CHECK), ('large', LARGE)]:
            scripts[name] = base / (name + '.groovy')
            scripts[name].write_text(contents, encoding='utf-8')
        for sig in (signal.SIGKILL, signal.SIGTERM):
            root = base / sig.name
            root.mkdir()
            db, marker = root / 'scans.duckdb', root / 'checkpoint'
            before = json.loads(run_groovy(scripts['seed'], db, root).stdout)
            child = subprocess.Popen([str(GROOVY), str(scripts['slow']), str(db), str(marker)],
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                deadline = time.monotonic() + 60
                while not marker.exists() and time.monotonic() < deadline and child.poll() is None:
                    time.sleep(0.05)
                assert marker.exists(), 'No cross-scan commit checkpoint before signal'
                child.send_signal(sig)
                out, err = child.communicate(timeout=40)
                accepted = (-sig, 128 + sig, 130)
                assert child.returncode in accepted, (child.returncode, out, err)
            finally:
                if child.poll() is None:
                    child.kill(); child.wait(timeout=15)
            partial = json.loads(run_groovy(scripts['check'], db).stdout)
            assert partial['scans'] == before and partial['hashes'] == 6, partial
            assert partial['errors'] == 0 and partial['ignored_hashes'] == 0 and partial['duplicate_keys'] == 0
            result = compare(db, stdout=subprocess.PIPE)
            assert result.returncode == 0, result.stderr
            rows = [json.loads(line) for line in result.stdout.splitlines()]
            report = json.loads(result.stderr)
            assert len(rows) == 48 and all(row['scan_count'] == 2 for row in rows)
            assert report['hashes_completed_this_run'] == 42 and report['existing_candidate_hashes'] == 6, report
            after = json.loads(run_groovy(scripts['check'], db).stdout)
            assert after['scans'] == before and after['hashes'] == 48 and after['duplicate_keys'] == 0
            assert after['errors'] == 0 and after['ignored_hashes'] == 0
            again = compare(db, stdout=subprocess.PIPE)
            assert again.returncode == 0 and json.loads(again.stderr)['hashes_needed'] == 0
            example = run_groovy(EXAMPLE, db, 'A', 'B')
            assert len(example.stdout.splitlines()) == 48 and json.loads(example.stderr)['hashes_needed'] == 0
            assert all(p.read_text() == 'same' for name in ('A','B','ignored') for p in (root/name).iterdir())
            print(f'PASS: {sig.name} preserves committed hashes; replay, unchanged scan state, and selected-only writes', flush=True)
        db = base / 'large.duckdb'
        run_groovy(scripts['large'], db, base / 'offline')
        output = base / 'duplicates.jsonl'
        with output.open('w', encoding='utf-8') as stream:
            result = compare(db, stdout=stream)
        assert result.returncode == 0, result.stderr
        report = json.loads(result.stderr)
        assert report['hashes_needed'] == 0 and report['duplicate_observations'] == 100_000, report
        count = 0
        with output.open(encoding='utf-8') as stream:
            for line in stream:
                row = json.loads(line)
                assert row['copies'] == 100_000 and row['scan_count'] == 2
                count += 1
        assert count == 100_000
        print('PASS: 100,000 offline observations stream from saved hashes with zero hashes needed', flush=True)
    print('All cross-scan process tests passed', flush=True)


if __name__ == '__main__':
    main()
