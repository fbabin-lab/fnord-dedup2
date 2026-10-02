#!/usr/bin/python3
"""Isolate native read/hash protocol from JDBC and print bounded hang diagnostics."""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='image-protocol-') as tmp:
    root = pathlib.Path(tmp)
    tree = root / 'tree'; tree.mkdir()
    (tree / 'normal.txt').write_bytes(b'normal streaming test')
    with (tree / 'sparse.bin').open('wb') as f: f.truncate(1048576)
    (tree / 'empty').touch()
    image = root / 'disk.raw'
    with image.open('wb') as f: f.truncate(32 * 1048576)
    subprocess.run(['mkfs.ext4','-q','-F','-d',str(tree),str(image)],check=True)
    original = hashlib.file_digest(image.open('rb'),'sha256').hexdigest()
    work = root / 'work';work.mkdir()
    (work / '.extract-lock').touch()
    cache = root / 'cache';cache.mkdir();(cache / '.extract-lock').touch()
    reader = (ROOT / 'src/main/resources/image/native_image.py').read_text()
    # Test-only traceback instrumentation does not change read/parse behavior.
    reader = reader.replace('def worker(cfg):', 'def worker(cfg):\n    import faulthandler\n    faulthandler.dump_traceback_later(60, repeat=True)')
    (work / 'reader.py').write_text(reader)
    shutil.copyfile(ROOT / 'src/main/resources/image/safe_exec.py',work / 'safe_exec.py')
    cfg = dict(mode='inspect',work=str(work),graph=dict(driver='raw',file=dict(driver='file',filename=str(image))),
               components=[dict(path=str(image))],appliance_cache=str(cache),cache_lease=str(cache / '.extract-lock'),
               max_files=100,max_temp_bytes=1099511627776,min_free_bytes=0,max_listing_bytes=268435456,
               timeout=150,memory_bytes=4294967296,appliance_memory_mib=768,acceleration='tcg')
    p = subprocess.Popen(['/usr/bin/python3','-I',str(work / 'reader.py'),str(os.getpid())],cwd=work,
                         stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    try:
        stdout,stderr = p.communicate(json.dumps(cfg),timeout=160)
    except subprocess.TimeoutExpired:
        p.terminate()
        try: stdout,stderr=p.communicate(timeout=10)
        except subprocess.TimeoutExpired: p.kill();stdout,stderr=p.communicate()
    print(stdout,flush=True)
    print(stderr[-14000:],file=sys.stderr,flush=True)
    rows=[json.loads(line) for line in stdout.splitlines() if line]
    summaries=[row for row in rows if row['event']=='summary']
    assert p.returncode==0 and summaries and summaries[-1]['errors']==0, ('Native protocol failed',p.returncode)
    files={row['relative_path']:row for row in rows if row['event']=='entry' and row.get('sha256')}
    for name,payload in [('normal.txt',b'normal streaming test'),('sparse.bin',bytes(1048576)),('empty',b'')]:
        assert files['/'+name]['sha256']==hashlib.sha256(payload).hexdigest(),files
        assert files['/'+name]['actual_size']==len(payload),files
    assert hashlib.file_digest(image.open('rb'),'sha256').hexdigest()==original
    print('PASS: native normal/empty/sparse streaming checksums and unchanged source image',flush=True)
