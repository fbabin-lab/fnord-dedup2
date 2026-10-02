#!/usr/bin/env python3
"""Installed Groovy script, large SQL copy, SIGTERM/SIGKILL, and crash recovery.
Only generated database fixtures are written; no native archive/image tools are needed.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time

PROJECT = Path(__file__).resolve().parents[1]
INSTALL = PROJECT / 'build/install/fnord-dedup2'
GROOVY = INSTALL / 'bin/fnord-dedup2-groovy'
MERGE = INSTALL / 'scripts/merge-database.groovy'


def run(script, *args, accepted=(0,), timeout=180):
    p = subprocess.run([str(GROOVY), str(script), *map(str, args)],
                       capture_output=True, text=True, timeout=timeout)
    assert p.returncode in accepted, (p.returncode, p.stdout, p.stderr)
    return p


def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


SEED = r'''
import fnord.dedup.*
import fnord.dedup.archive.ArchiveStore
import fnord.dedup.image.ImageStore
import java.nio.file.*
def path=Path.of(args[0]); def root=Files.createDirectories(Path.of(args[1])); long n=Long.parseLong(args[3])
Dedup.open(path,new ScanOptions(memoryLimit:'256MB',databaseThreads:1)).withCloseable { d ->
 d.createScan(args[2],root)
 d.store.transaction {
  d.store.exec("""INSERT INTO entries SELECT 1,i,1,
    CASE WHEN i=2 THEN 'source.7z' WHEN i=3 THEN 'disk.img' ELSE 'file-'||i END,
    CASE WHEN i=2 THEN 'source.7z' WHEN i=3 THEN 'disk.img' ELSE 'file-'||i END,
    'FILE',4,10,0 FROM range(2,${n+2}) t(i)""")
  d.store.exec('UPDATE directories SET completed=true')
  d.store.exec("UPDATE scans SET phase='COMPLETE',next_entry_id=?",n+2)
  d.store.exec("INSERT INTO hashes SELECT scan_id,entry_id,repeat('a',64) FROM entries WHERE kind='FILE'")
 }
 if(args[2]=='source') {
  new ArchiveStore(d.store,1024); new ImageStore(d.store)
  String a=UUID.randomUUID().toString(), im=UUID.randomUUID().toString()
  d.store.transaction {
   d.store.exec("INSERT INTO archive_runs VALUES (1,'COMPLETE',current_timestamp)")
   d.store.exec("INSERT INTO archive_inputs VALUES (1,'single:source.7z','single',0,2,'source.7z',4,10,0)")
   d.store.exec("INSERT INTO archive_jobs(scan_id,group_key,first_entry,flavor,status,result_id) VALUES (1,'single:source.7z',2,'single','COMPLETE',?)",a)
   d.store.exec("INSERT INTO archive_results(result_id,scan_id,source_label,policy,provider,state,reusable,completed_at) VALUES (?,1,'single:source.7z','fixture','fixture','COMPLETE',true,current_timestamp)",a)
   d.store.exec("INSERT INTO archive_volumes VALUES (?,1,0,2,'source.7z',4,repeat('b',64))",a)
   d.store.exec("INSERT INTO archive_members(result_id,ordinal,relative_path,filename,kind,declared_size,actual_size,sha256,integrity,encrypted) VALUES (?,1,'leaf','leaf','FILE',4,4,repeat('a',64),'READ_OK',false)",a)
   d.store.exec("INSERT INTO image_runs VALUES (1,'COMPLETE',current_timestamp)")
   d.store.exec("INSERT INTO image_jobs(scan_id,source_id,relative_path,format,size,modified_sec,modified_nano,state,result_id) VALUES (1,3,'disk.img','raw',4,10,0,'COMPLETE',?)",im)
   d.store.exec("INSERT INTO image_results(result_id,scan_id,source_id,state,provider,policy,virtual_size,reusable,completed_at) VALUES (?,1,3,'COMPLETE','fixture','fixture',4,true,current_timestamp)",im)
   d.store.exec("INSERT INTO image_components VALUES (?,0,-1,'PRIMARY','raw',3,'disk.img',4,10,0,repeat('b',64))",im)
   d.store.exec("INSERT INTO image_partitions VALUES (?,'/dev/sda',1,0,4,'gpt')",im)
   d.store.exec("INSERT INTO image_filesystems VALUES (?,1,'/dev/sda1','ext4',NULL,NULL,4,'COMPLETE')",im)
   d.store.exec("INSERT INTO image_entries VALUES (?,1,1,'/leaf','leaf','FILE',4,4,10,0,repeat('a',64),'READ_OK')",im)
  }
 }
}
'''

CHECK = r'''
import fnord.dedup.*
import groovy.json.JsonOutput
import java.nio.file.*
Dedup.open(Path.of(args[0]),new ScanOptions(memoryLimit:'256MB',databaseThreads:1)).withCloseable { d ->
 println JsonOutput.toJson([scans:d.listScans(),entries:d.store.rows('SELECT count(*) n FROM entries')[0].n,
    features:d.store.rows("SELECT table_name FROM information_schema.tables WHERE table_name IN ('archive_schema_info','image_schema_info')")*.table_name])
}
'''

CRASH = r'''
import fnord.dedup.*
import fnord.dedup.merge.*
import java.nio.file.*
import java.util.concurrent.*
StopToken stop=new StopToken(); CountDownLatch done=new CountDownLatch(1)
Thread hook=new Thread({stop.cancel();done.await(30,TimeUnit.SECONDS)} as Runnable)
Runtime.runtime.addShutdownHook(hook)
try {
 def merger=new DatabaseMerger(new ScanOptions(memoryLimit:'256MB',databaseThreads:1),stop)
 merger.progress={ Map e ->
  if ((e.phase=='TABLE_COPIED' && e.table==args[3]) || (args[3]=='commit' && e.phase=='BEFORE_COMMIT')) {
   Files.writeString(Path.of(args[2]),'uncommitted')
   while(!stop.cancelled) Thread.sleep(20)
   stop.check()
  }
 }
 merger.merge(Path.of(args[0]),Path.of(args[1]))
} catch(CancellationException ignored) {
 System.err.println('CANCELLED_ROLLED_BACK')
} finally { done.countDown(); try {Runtime.runtime.removeShutdownHook(hook)} catch(IllegalStateException ignored){} }
'''


def main():
    assert MERGE.is_file(), 'merge script must be included in the installed distribution'
    with tempfile.TemporaryDirectory(prefix='fnord-merge-test-') as tmp:
        base = Path(tmp)
        seed, check, harness = [base / (s + '.groovy') for s in ('seed', 'check', 'crash')]
        seed.write_text(SEED); check.write_text(CHECK); harness.write_text(CRASH)
        source, original = base / 'source.duckdb', base / 'original.duckdb'
        count = 250_000
        run(seed, source, base / 'source-root', 'source', count)
        run(seed, original, base / 'dest-root', 'existing', 2)
        source_sha, original_sha = sha(source), sha(original)
        # Leave committed data in a real WAL. Read-only attach must never repair it by writing.
        wal_seed = base / 'wal-seed.groovy'
        ending = SEED.rfind('\n}')
        wal_seed.write_text(SEED[:ending] + '\n Runtime.runtime.halt(0)\n}' + SEED[ending + 2:])
        wal_db = base / 'wal-source.duckdb'
        run(wal_seed, wal_db, base / 'wal-root', 'wal-source', 2)
        wal = Path(str(wal_db) + '.wal')
        assert wal.is_file() and wal.stat().st_size > 0
        wal_before = sha(wal_db), sha(wal)
        wal_result = run(MERGE, '--source', wal_db, '--destination', original, '--dry-run', '--quiet',
                         accepted=(0, 1))
        if wal_result.returncode:
            assert 'DATABASE_OPEN_FAILED' in wal_result.stderr
        else:
            assert json.loads(wal_result.stdout)['rows']['entries'] == 3
        assert (sha(wal_db), sha(wal)) == wal_before
        assert sha(original) == original_sha
        print('PASS: committed source WAL is read-only or explicitly refused, never rewritten', flush=True)
        plan = json.loads(run(MERGE, '--source', source, '--destination', original, '--dry-run', '--quiet',
                              '--memory-limit', '256MB').stdout)
        assert plan['status'] == 'READY' and plan['rows']['entries'] == count + 1
        assert sha(source) == source_sha and sha(original) == original_sha
        print('PASS: installed script dry-run, exact row plan, byte-unchanged databases', flush=True)

        for sig, stage in [(signal.SIGKILL, 'entries'), (signal.SIGKILL, 'archive_members'),
                           (signal.SIGKILL, 'image_entries'), (signal.SIGTERM, 'commit')]:
            dest, marker = base / f'{sig}-{stage}.duckdb', base / f'{sig}-{stage}.marker'
            shutil.copyfile(original, dest)
            p = subprocess.Popen([str(GROOVY), str(harness), str(source), str(dest), str(marker), stage],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                deadline = time.monotonic() + 180
                while not marker.exists():
                    if p.poll() is not None:
                        raise AssertionError(('merge exited before checkpoint', p.communicate()))
                    if time.monotonic() >= deadline:
                        raise AssertionError('merge never reached the uncommitted checkpoint')
                    time.sleep(0.025)
                p.send_signal(sig)
                out, err = p.communicate(timeout=45)
                assert p.returncode in (-sig, 128 + sig, 0), (out, err, p.returncode)
                if sig == signal.SIGTERM:
                    assert 'CANCELLED_ROLLED_BACK' in err, (out, err)
            finally:
                if p.poll() is None:
                    p.kill(); p.wait()
            observed = json.loads(run(check, dest).stdout)
            assert len(observed['scans']) == 1 and observed['scans'][0]['name'] == 'existing', observed
            assert observed['entries'] == 3 and observed['features'] == [], observed
            assert sha(source) == source_sha
            checkpoint = 'before COMMIT' if stage == 'commit' else f'after {stage}'
            print(f'PASS: {sig.name} {checkpoint}; full rollback including optional-schema creation', flush=True)

        dest = base / 'successful.duckdb'
        shutil.copyfile(original, dest)
        report = json.loads(run(MERGE, '--source', source, '--destination', dest, '--quiet',
                                '--memory-limit', '256MB', '--database-threads', '1').stdout)
        assert report['status'] == 'IMPORTED' and report['scans_imported'] == 1
        assert report['rows']['image_entries'] == 1 and report['rows']['archive_members'] == 1
        observed = json.loads(run(check, dest).stdout)
        assert len(observed['scans']) == 2 and observed['entries'] == count + 4
        before = sha(dest)
        conflict = run(MERGE, '--source', source, '--destination', dest, '--quiet', accepted=(3,))
        assert 'SCAN_NAME_CONFLICT' in conflict.stderr
        assert sha(dest) == before and sha(source) == source_sha
        print('PASS: 250,000-file import, archive/image transfer, repeat-import refusal, unchanged source', flush=True)


if __name__ == '__main__':
    main()
