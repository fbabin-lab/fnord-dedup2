#!/usr/bin/python3
"""Fail early with readable diagnostics before the native format matrix."""
import json
import pathlib
import runpy
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
m = runpy.run_path(str(ROOT / 'scripts/image-smoke.py'))
with tempfile.TemporaryDirectory(prefix='image-preflight-') as tmp:
    work = pathlib.Path(tmp)
    source = work / 'source'; source.mkdir()
    image = source / 'disk.raw'
    with image.open('wb') as out: out.truncate(32 * 1048576)
    m['run'](['mkfs.ext4', '-q', '-F', image])
    db = work / 'preflight.duckdb'
    m['scan'](db, source, 'preflight')
    result = m['images'](db, 'preflight', allowed=(0, 3))
    errors = m['jsonl'](m['cli'](db, 'image-errors', '--name', 'preflight').stdout)
    (ROOT / 'build/image-preflight-errors.json').write_text(json.dumps(errors, indent=2))
    for error in errors:
        print(error['code'] + ':\n' + error['message'], flush=True)
    assert not errors, 'Native preflight failed; see image-preflight-errors.json'
    print('PASS: unprivileged read-only RAW preflight', flush=True)
