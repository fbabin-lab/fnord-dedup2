#!/usr/bin/python3
"""Fail-closed Linux Landlock guard for QEMU image decoders.

Only approved input inodes, runtime libraries and private work are readable.
Source writes and TCP connections are denied. This is defense in depth in addition
 to the coordinator's explicit block graph / VMDK reference validation.
"""
import ctypes
import errno
import json
import os
import resource
import stat
import sys


def restrict(inputs, work, executable):
    libc = ctypes.CDLL(None, use_errno=True)
    syscall = libc.syscall
    abi = syscall(444, 0, 0, 1)  # landlock_create_ruleset(VERSION)
    if abi < 1:
        raise OSError(ctypes.get_errno(), 'Landlock unavailable; refusing unconfined image decoder')
    access = (1 << 13) - 1
    if abi >= 2:
        access |= 1 << 13  # REFER
    if abi >= 3:
        access |= 1 << 14  # TRUNCATE

    class Ruleset(ctypes.Structure):
        _fields_ = [('fs', ctypes.c_uint64), ('net', ctypes.c_uint64)]

    class Beneath(ctypes.Structure):
        _pack_ = 1
        _fields_ = [('allowed', ctypes.c_uint64), ('parent', ctypes.c_int32)]

    rules = Ruleset(access, 3 if abi >= 4 else 0)
    fd = syscall(444, ctypes.byref(rules), 16 if abi >= 4 else 8, 0)
    if fd < 0:
        raise OSError(ctypes.get_errno(), 'Cannot create Landlock ruleset')
    try:
        def allow(path, flags, source=False):
            if not os.path.exists(path):
                return
            handle = os.open(path, os.O_PATH | os.O_CLOEXEC | (os.O_NOFOLLOW if source else 0))
            try:
                mode = os.fstat(handle).st_mode
                if source and not stat.S_ISREG(mode):
                    raise OSError(errno.EINVAL, 'Source is not a regular file')
                if not stat.S_ISDIR(mode):
                    flags &= (1 | 2 | 4 | (1 << 14))
                rule = Beneath(flags, handle)
                if syscall(445, fd, 1, ctypes.byref(rule), 0) < 0:
                    raise OSError(ctypes.get_errno(), 'Cannot install Landlock path rule')
            finally:
                os.close(handle)

        allow('/', 8)  # directory enumeration, never blanket file reads
        for path in inputs:
            allow(path, 4, source=True)
        allow(executable, 5)
        for path in ['/usr/lib', '/lib', '/lib64', '/usr/share/qemu']:
            allow(path, 1 | 4 | 8)
        for path in ['/etc/ld.so.cache', '/proc/cpuinfo', '/dev/urandom', '/dev/random']:
            allow(path, 4)
        allow('/dev/null', 2 | 4)
        allow(work, access & ~1)  # no executing files created in work
        if libc.prctl(38, 1, 0, 0, 0) != 0:  # PR_SET_NO_NEW_PRIVS
            raise OSError(ctypes.get_errno(), 'Cannot set no_new_privs')
        if syscall(446, fd, 0) < 0:
            raise OSError(ctypes.get_errno(), 'Cannot enforce Landlock')
    finally:
        os.close(fd)


def main():
    with open(sys.argv[1], encoding='utf-8') as f:
        cfg = json.load(f)
    argv = cfg['argv']
    if not argv or argv[0] not in ['/usr/bin/qemu-img', '/usr/bin/qemu-nbd']:
        raise ValueError('Not an approved decoder executable')
    resource.setrlimit(resource.RLIMIT_AS, (cfg['memory_bytes'], cfg['memory_bytes']))
    resource.setrlimit(resource.RLIMIT_FSIZE, (cfg['max_temp_bytes'], cfg['max_temp_bytes']))
    restrict(cfg['inputs'], cfg['work'], argv[0])
    os.execv(argv[0], argv)


if __name__ == '__main__':
    main()
