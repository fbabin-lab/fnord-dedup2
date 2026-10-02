#!/usr/bin/env python3
"""Native filesystem, isolation, and deterministic crash-boundary regressions."""
import hashlib
import json
import os
import pathlib
import runpy
import signal
import subprocess
import tempfile
import time

BASE = pathlib.Path(__file__).resolve().parents[1]
M = runpy.run_path(str(BASE / 'scripts/image-smoke.py'))
cli, run, scan, digest, jsonl = (M[k] for k in ['cli', 'run', 'scan', 'digest', 'jsonl'])
GROOVY = BASE / 'build/install/fnord-dedup2/bin/fnord-dedup2-groovy'


def guard_test(work):
    source = work / 'allowed'; source.write_text('allowed')
    denied = work / 'not-approved'; denied.write_text('private')
    target = work / 'decoder-work'; target.mkdir()
    guard = BASE / 'src/main/resources/image/safe_exec.py'
    code = '''import importlib.util,os,sys
spec=importlib.util.spec_from_file_location('guard',sys.argv[1]);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.restrict([sys.argv[2]],sys.argv[4],'/usr/bin/qemu-img')
assert open(sys.argv[2]).read()=='allowed'
for path,mode in [(sys.argv[2],'w'),(sys.argv[3],'r')]:
    try: open(path,mode)
    except PermissionError: pass
    else: raise AssertionError('Landlock failed to deny '+mode+' '+path)
with open(os.path.join(sys.argv[4],'temporary'),'w') as f:f.write('ok')
print('PASS: native guard permits approved reads/temp writes and denies source writes/unapproved reads')
'''
    result = run(['/usr/bin/python3', '-c', code, guard, source, denied, target])
    print(result.stdout.decode().strip(), flush=True)


def partitioned(work):
    import guestfs
    root = work / 'partitions'; root.mkdir()
    image = root / 'disk.raw'
    with open(image, 'wb') as out: out.truncate(256 * 1048576)
    g = guestfs.GuestFS(python_return_dict=True)
    try:
        g.set_backend('direct'); g.set_network(False); g.set_memsize(768)
        g.add_drive_opts(str(image), format='raw', readonly=False)
        g.launch()
        device = g.list_devices()[0]
        g.part_init(device, 'gpt')
        for start, end in [(2048, 133119), (133120, 264191), (264192, 395263)]:
            g.part_add(device, 'p', start, end)
        for part, kind in zip(g.list_partitions(), ['ext4', 'vfat', 'ntfs']):
            g.mkfs(kind, part)
            g.mount(part, '/')
            g.write('/document.txt', b'multiple filesystems')
            g.umount_all()
    finally:
        g.close()
    original = digest(image)
    db = work / 'partitions.duckdb'; scan(db, root, 'partitions')
    p = cli(db, 'images', '--name', 'partitions', '--image-temp-min-free', '0', allowed=(0, 3))
    rows = jsonl(cli(db, 'image-list', '--name', 'partitions').stdout)
    errors = cli(db, 'image-errors', '--name', 'partitions').stdout
    assert rows[0]['state'] == 'COMPLETE', (p.stdout, errors, p.stderr)
    rid = rows[0]['result_id']
    fs = jsonl(cli(db, 'image-filesystems', '--result', rid).stdout)
    parts = jsonl(cli(db, 'image-partitions', '--result', rid).stdout)
    entries = jsonl(cli(db, 'image-entries', '--result', rid).stdout)
    sha = hashlib.sha256(b'multiple filesystems').hexdigest()
    assert len(fs) == 3 and len(parts) == 3, (fs, parts)
    assert len([e for e in entries if e['sha256'] == sha]) == 3, entries
    assert digest(image) == original
    print('PASS: GPT with ext4, FAT and NTFS; all three filesystems indexed read-only', flush=True)
    cli(db, 'images', '--name', 'partitions', '--image-temp-min-free', '0', '--force', '--image-max-files', '2', allowed=(3,))
    cli(db, 'images', '--name', 'partitions', '--image-temp-min-free', '0', '--retry-errors')
    assert digest(image) == original
    print('PASS: file-count limit preserves partial diagnostics and higher-limit retry succeeds', flush=True)


