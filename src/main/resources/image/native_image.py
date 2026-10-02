#!/usr/bin/python3
"""Structured QEMU/libguestfs bridge. Never boots a source guest or owns JDBC.

The outer supervisor retains the extraction lease until its worker/process group
has been terminated, including after abrupt JVM death. QEMU image decoders run
through safe_exec.py; libguestfs receives only an explicitly RAW, read-only NBD.
"""
import concurrent.futures
import contextlib
import ctypes
import fcntl
import hashlib
import json
import os
import pathlib
import re
import resource
import signal
import stat
import subprocess
import sys
import time

PROTOCOL = 1
MAX_RECORD = 262144


def emit(event, **fields):
    print(json.dumps(dict(event=event, protocol=PROTOCOL, **fields), ensure_ascii=True), flush=True)


class Failure(Exception):
    def __init__(self, code, message, category='OPERATIONAL'):
        super().__init__(message)
        self.code, self.category = code, category


def check_space(cfg, anticipated=0):
    st = os.statvfs(cfg['work'])
    if st.f_bavail * st.f_frsize < cfg['min_free_bytes'] + anticipated:
        raise Failure('TEMP_SPACE_EXHAUSTED', 'Image temporary filesystem reserve reached', 'LIMIT')


def guarded(cfg, argv, label):
    config = dict(cfg, argv=argv, inputs=[c['path'] for c in cfg['components']])
    path = os.path.join(cfg['work'], label + '-guard.json')
    with open(path, 'x', encoding='utf-8') as f:
        json.dump(config, f)
    return ['/usr/bin/python3', '-I', os.path.join(cfg['work'], 'safe_exec.py'), path]


def probe(cfg):
    # info opens only the primary node (BDRV_O_NO_BACKING). Supplying a nested
    # backing object here is rejected by QEMU 8. The NBD open below uses the
    # complete already-approved graph; probing must not implicitly open parents.
    primary = {k: v for k, v in cfg['graph'].items() if k != 'backing'}
    argv = ['/usr/bin/qemu-img', 'info', '--output=json', 'json:' + json.dumps(primary, separators=(',', ':'))]
    output = os.path.join(cfg['work'], 'probe-output')
    diagnostic = os.path.join(cfg['work'], 'probe-errors')
    with open(output, 'wb') as out, open(diagnostic, 'wb') as err:
        p = subprocess.Popen(guarded(cfg, argv, 'probe'), stdin=subprocess.DEVNULL, stdout=out, stderr=err)
        try:
            p.wait(timeout=min(cfg['timeout'], 120))
        except subprocess.TimeoutExpired:
            p.kill(); p.wait()
            raise Failure('TIMEOUT', 'Image metadata probe timed out')
    with open(diagnostic, 'rb') as f:
        error = f.read(65536).decode('utf-8', 'replace')
    if p.returncode:
        if 'Landlock' in error:
            raise Failure('ISOLATION_UNAVAILABLE', error)
        category = 'ENCRYPTION' if 'encrypt' in error.lower() else 'CAPABILITY' if any(t in error.lower() for t in ['not supported', 'unsupported', 'unknown driver']) else 'CONTENT'
        raise Failure('IMAGE_OPEN_FAILED', error or 'Native image probe failed', category)
    if os.path.getsize(output) > MAX_RECORD:
        raise Failure('PROTOCOL_LIMIT', 'Native image metadata exceeded limit', 'LIMIT')
    with open(output, encoding='utf-8') as f:
        info = json.load(f)
    if info.get('encrypted'):
        raise Failure('ENCRYPTED_UNREADABLE', 'Image encryption requires credentials', 'ENCRYPTION')
    if info.get('backing-filename') and not cfg['graph'].get('backing'):
        raise Failure('UNAPPROVED_DEPENDENCY', 'Native image reports an unapproved parent', 'SECURITY')
    return info


def nul_names(path):
    """Bounded parsing of a native NUL-delimited listing, including non-UTF8 names."""
    pending = bytearray()
    with open(path, 'rb') as stream:
        while block := stream.read(65536):
            for value in block:
                if value == 0:
                    yield bytes(pending)
                    pending.clear()
                else:
                    pending.append(value)
                    if len(pending) > 65536:
                        raise Failure('PATH_LIMIT', 'Guest path exceeds safe protocol bound', 'LIMIT')
        if pending:
            raise Failure('TRUNCATED_LISTING', 'Incomplete directory listing', 'FILESYSTEM')


def stream_hash(g, path, pool):
    """Download to a pipe, not a temporary copy. One reused reader thread counts bytes."""
    r, w = os.pipe()
    def consume():
        digest = hashlib.sha256()
        size = 0
        with os.fdopen(r, 'rb', buffering=0) as reader:
            while block := reader.read(1048576):
                digest.update(block)
                size += len(block)
        return size, digest.hexdigest()
    future = pool.submit(consume)
    try:
        g.download(path, '/dev/fd/' + str(w))
    finally:
        os.close(w)
    return future.result()


