package fnord.dedup.path

import groovy.transform.CompileStatic
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** Type checks precede directory/regular predicates: Windows junctions may also be isOther(). */
@CompileStatic
final class NativeFiles {
    static String kind(BasicFileAttributes attrs) {
        if (attrs.isSymbolicLink()) return 'SYMLINK'
        if (attrs.isOther()) return 'OTHER'
        if (attrs.isRegularFile()) return 'FILE'
        if (attrs.isDirectory()) return 'DIRECTORY'
        return 'OTHER'
    }

    static void requireDirectory(Path path) {
        if (kind(Files.readAttributes(path, BasicFileAttributes, LinkOption.NOFOLLOW_LINKS)) != 'DIRECTORY')
            throw new IOException('Directory changed type or is a link/reparse point: ' + path)
    }

    /** Recheck queued Windows ancestors: a directory may have become a junction after discovery. */
    static void checkAncestors(Path root, Path path) {
        if (!StoredPath.windows()) return
        if (!path.startsWith(root)) throw new IOException('Path escapes scan root')
        requireDirectory(root)
        Path current = root
        Path parent = path.parent
        if (parent == null || !parent.startsWith(root)) return
        Path relative = root.relativize(parent)
        if (relative.toString().isEmpty()) return
        for (Path part : relative) {
            current = current.resolve(part)
            requireDirectory(current)
        }
    }

    /** Canonicalize the existing prefix of a control path, retaining not-yet-created suffixes.
     * Only for exclusion comparisons; never use this to choose a source traversal target.
     * Windows short-name parents may differ textually from the DB's canonical parent.
     */
    static Path canonicalControlPath(Path requested) {
        Path probe = requested.toAbsolutePath().normalize()
        List<Path> suffix = new ArrayList<>()
        while (probe != null) {
            try {
                Path canonical = probe.toRealPath()
                for (int i = suffix.size() - 1; i >= 0; i--) canonical = canonical.resolve(suffix.get(i))
                return canonical
            } catch (NoSuchFileException missing) {
                if (probe.fileName == null) throw missing
                suffix.add(probe.fileName)
                probe = probe.parent
            }
        }
        throw new NoSuchFileException(requested.toString())
    }

    static void requireLinux(String feature) {
        if (!System.getProperty('os.name','').toLowerCase(Locale.ROOT).contains('linux'))
            throw new UnsupportedOperationException(feature + ' processing is available only on Linux; Windows supports core scanning, hashes and database reports/merge only')
    }
}
