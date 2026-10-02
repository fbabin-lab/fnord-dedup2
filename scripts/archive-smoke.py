#!/usr/bin/env python3
"""Installed-app archive integration/recovery tests. All data is temporary.

FNORD_TEST_CLASSPATH may point at a compiled developer harness. Normal CI tests
use the installed distribution, including its packaged helper resource.
"""
import argparse
import binascii
import hashlib
import io
import json
import os
from pathlib import Path
import selectors
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile
import zlib
from archive_fixtures import zip_bytes, rar4, tar_bytes

ROOT=Path(__file__).resolve().parents[1]
CP=os.environ.get('FNORD_TEST_CLASSPATH',str(ROOT/'build/install/fnord-dedup2/lib/*'))
JAVA=['java','-Xmx512m','-cp',CP]
ENV=dict(os.environ,LC_ALL='C.UTF-8')


def cli(db,*args,expected=(0,)):
    command=JAVA+['fnord.dedup.cli.Main','--db',str(db),'--database-threads','1','--memory-limit','256MB']+list(args)
    result=subprocess.run(command,text=True,capture_output=True,env=ENV,timeout=120)
    assert result.returncode in expected,(command,result.returncode,result.stdout,result.stderr)
    return result.stdout


def json_cli(db,*args,**kw):
    return json.loads(cli(db,*args,**kw))


def lines(db,*args):
    return [json.loads(line) for line in cli(db,*args).splitlines() if line]


def scan(db,root,name='s'):
    cli(db,'scan','--name',name,'--root',str(root),'--discover-only','--quiet')


def analyze(db,name='s',expected=(0,),*extra):
    return json_cli(db,'archives','--name',name,'--archive-temp-min-free','0','--quiet',*extra,expected=expected)


def clean(db):
    parent=Path(str(db)+'.archives-tmp')
    assert not list(parent.glob('fnord-*/attempt-*')),parent


def one_result(db,stem):
    roots=[r for r in lines(db,'archive-list','--name','s') if r['location_kind']=='ROOT' and stem in r['source']]
    assert len(roots)==1,roots
    return roots[0]


def members(db,result):
    return lines(db,'archive-entries','--result',result['result_id'])


def sha(data):
    return hashlib.sha256(data).hexdigest()