def inspect(cfg):
    import guestfs
    work = cfg['work']
    check_space(cfg)
    socket = os.path.join(work, 'disk.sock')
    argv = ['/usr/bin/qemu-nbd', '--read-only', '--persistent', '--shared=1', '--socket', '/proc/' + str(os.getpid()) + '/cwd/disk.sock',
            'json:' + json.dumps(cfg['graph'], separators=(',', ':'))]
    nbd_log = open(os.path.join(work, 'nbd-errors'), 'wb')
    nbd = subprocess.Popen(guarded(cfg, argv, 'nbd'), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=nbd_log)
    g = None
    errors = 0
    filesystems = 0
    entries = 0
    operational = False
    def error(code, message, fs=None, entry=None, category='FILESYSTEM'):
        nonlocal errors
        errors += 1
        emit('error', filesystem_id=fs, entry_id=entry, category=category, code=code, message=str(message)[:4096])
    try:
        deadline = time.monotonic() + 30
        while not os.path.exists(socket):
            if nbd.poll() is not None or time.monotonic() > deadline:
                nbd_log.flush()
                with open(nbd_log.name, 'rb') as f:
                    reason = f.read(65536).decode('utf-8', 'replace')
                raise Failure('IMAGE_OPEN_FAILED', reason or 'Read-only NBD did not start')
            time.sleep(.05)
        emit('progress', state='OPENING')
        g = guestfs.GuestFS(python_return_dict=True)
        g.set_backend('direct')
        g.set_backend_settings([] if cfg['acceleration'] == 'auto' else ['force_' + cfg['acceleration']])
        g.set_network(False)
        g.set_pgroup(False)
        g.set_recovery_proc(True)
        g.set_memsize(cfg['appliance_memory_mib'])
        g.set_tmpdir('/proc/' + str(os.getpid()) + '/cwd')
        g.set_sockdir('/proc/' + str(os.getpid()) + '/cwd')
        g.set_cachedir(cfg['appliance_cache'])
        g.add_drive_opts('', format='raw', readonly=True, protocol='nbd', server=['unix:/proc/' + str(os.getpid()) + '/cwd/disk.sock'])
        g.launch()
        emit('progress', state='INSPECTING')
        for device in g.list_devices():
            try:
                table = g.part_get_parttype(device)
                for p in g.part_list(device):
                    emit('partition', device=device, number=p['part_num'], start_bytes=p['part_start'], size_bytes=p['part_size'], table_type=table)
            except RuntimeError:
                pass  # A filesystem image legitimately has no partition table.
        found = g.list_filesystems()
        if not found:
            error('NO_FILESYSTEM', 'No accessible filesystem was discovered')
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            for fs, (device, fstype) in enumerate(sorted(found.items()), 1):
                filesystems += 1
                state = 'COMPLETE'
                initial_errors = errors
                record = dict(filesystem_id=fs, device=device, type=fstype, uuid=None, label=None, size_bytes=None)
                for key, method in [('uuid', 'vfs_uuid'), ('label', 'vfs_label'), ('size_bytes', 'blockdev_getsize64')]:
                    try:
                        record[key] = getattr(g, method)(device)
                    except RuntimeError:
                        pass
                if fstype in ['swap', 'LVM2_member']:
                    emit('filesystem', **record, state='NOT_A_FILESYSTEM')
                    continue
                if 'crypto' in fstype.lower() or 'bitlocker' in fstype.lower():
                    error('ENCRYPTED_UNREADABLE', 'Encrypted filesystem has no supplied credentials', fs, category='ENCRYPTION')
                    emit('filesystem', **record, state='ENCRYPTED')
                    continue
                try:
                    readonly_options = {'ext3':'ro,noload', 'ext4':'ro,noload', 'xfs':'ro,norecovery', 'btrfs':'ro,nologreplay'}
                    if fstype in readonly_options:
                        g.mount_options(readonly_options[fstype], device, '/')
                    else:
                        g.mount_ro(device, '/')
                except RuntimeError as e:
                    error('FILESYSTEM_UNMOUNTABLE', e, fs)
                    emit('filesystem', **record, state='UNMOUNTABLE')
                    continue
                emit('progress', state='SCANNING', filesystem_id=fs)
                queue = os.path.join(work, 'directories.queue')
                listing = os.path.join(work, 'directory.names')
                with open(queue, 'w', encoding='utf-8') as q:
                    q.write(json.dumps('/') + '\n')
                try:
                    with open(queue, 'r', encoding='utf-8') as pending, open(queue, 'a', encoding='utf-8') as append:
                        def entry(path):
                            nonlocal entries
                            if entries >= cfg['max_files']:
                                raise Failure('MAX_FILE_COUNT', 'Image member limit reached', 'LIMIT')
                            entries += 1
                            try:
                                s = g.lstatns(path)
                            except (RuntimeError, UnicodeError) as e:
                                error('METADATA_ERROR', e, fs, entries)
                                emit('entry', filesystem_id=fs, entry_id=entries, relative_path=path, filename=path.rsplit('/', 1)[-1] or '/',
                                     kind='OTHER', size=None, actual_size=None, modified_sec=None, modified_nano=None, sha256=None, integrity='UNREADABLE')
                                return
                            mode = s['st_mode']
                            kind = 'FILE' if stat.S_ISREG(mode) else 'DIRECTORY' if stat.S_ISDIR(mode) else 'SYMLINK' if stat.S_ISLNK(mode) else 'OTHER'
                            r = dict(filesystem_id=fs, entry_id=entries, relative_path=path, filename=path.rsplit('/', 1)[-1] or '/',
                                     kind=kind, size=s['st_size'], actual_size=None, modified_sec=s['st_mtime_sec'],
                                     modified_nano=s['st_mtime_nsec'], sha256=None, integrity='METADATA_ONLY')
                            if kind == 'FILE':
                                try:
                                    size, sha = stream_hash(g, path, pool)
                                    after = g.lstatns(path)
                                    if not stat.S_ISREG(after['st_mode']) or any(after[k] != s[k] for k in ['st_size', 'st_mtime_sec', 'st_mtime_nsec']) or size != s['st_size']:
                                        raise RuntimeError('Guest file size/type/time changed or read was incomplete')
                                    r.update(actual_size=size, sha256=sha, integrity='READ_OK')
                                except RuntimeError as e:
                                    r['integrity'] = 'UNREADABLE'
                                    error('FILE_READ_ERROR', e, fs, entries)
                            emit('entry', **r)
                            if kind == 'DIRECTORY' and path != '/':
                                append.write(json.dumps(path) + '\n')
                                append.flush()
                        entry('/')
                        while line := pending.readline():
                            directory = json.loads(line)
                            check_space(cfg)
                            resource.setrlimit(resource.RLIMIT_FSIZE, (cfg['max_listing_bytes'], cfg['max_temp_bytes']))
                            try:
                                g.ls0(directory, listing)
                            except RuntimeError as e:
                                error('DIRECTORY_READ_ERROR', e, fs)
                            finally:
                                resource.setrlimit(resource.RLIMIT_FSIZE, (cfg['max_temp_bytes'], cfg['max_temp_bytes']))
                            if os.path.exists(listing):
                                for raw in nul_names(listing):
                                    try:
                                        name = raw.decode('utf-8', 'strict')
                                        if name in ['', '.', '..'] or '/' in name:
                                            raise ValueError('Invalid native directory basename')
                                    except (UnicodeError, ValueError) as e:
                                        error('INVALID_FILENAME', str(e) + '; name bytes=' + raw[:256].hex(), fs)
                                        continue
                                    path = directory.rstrip('/') + '/' + name
                                    if len(path.encode('utf-8')) > 65536:
                                        error('PATH_LIMIT', 'Guest path exceeds safe protocol bound', fs)
                                        continue
                                    entry(path)
                                os.unlink(listing)
                            if os.path.getsize(queue) > cfg['max_listing_bytes']:
                                raise Failure('LISTING_LIMIT', 'Directory work queue exceeded metadata-spool limit', 'LIMIT')
                    state = 'PARTIAL' if errors > initial_errors else 'COMPLETE'
                except Failure as e:
                    error(e.code, str(e), fs, category=e.category)
                    state = 'PARTIAL'
                    if e.category == 'LIMIT':
                        raise
                finally:
                    emit('filesystem', **record, state=state)
                    g.umount_all()
                    for path in [queue, listing]:
                        with contextlib.suppress(FileNotFoundError):
                            os.unlink(path)
        return dict(errors=errors, filesystems=filesystems, entries=entries, retryable=operational)
    except Failure as e:
        error(e.code, str(e), category=e.category)
        return dict(errors=errors, filesystems=filesystems, entries=entries, retryable=e.category == 'OPERATIONAL')
    finally:
        if g is not None:
            with contextlib.suppress(Exception):
                g.close()
        nbd.terminate()
        try:
            nbd.wait(timeout=3)
        except subprocess.TimeoutExpired:
            nbd.kill(); nbd.wait()
        nbd_log.close()


