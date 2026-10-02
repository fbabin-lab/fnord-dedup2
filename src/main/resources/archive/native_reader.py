#!/usr/bin/env python3
"""Private JSONL bridge to native libarchive; never restores archive paths.

Payloads use decimal member ordinals. Groovy owns SHA-256, DuckDB, recursion and
publication. No shell, password prompt, source writes, or external filter programs.
"""
import base64
import ctypes as C
import ctypes.util
import errno
import fcntl
import json
import os
from pathlib import Path
import re
import resource
import signal
import stat
import struct
import sys
import zlib

PROTOCOL = 1
MAX_PATH_BYTES = 32768
OK, EOF, RETRY, WARN = 0, 1, -10, -20


def emit(event):
    print(json.dumps(event, ensure_ascii=True, separators=(',', ':')), flush=True)


def parent_guard(expected):
    libc = C.CDLL(None, use_errno=True)
    if libc.prctl(1, signal.SIGKILL, 0, 0, 0) != 0:
        raise OSError(C.get_errno(), 'PR_SET_PDEATHSIG failed')
    if os.getppid() != expected:
        raise RuntimeError('Controller exited before extractor startup')
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


class NativeUnavailable(Exception):
    pass


class Native:
    def __init__(self):
        override = os.environ.get('FNORD_LIBARCHIVE')
        if override:
            candidates = [override]
        else:
            candidates = []
            discovered = ctypes.util.find_library('archive')
            if discovered:
                candidates.append(discovered)
            candidates.extend(['libarchive.so.13', 'libarchive.so'])

        errors = []
        self.lib = None
        for candidate in dict.fromkeys(candidates):
            try:
                self.lib = C.CDLL(candidate)
                self.library_name = candidate
                break
            except OSError as exc:
                errors.append(f'{candidate}: {exc}')
        if self.lib is None:
            detail = '; '.join(errors[-3:]) if errors else 'no candidates were found'
            raise NativeUnavailable(
                'libarchive runtime not found; install your distribution libarchive '
                'runtime package or set FNORD_LIBARCHIVE to the shared-library path; ' + detail
            )
        def fn(name, result, *args):
            value = getattr(self.lib, name)
            value.restype = result
            value.argtypes = list(args)
            setattr(self, name, value)
        p, i, s, q = C.c_void_p, C.c_int, C.c_char_p, C.c_longlong
        fn('archive_version_string', s)
        fn('archive_version_details', s)
        fn('archive_read_new', p)
        fn('archive_read_support_filter_all', i, p)
        fn('archive_read_support_format_all', i, p)
        fn('archive_read_support_format_raw', i, p)
        fn('archive_read_support_format_zip_streamable', i, p)
        fn('archive_read_open_filenames', i, p, C.POINTER(s), C.c_size_t)
        fn('archive_read_next_header', i, p, C.POINTER(p))
        fn('archive_read_data', C.c_ssize_t, p, p, C.c_size_t)
        fn('archive_read_data_skip', i, p)
        fn('archive_read_free', i, p)
        fn('archive_error_string', s, p)
        fn('archive_format_name', s, p)
        fn('archive_filter_count', i, p)
        fn('archive_entry_pathname_utf8', s, p)
        fn('archive_entry_pathname', s, p)
        fn('archive_entry_size', q, p)
        fn('archive_entry_size_is_set', i, p)
        fn('archive_entry_mtime', C.c_long, p)
        fn('archive_entry_mtime_nsec', C.c_long, p)
        fn('archive_entry_mtime_is_set', i, p)
        fn('archive_entry_filetype', C.c_uint, p)
        fn('archive_entry_hardlink', s, p)
        fn('archive_entry_is_encrypted', i, p)

    def message(self, archive):
        raw = self.archive_error_string(archive)
        return (raw or b'Native archive error').decode('utf-8', 'replace')[:4096]


def safe_path(raw):
    if raw is None or len(raw) > MAX_PATH_BYTES:
        return None, 'INVALID_NAME'
    try:
        name = raw.decode('utf-8', 'strict')
    except UnicodeError:
        return None, 'INVALID_UTF8'
    if '\x00' in name or name.startswith(('/', '\\')) or re.match(r'^[A-Za-z]:', name):
        return name, 'UNSAFE_PATH'
    if '\\' in name or '..' in name.split('/'):
        return name, 'UNSAFE_PATH'
    normalized = '/'.join(p for p in name.split('/') if p not in ('', '.'))
    return normalized, None if normalized or raw in (b'.', b'./') else 'INVALID_NAME'