def deterministic_recovery(work):
    tree = work / 'guest'; tree.mkdir()
    for i in range(1000): (tree / ('file-%04d' % i)).write_bytes(b'crash test content')
    source = work / 'recovery'; source.mkdir()
    image = source / 'disk.raw'
    with open(image, 'wb') as out: out.truncate(64 * 1048576)
    run(['mkfs.ext4', '-q', '-F', '-d', tree, image])
    original = digest(image)
    db = work / 'recovery.duckdb'
    scan(db, source, 'finished')
    cli(db, 'images', '--name', 'finished', '--image-temp-min-free', '0')
    canonical = jsonl(cli(db, 'image-list', '--name', 'finished').stdout)[0]['result_id']
    script = work / 'crash.groovy'
    script.write_text('''import fnord.dedup.*
import fnord.dedup.image.*
import java.nio.file.Path
import java.util.concurrent.*
StopToken token=new StopToken()
CountDownLatch finished=new CountDownLatch(1)
Thread hook=new Thread({ ->token.cancel();finished.await(25,TimeUnit.SECONDS)} as Runnable)
Runtime.runtime.addShutdownHook(hook)
try {
    Dedup.open(Path.of(args[0]),new ScanOptions(batchSize:2,memoryLimit:'256MB')).withCloseable { d ->
        boolean paused=false
        d.progress={ Map event ->
            if (!paused && event.entries_written_this_run!=null && event.entries_written_this_run>=2) {
                paused=true
                System.err.println('COMMITTED_CHECKPOINT');System.err.flush()
                while (!token.cancelled) Thread.sleep(20)
            }
        }
        d.analyzeImages(args[1],new ImageOptions(minFreeBytes:0,containerHash:'never'),token)
    }
} finally {finished.countDown();try {Runtime.runtime.removeShutdownHook(hook)} catch(IllegalStateException ignored) {}}
''')
    for sig in [signal.SIGKILL, signal.SIGTERM]:
        name = 'paused-' + sig.name
        scan(db, source, name)
        log = work / (sig.name + '.log')
        with open(log, 'w') as err:
            p = subprocess.Popen([str(GROOVY), str(script), str(db), name], stdout=subprocess.DEVNULL, stderr=err)
            try:
                deadline = time.monotonic() + 300
                while p.poll() is None and time.monotonic() < deadline:
                    if 'COMMITTED_CHECKPOINT' in log.read_text():
                        p.send_signal(sig); break
                    time.sleep(.02)
                else: raise AssertionError(('Did not reach committed native-file boundary', log.read_text()))
                p.wait(timeout=35)
            finally:
                if p.poll() is None: p.kill();p.wait()
        time.sleep(4)
        rows = jsonl(cli(db, 'image-list', '--name', name).stdout)
        assert rows[0]['state'] == 'RUNNING', rows
        rid = rows[0]['result_id']
        cli(db, 'image-entries', '--result', rid, allowed=(1,))
        cli(db, 'images', '--name', name, '--image-temp-min-free', '0', '--image-container-hash', 'never')
        rows = jsonl(cli(db, 'image-list', '--name', name).stdout)
        entries = jsonl(cli(db, 'image-entries', '--result', rows[0]['result_id']).stdout)
        assert len([e for e in entries if e['sha256']]) == 1000
        assert jsonl(cli(db, 'image-list', '--name', 'finished').stdout)[0]['result_id'] == canonical
        assert not list(pathlib.Path(str(db) + '.images-tmp').glob('fnord-*/attempt-*'))
        assert digest(image) == original
        print('PASS:', sig.name, 'after committed guest-file batches; hidden staging, finalized-result preservation, replay and cleanup', flush=True)


def main():
    if os.geteuid() == 0: raise SystemExit('Run tests unprivileged')
    with tempfile.TemporaryDirectory(prefix='fd2-image-extra-') as tmp:
        work = pathlib.Path(tmp)
        guard_test(work)
        partitioned(work)
        deterministic_recovery(work)
    print('All extended image tests passed', flush=True)


if __name__ == '__main__': main()
