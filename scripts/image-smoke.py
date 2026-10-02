#!/usr/bin/env python3
"""Installed-app Linux image matrix. All writes are inside generated fixtures.

No guest is booted. mkfs only creates a disposable test image. Native format
conversion creates test inputs, never scanner output. No sudo needed here.
"""
import argparse
import hashlib
import json
import os
import pathlib
import plistlib
import shutil
import signal
import struct
import subprocess
import tempfile
import time
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'build/install/fnord-dedup2/bin/fnord-dedup2'


def run(argv, **kwargs):
    p = subprocess.run(list(map(str, argv)), capture_output=True, timeout=kwargs.pop('timeout', 120), **kwargs)
    if p.returncode:
        raise AssertionError((argv, p.returncode, p.stdout[-4000:], p.stderr[-8000:]))
    return p


def cli(db, *args, allowed=(0,), timeout=1200):
    p = subprocess.run([str(APP), '--db', str(db), '--memory-limit', '256MB', '--database-threads', '1', *map(str, args)],
                       capture_output=True, text=True, timeout=timeout)
    if p.returncode not in allowed:
        raise AssertionError((args, p.returncode, p.stdout[-8000:], p.stderr[-8000:]))
    return p


def jsonl(text):
    return [json.loads(line) for line in text.splitlines() if line]


def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        while data := f.read(1048576):
            h.update(data)
    return h.hexdigest()