def format_tests(base,require_7z):
    root=base/'formats';root.mkdir();db=base/'formats.duckdb'
    (root/'complex.tar.gz').write_bytes(tar_bytes())
    corrupt=zip_bytes([('good',b'good'),('bad',b'BBBB'),('later',b'later')],stored=True)
    corrupt=corrupt.replace(b'BBBB',b'XXXX',1)
    (root/'corrupt.zip').write_bytes(corrupt)
    content=b'multipart archive data - exact known bytes'
    part1=rar4(content,content[:15],after=True)
    part2=rar4(content,content[15:],before=True,first=False)
    for folder in ('rar','rar-copy','missing'):
        d=root/folder;d.mkdir();(d/'data.part1.rar').write_bytes(part1)
        if folder!='missing':(d/'data.part2.rar').write_bytes(part2)
    (root/'nested.zip').write_bytes(zip_bytes([('chunks/data.part1.rar',part1),('chunks/data.part2.rar',part2)]))
    (root/'invalid.zip').write_bytes(b'not an archive')
    (root/'duplicate-members.zip').write_bytes(zip_bytes([('same',b'first'),('same',b'second')]))
    # Native fixture tools run only in the test's directory, never on user data.
    inputfile=base/'payload';inputfile.write_bytes(bytes(range(256))*1200)
    subprocess.run(['zip','-q','-0','-s','64k',str(root/'split.zip'),str(inputfile.name)],cwd=base,check=True)
    subprocess.run(['zip','-q','-P','test-only',str(root/'encrypted.zip'),str(inputfile.name)],cwd=base,check=True)
    seven=shutil.which('7z') or shutil.which('7zz')
    assert seven or not require_7z,'CI must install 7z fixture generator'
    if seven:
        subprocess.run([seven,'a','-bd','-y','-mx=0','-v64k',str(root/'split.7z'),str(inputfile.name)],cwd=base,stdout=subprocess.DEVNULL,check=True)
    scan(db,root)
    status=analyze(db,expected=(3,));assert status['phase']=='COMPLETE_WITH_ERRORS',status
    tar=members(db,one_result(db,'complex.tar.gz'))
    assert [m['sha256'] for m in tar if m['relative_path']=='same']==[sha(b'first'),sha(b'second')],tar
    assert {m['kind'] for m in tar}>={'FILE','SYMLINK','HARDLINK','OTHER'}
    assert all(m['sha256'] is None for m in tar if m['kind']!='FILE')
    assert next(m for m in tar if m['filename']=='plain')['modified_nano']==123456789
    rows=members(db,one_result(db,'corrupt'))
    assert next(m for m in rows if m['filename']=='good')['sha256']==sha(b'good')
    assert next(m for m in rows if m['filename']=='later')['sha256']==sha(b'later')
    bad=next(m for m in rows if m['filename']=='bad')
    assert bad['sha256'] is None and bad['integrity']=='DAMAGED',bad
    rar=one_result(db,'rar/data');copy=one_result(db,'rar-copy/data')
    assert rar['result_id']==copy['result_id']
    assert rar['status']=='COMPLETE',rar
    assert members(db,rar)[0]['sha256']==sha(content)
    miss=one_result(db,'missing/data');assert miss['status']=='PARTIAL' and not miss['reusable'],miss
    nested=[r for r in lines(db,'archive-list','--name','s') if r['location_kind']=='NESTED']
    assert any(r['result_id']==rar['result_id'] for r in nested),nested
    split=one_result(db,'zip-split:split')
    assert split['status']=='COMPLETE',split
    assert members(db,split)[0]['sha256']==sha(inputfile.read_bytes())
    assert one_result(db,'encrypted')['status']=='PARTIAL'
    dup=members(db,one_result(db,'duplicate-members'))
    assert [m['sha256'] for m in dup]==[sha(b'first'),sha(b'second')]
    if seven:
        split7=one_result(db,'7z-split:split')
        assert split7['status']=='COMPLETE',split7
        assert members(db,split7)[0]['sha256']==sha(inputfile.read_bytes())
    clean(db)
    print('PASS: TAR metadata/links/duplicate names, corrupt ZIP recovery, split ZIP, encrypted ZIP, RAR4 multipart/nested/cache/missing volume, 7z=' + str(bool(seven)),flush=True)


def wait_line(process,predicate,timeout=60):
    # Use raw pipes rather than TextIO buffering with select.
    selector=selectors.DefaultSelector();selector.register(process.stdout,selectors.EVENT_READ)
    deadline=time.monotonic()+timeout;buffer=b''
    try:
        while time.monotonic()<deadline:
            if process.poll() is not None:
                raise AssertionError(('driver exited early',process.returncode,process.stderr.read().decode()))
            for key,_ in selector.select(.1):
                chunk=os.read(key.fileobj.fileno(),65536)
                buffer+=chunk
                while b'\n' in buffer:
                    line,buffer=buffer.split(b'\n',1)
                    if line and predicate(json.loads(line)):
                        return
        raise AssertionError('No requested durable checkpoint before timeout')
    finally:selector.close()


