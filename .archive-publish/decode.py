# Temporary transport verifier; removed with its workflow by the resulting commit.
from pathlib import Path
import base64, gzip, hashlib, os, zlib
CRCS = 'fe376b94,6ee85148,5e2b9f43,78508bc6,7df277e2,600228e4,f2218007,3e7baad4,3a822441,1bac9d02,f247cd4a,6c607115,4bf5a35a,d5c18a3a,40cf2997,6b34b95b,f6104cf0,d2c59d56,d9b69fdc,91913515,21ecef00,f5babe4e,c73d7dc8,1091b2c7,7bcfc378,732f8bc7,d89a36dd,c76fc190,672ba0fe,c609c571,773340fd,566e29ed,e1ea9be2,11e2d9fe,061eb60a,a1c40a0f,bbdc70a1,5ed61d73,d8ba4906,de053ecc,4a3621d0,b95bd5cb,255380bf,55f4994e,d4f27a1b,92694cd1,62e10e01,90ca10f8,b8880858,aab0400a,3bf929d9,e00e0312,4bc333d2,dbeffafa,ec9baf42,9f7a318e,fe9c5977,db45bce3,5aa6e91f,01832a4a,50d10b46,7a7be1b0,09175add,a4fece06,d9afb138,ac6f98ea,aaad3617,95b8eb0b,0889c092,b663be8c,b74169b8,480cd172,83a4522b,880400b6,bc53affa,f6219774,de95ff1a,a4707a11,66e5ca45,9ddaa082,2299a680,9252b2d8,177431b8,756d5c2e,4e6e5f0b,349779d0,32c09734,d79482a6,c48a6bb7,fca5f02b,94a1a467,55815ce5,1a0952bb,072a7cbe'.split(',')
def block(data, n, crc):
    for consumed in (n, n+1, n-1):
        b=data[:consumed]
        if consumed==n:
            if zlib.crc32(b)==crc:return b,consumed
            choices=(b[:i]+bytes([c])+b[i+1:] for i in range(len(b)) for c in b'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=')
        elif consumed==n+1:
            choices=(b[:i]+b[i+1:] for i in range(len(b)))
        else:
            choices=(b[:i]+bytes([c])+b[i:] for i in range(len(b)+1) for c in b'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=')
        for fixed in choices:
            if len(fixed)==n and zlib.crc32(fixed)==crc:return fixed,consumed
    raise ValueError('Transport block cannot be verified')
def repair(raw):
    fixed=[];pos=0
    for i,crc in enumerate(CRCS):
        try:b,used=block(raw[pos:],min(128,12000-i*128),int(crc,16))
        except ValueError as e:raise ValueError(f'Bad block {i}, input offset {pos}') from e
        fixed.append(b);pos+=used
    assert pos==len(raw),'Transport has trailing data'
    result=b''.join(fixed)
    assert hashlib.sha1(b'blob 12000\0'+result).hexdigest()=='767cf29eddc70178ba6495e9542e2fd599572747'
    return result
root=Path('.archive-publish');parts=sorted(root.glob('[0-9]*'))
assert len(parts)==16
encoded=repair(parts[0].read_bytes())+b''.join(p.read_bytes() for p in parts[1:])
assert len(encoded)==51980
patch=gzip.decompress(base64.b64decode(encoded,validate=True))
assert hashlib.sha256(patch).hexdigest()=='08d10c0290a696e67e522ec8ba9198e7d0ae23e63cd5ed755af98efeeea1b809'
Path(os.environ['RUNNER_TEMP'],'archive-feature.patch').write_bytes(patch)
print('Verified exact tested archive implementation patch (SHA-256).')
