#!/usr/bin/env python3
"""Small deterministic fixtures; no proprietary archiver required for stored RAR4."""
import io, os, struct, zlib, zipfile, tarfile

def zip_bytes(entries, stored=False):
    buf=io.BytesIO()
    with zipfile.ZipFile(buf,'w',compression=zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED) as z:
        for name,data in entries: z.writestr(name,data)
    return buf.getvalue()

def rar4(data,payload=None,before=False,after=False,first=True):
    def header(kind,flags,body=b''):
        raw=struct.pack('<BHH',kind,flags,7+len(body))+body
        return struct.pack('<H',zlib.crc32(raw)&65535)+raw
    if payload is None: payload=data
    main=header(0x73, (0x11|(0x100 if first else 0)) if (before or after) else 0, bytes(6))
    name=b'file.txt'
    crc=zlib.crc32(payload if after else data)
    body=struct.pack('<IIBIIBBHI',len(payload),len(data),3,crc,0,20,0x30,len(name),0o100644)+name
    item=header(0x74,0x8000|(1 if before else 0)|(2 if after else 0),body)+payload
    return b'Rar!\x1a\x07\x00'+main+item+header(0x7b,1 if after else 0)

def tar_bytes():
    buf=io.BytesIO()
    with tarfile.open(fileobj=buf,mode='w:gz',format=tarfile.PAX_FORMAT) as t:
        for name,data in [('plain',b'one'),('same',b'first'),('same',b'second')]:
            e=tarfile.TarInfo(name); e.size=len(data); e.pax_headers={'mtime':'1700000000.123456789'}
            t.addfile(e,io.BytesIO(data))
        for name,kind in [('link',tarfile.SYMTYPE),('hard',tarfile.LNKTYPE),('fifo',tarfile.FIFOTYPE)]:
            e=tarfile.TarInfo(name);e.type=kind;e.linkname='plain';t.addfile(e)
    return buf.getvalue()