def vint(data, at=0):
    value = 0
    for shift in range(0, 70, 7):
        if at >= len(data):
            raise ValueError('Truncated RAR variable integer')
        b = data[at]
        at += 1
        value |= (b & 127) << shift
        if b < 128:
            return value, at
    raise ValueError('Oversized RAR variable integer')


def rar_end(path):
    """Checked header-only RAR4/RAR5 final-volume proof. Unknown is not complete.
    RAR5 layout: https://www.rarlab.com/technote.htm
    """
    multi = False
    with path.open('rb') as src:
        sig = src.read(8)
        rar5 = sig == b'Rar!\x1a\x07\x01\x00'
        if not rar5 and sig[:7] != b'Rar!\x1a\x07\x00':
            return False, None
        src.seek(8 if rar5 else 7)
        length = path.stat().st_size
        while src.tell() < length:
            start = src.tell()
            if rar5:
                prefix = src.read(14)
                if len(prefix) < 5:
                    break
                size, end = vint(prefix, 4)
                if size > 2 * 1024 * 1024 or start + end + size > length:
                    break
                src.seek(start + end)
                header = src.read(size)
                if zlib.crc32(prefix[4:end] + header) != struct.unpack_from('<I', prefix)[0]:
                    break
                kind, at = vint(header)
                flags, at = vint(header, at)
                if flags & 1:
                    _, at = vint(header, at)
                data_size = 0
                if flags & 2:
                    data_size, at = vint(header, at)
                if kind == 1:
                    archive_flags, _ = vint(header, at)
                    multi = bool(archive_flags & 1)
                if kind == 4:
                    break
                if kind == 5:
                    end_flags, _ = vint(header, at)
                    return multi, not bool(end_flags & 1)
                target = start + end + size + data_size
            else:
                basic = src.read(7)
                if len(basic) != 7:
                    break
                crc, kind, flags, size = struct.unpack('<HBHH', basic)
                if size < 7 or start + size > length:
                    break
                header = basic + src.read(size - 7)
                if zlib.crc32(header[2:]) & 65535 != crc:
                    break
                if kind == 0x73:
                    multi = bool(flags & 1)
                if kind == 0x7b:
                    return multi, not bool(flags & 1)
                data_size = struct.unpack_from('<I', header, 7)[0] if flags & 0x8000 else 0
                if kind == 0x74 and flags & 0x100:
                    data_size += struct.unpack_from('<I', header, 32)[0] << 32
                target = start + size + data_size
            if target <= start or target > length:
                break
            src.seek(target)
    return multi, None


class Limit(Exception):
    pass