def worker(cfg):
    try:
        cfg['work'] = os.getcwd()
        if os.geteuid() == 0:
            raise Failure('UNPRIVILEGED_REQUIRED', 'Run image analysis as an unprivileged user')
        resource.setrlimit(resource.RLIMIT_AS, (cfg['memory_bytes'], cfg['memory_bytes']))
        resource.setrlimit(resource.RLIMIT_FSIZE, (cfg['max_temp_bytes'], cfg['max_temp_bytes']))
        signal.signal(signal.SIGXFSZ, signal.SIG_IGN)
        if cfg['mode'] == 'version':
            import guestfs
            g = guestfs.GuestFS(python_return_dict=True)
            version = g.version()
            g.close()
            result = subprocess.run(['/usr/bin/qemu-img', '--version'], capture_output=True, timeout=10, check=True)
            libc = ctypes.CDLL(None)
            abi = libc.syscall(444, 0, 0, 1)
            if abi < 1:
                raise Failure('ISOLATION_UNAVAILABLE', 'Linux Landlock is required for image decoding')
            emit('version', provider='image-v1/' + json.dumps(version, sort_keys=True) + '/' + result.stdout.decode().splitlines()[0] + '/landlock-' + str(abi))
            return
        if cfg['mode'] == 'probe':
            info = probe(cfg)
            emit('summary', virtual_size=info['virtual-size'], format=info['format'])
        elif cfg['mode'] == 'inspect':
            emit('summary', **inspect(cfg))
        else:
            raise Failure('PROTOCOL_ERROR', 'Unknown image operation')
    except Failure as e:
        emit('fatal', code=e.code, category=e.category, message=str(e)[:4096])
        sys.exit(4)
    except Exception as e:
        emit('fatal', code='NATIVE_PROCESS_ERROR', category='OPERATIONAL', message=str(e)[:4096])
        sys.exit(4)