def dmg(raw, destination):
    """Minimal valid zlib-compressed UDIF, one MISH table; deterministic fixture."""
    chunks = []
    sector = 0
    with open(raw, 'rb') as src, open(destination, 'wb') as out:
        while block := src.read(524288):
            block += bytes((-len(block)) % 512)
            compressed = zlib.compress(block)
            offset = out.tell()
            out.write(compressed)
            chunks.append(struct.pack('>IIQQQQ', 0x80000005, 0, sector, len(block) // 512, offset, len(compressed)))
            sector += len(block) // 512
        data_length = out.tell()
        header = bytearray(204)
        struct.pack_into('>4sIQQQII', header, 0, b'mish', 1, 0, sector, 0, 1024, 0)
        struct.pack_into('>I', header, 200, len(chunks) + 1)
        table = bytes(header) + b''.join(chunks) + struct.pack('>IIQQQQ', 0xffffffff, 0, sector, 0, 0, 0)
        xml = plistlib.dumps({'resource-fork': {'blkx': [{'Name': 'whole disk', 'Data': table}]}}, sort_keys=True)
        out.write(xml)
        trailer = bytearray(512)
        struct.pack_into('>4sIIIQQQQQII', trailer, 0, b'koly', 4, 512, 1, 0, 0, data_length, 0, 0, 1, 1)
        struct.pack_into('>QQ', trailer, 216, data_length, len(xml))
        struct.pack_into('>I', trailer, 488, 1)
        struct.pack_into('>Q', trailer, 492, sector)
        out.write(trailer)


def scan(db, source, name):
    cli(db, 'scan', '--name', name, '--root', source, '--discover-only', '--quiet')


def images(db, name, *args, allowed=(0,)):
    return cli(db, 'images', '--name', name, '--image-temp-min-free', '0', '--image-container-hash', 'always', *args, allowed=allowed)


def matrix(work):
    tree = work / 'files'; tree.mkdir()
    (tree / 'nested').mkdir()
    (tree / 'normal.txt').write_bytes(b'the same content\n')
    (tree / 'nested/copy.txt').write_bytes(b'the same content\n')
    (tree / 'different.txt').write_bytes(b'different bytes!\n')
    (tree / 'empty').touch()
    (tree / 'space quote\'\t\n-été').write_bytes(b'unusual path')
    (tree / 'outside-link').symlink_to('/etc/passwd')
    os.mkfifo(tree / 'fifo')
    with open(tree / 'sparse.bin', 'wb') as f:
        f.truncate(1048576)
    source = work / 'images'; source.mkdir()
    seed = source / 'seed.bin'
    with open(seed, 'wb') as f:
        f.truncate(64 * 1048576)
    run(['mkfs.ext4', '-F', '-q', '-d', tree, seed])
    expected = {('/' + str(p.relative_to(tree))): digest(p) for p in tree.rglob('*') if p.is_file() and not p.is_symlink()}
    shutil.copyfile(seed, source / 'disk.raw')
    shutil.copyfile(seed, source / 'copy.img')
    shutil.copyfile(seed, source / 'copy.dd')
    formats = {'qcow': 'qcow', 'qcow2': 'qcow2', 'vdi': 'vdi', 'vhd': 'vpc', 'vhdx': 'vhdx', 'qed': 'qed', 'vmdk': 'vmdk'}
    for ext, fmt in formats.items():
        run(['qemu-img', 'convert', '-f', 'raw', '-O', fmt, seed, source / ('disk.' + ext)])
    run(['qemu-img', 'create', '-f', 'qcow2', '-F', 'raw', '-b', 'seed.bin', 'overlay.qcow2'], cwd=source)
    run(['qemu-img', 'create', '-f', 'vmdk', '-o', 'subformat=twoGbMaxExtentSparse', 'split.vmdk', '3G'], cwd=source)
    run(['qemu-img', 'convert', '-n', '-f', 'raw', '-O', 'vmdk', seed, source / 'split.vmdk'])
    run(['qemu-img', 'convert', '-f', 'raw', '-O', 'vmdk', '-o', 'subformat=monolithicFlat', seed, source / 'flat.vmdk'])
    run(['qemu-img', 'convert', '-f', 'raw', '-O', 'vmdk', '-o', 'subformat=streamOptimized', seed, source / 'stream.vmdk'])
    # ISO and DMG use a small separate optical fixture without special-node creation.
    optical = work / 'optical'; optical.mkdir()
    (optical / 'document.txt').write_bytes(b'optical image')
    run(['genisoimage', '-quiet', '-R', '-J', '-o', source / 'disc.iso', optical])
    dmg(source / 'disc.iso', source / 'disc.dmg')
    originals = {p.name: digest(p) for p in source.iterdir() if p.is_file()}
    db = work / 'matrix.duckdb'
    scan(db, source, 'matrix')
    result = images(db, 'matrix', allowed=(0, 3))
    rows = jsonl(cli(db, 'image-list', '--name', 'matrix').stdout)
    errors = jsonl(cli(db, 'image-errors', '--name', 'matrix').stdout)
    failures = [r for r in rows if r['state'] not in ['COMPLETE', 'COMPONENT']]
    assert not failures, (failures, errors, result.stderr[-8000:])
    assert sum(r['duplicate'] for r in rows) >= 2, rows
    assert sum(r['state'] == 'COMPONENT' for r in rows) >= 3, rows
    for row in rows:
        if row['state'] == 'COMPONENT':
            continue
        entries = jsonl(cli(db, 'image-entries', '--result', row['result_id']).stdout)
        by_path = {e['relative_path']: e for e in entries if e['sha256']}
        target = {'/document.txt': hashlib.sha256(b'optical image').hexdigest()} if row['relative_path'].endswith(('.iso', '.dmg')) else expected
        assert all(by_path.get(path, {}).get('sha256') == sha for path, sha in target.items()), (row, entries, target)
        assert all(e['sha256'] is None for e in entries if e['kind'] != 'FILE'), (row, entries)
    assert {p.name: digest(p) for p in source.iterdir() if p.is_file()} == originals, 'Source images changed'
    assert not list(pathlib.Path(str(db) + '.images-tmp').glob('fnord-*/attempt-*')), 'Temporary work leaked'
    print('PASS: RAW/IMG/DD, QCOW1/2, backing chain, VDI, VHD, VHDX, QED, VMDK variants, ISO and compressed UDIF/DMG; source hashes unchanged', flush=True)
    return seed


def dependency_failures(work):
    source = work / 'unsafe'; source.mkdir()
    for name, backing in [('absolute', '/etc/passwd'), ('missing', 'absent.raw'), ('cycle-a', 'cycle-b.qcow2'), ('cycle-b', 'cycle-a.qcow2')]:
        run(['qemu-img', 'create', '-u', '-f', 'qcow2', '-F', 'raw' if name in ['absolute', 'missing'] else 'qcow2', '-b', backing, name + '.qcow2', '64M'], cwd=source)
    (source / 'broken.qcow2').write_bytes(b'not an image')
    db = work / 'unsafe.duckdb'; scan(db, source, 'unsafe')
    images(db, 'unsafe', allowed=(3,))
    errors = jsonl(cli(db, 'image-errors', '--name', 'unsafe').stdout)
    codes = {e['code'] for e in errors}
    assert {'UNSAFE_DEPENDENCY', 'MISSING_COMPONENT', 'DEPENDENCY_CYCLE', 'INVALID_IMAGE'} <= codes, errors
    print('PASS: absolute/missing/cyclic dependencies rejected; malformed images recorded', flush=True)


def recovery(work, seed, sig):
    source = work / ('recovery-' + sig.name); source.mkdir()
    shutil.copyfile(seed, source / 'disk.raw')
    db = work / (sig.name + '.duckdb'); scan(db, source, 'recovery')
    log = work / (sig.name + '.log')
    with open(log, 'w') as err:
        p = subprocess.Popen([str(APP), '--db', str(db), '--batch-size', '2', '--memory-limit', '256MB', 'images', '--name', 'recovery', '--image-temp-min-free', '0'],
                             stdout=subprocess.DEVNULL, stderr=err)
        deadline = time.monotonic() + 300
        while p.poll() is None and time.monotonic() < deadline:
            if '"state":"SCANNING"' in log.read_text():
                p.send_signal(sig)
                break
            time.sleep(.01)
        else:
            p.kill(); raise AssertionError(('No inspection checkpoint', log.read_text()))
        p.wait(timeout=20)
    time.sleep(.5)  # supervisor must retire its process group/leases after SIGKILL
    result = images(db, 'recovery')
    assert json.loads(result.stdout)['phase'] == 'COMPLETE'
    assert not list(pathlib.Path(str(db) + '.images-tmp').glob('fnord-*/attempt-*'))
    print('PASS:', sig.name, 'native image scan replay and temporary cleanup', flush=True)


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    if os.geteuid() == 0:
        raise SystemExit('Run this smoke suite as an unprivileged user')
    for executable in ['qemu-img', 'qemu-nbd', 'mkfs.ext4', 'genisoimage']:
        assert shutil.which(executable), executable + ' missing'
    with tempfile.TemporaryDirectory(prefix='fd2-images-') as tmp:
        work = pathlib.Path(tmp)
        seed = matrix(work)
        dependency_failures(work)
        recovery(work, seed, signal.SIGKILL)
        recovery(work, seed, signal.SIGTERM)
    print('All image smoke tests passed', flush=True)


if __name__ == '__main__':
    main()