def recovery_test(base,signum):
    root=base/('kill-input-'+str(signum));root.mkdir();db=base/('kill-'+str(signum)+'.duckdb')
    (root/'first.zip').write_bytes(zip_bytes([('a',b'preserved completed archive')]))
    scan(db,root)
    analyze(db)
    previous=one_result(db,'first')['result_id']
    # A second named scan shares the completed first archive's immutable cache.
    # Enough members keep the extractor active while a small batch is committed.
    with zipfile.ZipFile(root/'second.zip','w',compression=zipfile.ZIP_STORED) as z:
        for i in range(5000):z.writestr(str(i),b'x'*1024)
    scan(db,root,'resume')
    driver=base/('driver-'+str(signum)+'.groovy')
    driver.write_text('''import fnord.dedup.*
import fnord.dedup.archive.*
import groovy.json.JsonOutput
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
StopToken stop=new StopToken()
CountDownLatch ended=new CountDownLatch(1)
Runtime.runtime.addShutdownHook(new Thread({stop.cancel();ended.await(20,TimeUnit.SECONDS)} as Runnable))
try {
 Dedup.open(Path.of(args[0]),new ScanOptions(batchSize:32,memoryLimit:'256MB',databaseThreads:1)).withCloseable { d ->
  d.progress={ e ->
   println JsonOutput.toJson(e);System.out.flush()
   if(e.status=='MEMBERS_STAGED') Thread.sleep(300)
  }
  d.analyzeArchives('resume',new ArchiveOptions(minFreeBytes:0),stop)
 }
} finally {ended.countDown()}
''')
    process=subprocess.Popen(JAVA+['groovy.ui.GroovyMain',str(driver),str(db)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=ENV)
    try:
        wait_line(process,lambda e:e.get('status')=='MEMBERS_STAGED')
        process.send_signal(signum);process.wait(timeout=30)
    finally:
        if process.poll() is None:process.kill();process.wait()
        process.stdout.close();process.stderr.close()
    # The completed old scan remains queryable. Staged new members are invisible.
    assert one_result(db,'first')['result_id']==previous
    partial=json_cli(db,'archive-status','--name','resume')
    assert partial['checksummed_members']<=1,partial
    status=analyze(db,'resume');assert status['phase']=='COMPLETE',status
    assert status['checksummed_members']==5001,status
    clean(db)
    print('PASS: real '+signal.Signals(signum).name+' during extraction after committed member batches, cache preservation and temp recovery',flush=True)


def upstream_rar5(base):
    # Public reference fixtures from libarchive's own regression suite, pinned to
    # an immutable commit. No fetched code is executed. Only archive bytes read.
    commit='d294297f9ecade3b2446b677bd087ad84fb7965a'
    root=base/'rar5';root.mkdir();db=base/'rar5.duckdb'
    for i in range(1,5):
        name='test_read_format_rar5_multiarchive_solid.part%02d.rar'%i
        url='https://raw.githubusercontent.com/libarchive/libarchive/'+commit+'/libarchive/test/'+name+'.uu'
        with urllib.request.urlopen(url,timeout=30) as response:data=response.read(1024*1024)
        encoded=data.splitlines();assert encoded[0].startswith(b'begin ') and encoded[-1]==b'end'
        raw=b''.join(binascii.a2b_uu(line) for line in encoded[1:-1])
        (root/name).write_bytes(raw)
    scan(db,root);status=analyze(db)
    assert status['checksummed_members']==9,status
    result=lines(db,'archive-list','--name','s')[0]
    assert result['status']=='COMPLETE' and result['reusable'],result
    assert len(lines(db,'archive-volumes','--result',result['result_id']))==4
    # Copy fixtures into the optional reproducible harness artifact.
    dest=ROOT/'build/archive-fixtures';dest.mkdir(parents=True,exist_ok=True)
    for file in root.iterdir():shutil.copyfile(file,dest/file.name)
    clean(db)
    print('PASS: upstream RAR5 solid 4-volume fixture, 9 members',flush=True)


def main():
    args=argparse.ArgumentParser()
    args.add_argument('--require-7z',action='store_true')
    args.add_argument('--upstream-rar5',action='store_true')
    opts=args.parse_args()
    with tempfile.TemporaryDirectory(prefix='fnord-archive-tests-') as temp:
        base=Path(temp)
        format_tests(base,opts.require_7z)
        recovery_test(base,signal.SIGKILL)
        recovery_test(base,signal.SIGTERM)
        if opts.upstream_rar5:upstream_rar5(base)
    print('All archive smoke tests passed',flush=True)

if __name__=='__main__':main()