def supervise(parent_pid):
    raw = sys.stdin.buffer.read(MAX_RECORD + 1)
    if len(raw) > MAX_RECORD:
        raise Failure('PROTOCOL_LIMIT', 'Image configuration too large', 'LIMIT')
    cfg = json.loads(raw)
    cfg['work'] = os.getcwd()
    cfg.setdefault('memory_bytes', 4294967296)
    cfg.setdefault('max_temp_bytes', 1099511627776)
    cfg.setdefault('timeout', 14400)
    config = os.path.join(os.getcwd(), 'operation.json')
    with open(config, 'w', encoding='utf-8') as f:
        json.dump(cfg, f)
    stopped = False
    def cancel(signum, frame):
        nonlocal stopped
        stopped = True
    signal.signal(signal.SIGTERM, cancel)
    signal.signal(signal.SIGINT, cancel)
    with contextlib.ExitStack() as stack:
        lease = stack.enter_context(open('.extract-lock', 'r+b'))
        fcntl.lockf(lease, fcntl.LOCK_EX)
        if cfg.get('cache_lease'):
            shared = stack.enter_context(open(cfg['cache_lease'], 'r+b'))
            fcntl.lockf(shared, fcntl.LOCK_EX)
        process = subprocess.Popen([sys.executable, '-I', __file__, '--worker', config], start_new_session=True)
        end = time.monotonic() + (30 if cfg['mode'] == 'version' else cfg['timeout'])
        limit_error = None
        last_space_check = 0.0
        try:
            while process.poll() is None:
                if time.monotonic() - last_space_check >= 1 and cfg['mode'] != 'version':
                    last_space_check = time.monotonic()
                    try:
                        check_space(cfg)
                        total = 0
                        for base in [os.getcwd(), cfg.get('appliance_cache')]:
                            if base:
                                for directory, _, names in os.walk(base, followlinks=False):
                                    for name in names:
                                        with contextlib.suppress(FileNotFoundError):
                                            total += os.lstat(os.path.join(directory, name)).st_size
                        if total > cfg['max_temp_bytes']:
                            raise Failure('TEMP_LIMIT', 'Image temporary-byte limit reached', 'LIMIT')
                    except Failure as e:
                        limit_error = e
                        break
                if stopped or os.getppid() != parent_pid or time.monotonic() > end:
                    break
                time.sleep(.1)
        finally:
            with contextlib.suppress(ProcessLookupError):
                os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                pass
            with contextlib.suppress(ProcessLookupError):
                os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        if limit_error is not None:
            emit('fatal', code=limit_error.code, category=limit_error.category, message=str(limit_error))
            return 4
        if time.monotonic() > end:
            emit('fatal', code='TIMEOUT', category='OPERATIONAL', message='Image operation time limit reached')
            return 4
        return process.returncode if process.returncode >= 0 else 4


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--worker':
        with open(sys.argv[2], encoding='utf-8') as f:
            worker(json.load(f))
    else:
        sys.exit(supervise(int(sys.argv[1])))