class Reader:
    def __init__(self, config, native):
        self.cfg, self.n = config, native
        self.total = 0
        self.errors = 0
        self.operational = False
        self.limited = False
        self.ended = False
        self.ordinal = 0
        self.format = None
        self.volume_complete = True

    def error(self, code, message, ordinal=None, category='CONTENT'):
        self.errors += 1
        emit(dict(event='error', ordinal=ordinal, category=category,
                  code=code, message=str(message)[:4096]))

    def space(self, adding):
        if adding < 0 or self.total + adding > int(self.cfg['byte_budget']):
            raise Limit('Expanded-byte/temporary-stack limit reached')
        fs = os.statvfs('.')
        if fs.f_bavail * fs.f_frsize - adding < int(self.cfg['min_free_bytes']):
            raise OSError(errno.ENOSPC, 'Temporary filesystem free-space reserve reached')

    def skip(self, archive, ordinal):
        result = self.n.archive_read_data_skip(archive)
        if result < OK:
            self.error('SKIP_ERROR', self.n.message(archive), ordinal)
        return result >= WARN

    def inputs(self):
        paths = [os.fsencode(x) for x in self.cfg['volumes']]
        if self.cfg['flavor'] == '7z-split':
            # A seekable joined stream is required for split 7z. Copy compressed
            # bytes only, without modifying originals. The copy consumes quota.
            with open('joined-input', 'xb', buffering=0) as out:
                for path in paths:
                    with open(path, 'rb', buffering=0) as src:
                        while True:
                            block = src.read(1024 * 1024)
                            if not block:
                                break
                            self.space(len(block))
                            out.write(block)
                            self.total += len(block)
            return [os.fsencode(str(Path('joined-input').absolute()))]
        return paths

    def run(self):
        n, archive = self.n, self.n.archive_read_new()
        if not archive:
            raise MemoryError('archive_read_new')
        try:
            n.archive_read_support_filter_all(archive)
            if self.cfg['flavor'] in ('zip-split', 'zip-chunks'):
                n.archive_read_support_format_zip_streamable(archive)
            else:
                n.archive_read_support_format_all(archive)
            if self.cfg.get('raw_compressed'):
                n.archive_read_support_format_raw(archive)
            paths = self.inputs()
            names = (C.c_char_p * (len(paths) + 1))(*paths, None)
            opened = n.archive_read_open_filenames(archive, names, 1024 * 1024)
            if opened < OK:
                self.error('OPEN_ERROR', n.message(archive))
                if opened < WARN:
                    return
            header = C.c_void_p()
            buffer = C.create_string_buffer(1024 * 1024)
            retries = 0
            while True:
                result = n.archive_read_next_header(archive, C.byref(header))
                if result == EOF:
                    self.ended = True
                    self.format = (n.archive_format_name(archive) or b'unknown').decode('utf-8', 'replace')
                    break
                if result == RETRY:
                    retries += 1
                    if retries <= 3:
                        continue
                retries = 0
                if result < WARN:
                    self.error('HEADER_ERROR', n.message(archive))
                    break
                self.ordinal += 1
                if self.ordinal > int(self.cfg['max_members']):
                    raise Limit('Member-count limit reached')
                e = header.value
                self.format = (n.archive_format_name(archive) or b'unknown').decode('utf-8', 'replace')
                if self.format == 'raw' and n.archive_filter_count(archive) < 2:
                    self.error('NOT_ARCHIVE', 'Raw, uncompressed data is not an archive')
                    break
                raw = n.archive_entry_pathname_utf8(e)
                if raw is None:
                    raw = n.archive_entry_pathname(e)
                if self.format == 'raw' and raw == b'data':
                    raw = self.cfg['raw_name'].encode('utf-8')
                path, unsafe = safe_path(raw)
                filetype = n.archive_entry_filetype(e)
                kind = ('HARDLINK' if n.archive_entry_hardlink(e) is not None else
                        'FILE' if filetype == stat.S_IFREG else
                        'DIRECTORY' if filetype == stat.S_IFDIR else
                        'SYMLINK' if filetype == stat.S_IFLNK else 'OTHER')
                declared = n.archive_entry_size(e) if n.archive_entry_size_is_set(e) else None
                has_time = n.archive_entry_mtime_is_set(e)
                row = dict(event='member', ordinal=self.ordinal, path=path,
                           raw_path_base64=base64.b64encode((raw or b'')[:MAX_PATH_BYTES]).decode('ascii'),
                           kind=kind, declared_size=declared, actual_size=None,
                           modified_sec=n.archive_entry_mtime(e) if has_time else None,
                           modified_nano=n.archive_entry_mtime_nsec(e) if has_time else None,
                           encrypted=bool(n.archive_entry_is_encrypted(e)), integrity='METADATA',
                           payload=None, diagnostic=None)
                warning = n.message(archive) if result == WARN else None
                if warning:
                    self.error('HEADER_WARNING', warning, self.ordinal)
                if unsafe:
                    row.update(integrity='SKIPPED', diagnostic=unsafe)
                    self.error(unsafe, 'Rejected archive member name', self.ordinal, 'SAFETY')
                    emit(row)
                    if not self.skip(archive, self.ordinal):
                        break
                    continue
                if kind != 'FILE':
                    emit(row)
                    if not self.skip(archive, self.ordinal):
                        break
                    continue
                if row['encrypted']:
                    row.update(integrity='ENCRYPTED', diagnostic='No password supplied')
                    self.error('ENCRYPTED', row['diagnostic'], self.ordinal)
                    emit(row)
                    if not self.skip(archive, self.ordinal):
                        break
                    continue
                count = 0
                damaged = warning
                if declared is not None and declared < 0:
                    damaged = 'Negative declared member size'
                if declared is not None and declared >= 0:
                    self.space(declared)
                payload = str(self.ordinal)
                with open(payload, 'xb', buffering=0) as out:
                    while True:
                        got = n.archive_read_data(archive, buffer, len(buffer))
                        if got == 0:
                            break
                        if got < 0:
                            damaged = n.message(archive)
                            break
                        self.space(got)
                        out.write(buffer.raw[:got])
                        self.total += got
                        count += got
                if declared is not None and count != declared:
                    damaged = damaged or 'Declared size does not match recovered bytes'
                row.update(actual_size=count, payload=payload,
                           integrity='DAMAGED' if damaged else 'READ_OK', diagnostic=damaged)
                if damaged:
                    self.error('MEMBER_DATA_ERROR', damaged, self.ordinal)
                emit(row)
                # Try subsequent headers after a data failure. Solid/corrupt
                # formats may be unable to resynchronize; never fabricate members.
        except Limit as exc:
            self.limited = True
            self.error('RESOURCE_LIMIT', exc, category='LIMIT')
        except OSError as exc:
            self.operational = True
            self.error('TEMP_IO_ERROR' if exc.errno == errno.ENOSPC else 'IO_ERROR', exc, category='OPERATIONAL')
        finally:
            result = n.archive_read_free(archive)
            if result < OK:
                self.error('CLOSE_ERROR', 'Native reader reported an error on close')
        self.verify_container()

    def verify_container(self):
        try:
            fmt = (self.format or '').lower()
            last = Path(self.cfg['volumes'][-1])
            if fmt.startswith('zip'):
                with last.open('rb') as src:
                    src.seek(max(0, last.stat().st_size - 65577))
                    tail = src.read(65577)
                at = tail.rfind(b'PK\x05\x06')
                valid = at >= 0 and len(tail) >= at + 22
                if valid:
                    _, disk, _, _, entries, _, _, comment = struct.unpack_from('<4s4H2IH', tail, at)
                    valid = at + 22 + comment == len(tail)
                    disks = disk + 1
                    if disk == 65535:
                        valid = valid and at >= 20 and tail[at-20:at-16] == b'PK\x06\x07'
                        if valid:
                            disks = struct.unpack_from('<I', tail, at-4)[0]
                    expected = len(self.cfg['volumes']) if self.cfg['flavor'] == 'zip-split' else 1
                    valid = valid and disks == expected
                    if self.ended and entries != 65535 and self.ordinal != entries:
                        valid = False
                self.volume_complete = valid
                if not valid:
                    self.error('INCOMPLETE_ZIP_CATALOG', 'ZIP end record, member count, or volume count is incomplete/inconsistent')
            elif 'rar' in fmt:
                multi, final = rar_end(last)
                if final is False:
                    self.volume_complete = False
                    self.error('MISSING_VOLUMES', 'Last available RAR volume declares a following volume')
                elif final is None and (multi or len(self.cfg['volumes']) > 1 or self.cfg['flavor'] == 'rar-parts'):
                    self.volume_complete = False
                    self.error('VOLUME_COMPLETENESS_UNKNOWN', 'RAR final-volume marker could not be verified')
            elif '7-zip' in fmt:
                with open(self.cfg['volumes'][0], 'rb') as src:
                    header = src.read(32)
                if len(header) == 32 and header.startswith(b'7z\xbc\xaf\x27\x1c'):
                    offset, size = struct.unpack_from('<QQ', header, 12)
                    available = sum(Path(p).stat().st_size for p in self.cfg['volumes'])
                    self.volume_complete = available >= 32 + offset + size
                    if not self.volume_complete:
                        self.error('MISSING_VOLUMES', '7z next-header extent exceeds available volumes')
        except (OSError, ValueError, struct.error) as exc:
            self.volume_complete = False
            self.error('VOLUME_CHECK_FAILED', exc)

    def summary(self):
        return dict(event='summary', protocol=PROTOCOL,
                    provider=self.n.archive_version_details().decode('ascii', 'replace'),
                    format=self.format, reached_eof=self.ended, members=self.ordinal,
                    errors=self.errors, temporary_bytes=self.total,
                    operational=self.operational, limited=self.limited,
                    volume_complete=self.volume_complete)


def main():
    parent_guard(int(sys.argv[1]))
    native = Native()
    if len(sys.argv) > 2 and sys.argv[2] == '--version':
        emit(dict(event='version', protocol=PROTOCOL,
                  provider=native.archive_version_details().decode('ascii', 'replace')))
        return 0
    config = json.loads(sys.stdin.buffer.read(32 * 1024 * 1024 + 1))
    resource.setrlimit(resource.RLIMIT_AS, (int(config['native_memory_bytes']),) * 2)
    os.umask(0o077)
    with open('.extract-lock', 'a+b') as lease:
        fcntl.lockf(lease, fcntl.LOCK_EX)
        reader = Reader(config, native)
        reader.run()
        emit(reader.summary())
        return 4 if reader.operational else 3 if reader.errors else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except NativeUnavailable as exc:
        emit(dict(event='fatal', category='CAPABILITY', code='NATIVE_RUNTIME_UNAVAILABLE',
                  message=str(exc)[:4096]))
        sys.exit(4)
    except Exception as exc:
        emit(dict(event='fatal', category='OPERATIONAL', code='HELPER_FAILED',
                  message=str(exc)[:4096]))
        sys.exit(4)
