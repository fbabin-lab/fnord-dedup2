package fnord.dedup.archive

import fnord.dedup.store.DuckStore
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** Private per-database namespace. Only marked, owned attempt directories are deleted. */
class ArchiveTemp implements AutoCloseable {
    final Path namespace
    final String owner
    private final Set<Path> live = new LinkedHashSet<>()

    ArchiveTemp(DuckStore store, ArchiveOptions options, String instanceId, Path sourceRoot, String registry = 'archive_temp_roots') {
        if (!(registry in ['archive_temp_roots','image_temp_roots'])) throw new IllegalArgumentException('Unknown temporary registry')
        Path requested = options.tempDirectory ?: Path.of(store.database.toString() + '.archives-tmp')
        Files.createDirectories(requested)
        Path root = requested.toRealPath()
        if (root.startsWith(sourceRoot.toRealPath())) throw new IllegalArgumentException('Archive temporary directory must be outside the scanned source tree')
        owner = instanceId + '\n' + store.database.toString() + '\n'
        String key = HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(owner.getBytes('UTF-8'))).substring(0, 24)
        namespace = root.resolve('fnord-' + key)
        if (Files.isSymbolicLink(namespace)) throw new IOException('Archive temp namespace is a symlink')
        Files.createDirectories(namespace)
        Files.setPosixFilePermissions(namespace, PosixFilePermissions.fromString('rwx------'))
        Path marker = namespace.resolve('.fnord-owner')
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 4096 || Files.readString(marker) != owner) {
                throw new IOException('Unrecognized archive temporary namespace')
            }
        } else Files.writeString(marker, owner, StandardOpenOption.CREATE_NEW)
        // Persist all namespaces so a later invocation with a different temp option can reclaim the old one.
        store.exec("INSERT INTO ${registry} VALUES (?,?) ON CONFLICT DO NOTHING", namespace.toString(), owner)
        for (Map old : store.rows("SELECT path,owner FROM ${registry}")) {
            // A copied database must not clean the original database's live work.
            if (old.owner == owner) cleanupNamespace(Path.of(old.path as String), owner)
        }
    }

    private static void cleanupNamespace(Path base, String expected) {
        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) throw new IOException('Refusing symlink/non-directory archive namespace')
        Path marker = base.resolve('.fnord-owner')
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 4096 || Files.readString(marker) != expected) {
            throw new IOException('Archive cleanup ownership marker mismatch')
        }
        // No payloads exist until initialization is atomically renamed. A crash
        // while creating marker/lease can only leave these two control files.
        Files.newDirectoryStream(base, '.preparing-*').withCloseable { stream ->
            for (Path prep : stream) {
                if (!Files.isDirectory(prep, LinkOption.NOFOLLOW_LINKS)) throw new IOException('Unrecognized temp initialization path')
                Files.newDirectoryStream(prep).withCloseable { controls ->
                    for (Path control : controls) {
                        if (!(control.fileName.toString() in ['.fnord-owner','.extract-lock']) ||
                            !Files.isRegularFile(control, LinkOption.NOFOLLOW_LINKS) || Files.size(control) > 4096) {
                            throw new IOException('Unrecognized temp initialization contents')
                        }
                        Files.delete(control)
                    }
                }
                Files.delete(prep)
            }
        }
        Files.newDirectoryStream(base, 'attempt-*').withCloseable { stream ->
            for (Path path : stream) remove(path, expected)
        }
    }

    Path create() {
        Path work = Files.createTempDirectory(namespace, '.preparing-', PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString('rwx------')))
        Files.writeString(work.resolve('.fnord-owner'), owner, StandardOpenOption.CREATE_NEW)
        Files.createFile(work.resolve('.extract-lock'))
        work = Files.move(work, namespace.resolve('attempt-' + UUID.randomUUID()), StandardCopyOption.ATOMIC_MOVE)
        live.add(work)
        work
    }

    void release(Path work) {
        remove(work, owner)
        live.remove(work)
    }

    private static void remove(Path path, String expected) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException('Refusing non-directory archive cleanup target')
        Path marker = path.resolve('.fnord-owner')
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            // Recovery of the final cleanup steps: payloads are always removed
            // before these controls. Never remove data from an unmarked folder.
            List<Path> residue = []
            Files.newDirectoryStream(path).withCloseable { stream ->
                for (Path item : stream) {
                    if (residue.size() >= 1) throw new IOException('Unmarked temporary directory contains data')
                    residue.add(item)
                }
            }
            if (residue.any { it.fileName.toString() != '.extract-lock' ||
                    !Files.isRegularFile(it,LinkOption.NOFOLLOW_LINKS) || Files.size(it) != 0 }) {
                throw new IOException('Refusing unowned temporary directory: ' + path)
            }
            residue.each { Files.delete(it) }
            Files.delete(path)
            return
        }
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 4096 || Files.readString(marker) != expected) {
            throw new IOException('Refusing unowned temporary directory: ' + path)
        }
        Path lockPath = path.resolve('.extract-lock')
        if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) throw new IOException('Missing archive extraction lease')
        FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).withCloseable { channel ->
            def lock = channel.tryLock()
            if (lock == null) throw new IOException('Extractor is still using temporary data; retry after it exits')
            try {
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    @Override FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file != marker && file != lockPath) Files.delete(file)
                        FileVisitResult.CONTINUE
                    }
                    @Override FileVisitResult postVisitDirectory(Path dir, IOException error) {
                        if (error != null) throw error
                        if (dir != path) Files.delete(dir)
                        FileVisitResult.CONTINUE
                    }
                })
                Files.delete(marker)
                Files.delete(lockPath)
                Files.delete(path)
            } finally { lock.release() }
        }
    }

    @Override void close() {
        List<Path> paths = new ArrayList<>(live)
        Collections.reverse(paths)
        IOException failure = null
        for (Path path : paths) {
            try { release(path) } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e) }
        }
        if (failure != null) throw failure
    }
}
