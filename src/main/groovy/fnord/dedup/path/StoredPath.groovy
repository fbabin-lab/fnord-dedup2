package fnord.dedup.path

import groovy.transform.CompileStatic
import java.nio.file.*

/** Host-independent stored identities. Only materialize() crosses into local filesystem I/O. */
@CompileStatic
final class StoredPath {
    enum Style { POSIX, WINDOWS_DRIVE, WINDOWS_UNC, UNKNOWN }
    static boolean windows() { FileSystems.default.separator == '\\' }

    static boolean windowsHost() { windows() }
    static Style classify(String root) {
        try { return style(root) } catch (IllegalArgumentException ignored) { return Style.UNKNOWN }
    }
    static boolean isForeign(String root) {
        Style kind = style(root)
        windows() ? kind == Style.POSIX : kind != Style.POSIX
    }
    static Path nativeRoot(String root) { materialize(root) }
    static Path resolve(String root, String relative) { materialize(root, relative) }
    static Path resolve(Path root, String relative) { materialize(storeAbsolute(root), relative) }
    static String join(String root, String relative) { display(root, relative) }

    static String storeRelative(Path relative) {
        if (relative == null || relative.isAbsolute() || relative.root != null) throw new IllegalArgumentException('Relative native path required')
        if (relative.toString().isEmpty()) return ''
        List<String> parts = new ArrayList<>()
        for (Path component : relative) parts.add(component.toString())
        String stored = String.join('/', parts)
        validateComponents(stored, relative.fileSystem.separator == '\\', true)
        return stored
    }

    static Style style(String root) {
        if (root == null || root.isEmpty() || root.indexOf(0) >= 0) throw new IllegalArgumentException('Invalid stored root')
        Style kind
        String tail
        if (root ==~ /[A-Za-z]:\/.*/) {
            kind = Style.WINDOWS_DRIVE
            tail = root.substring(3)
        } else if (root.startsWith('//')) {
            kind = Style.WINDOWS_UNC
            String[] parts = root.substring(2).split('/', -1)
            if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) throw new IllegalArgumentException('UNC root needs a server and share')
            tail = root.substring(2)
        } else if (root.startsWith('/')) {
            kind = Style.POSIX
            tail = root.substring(1)
        } else throw new IllegalArgumentException('Stored root must be absolute (POSIX, drive or UNC)')
        validateComponents(tail, kind != Style.POSIX, true)
        return kind
    }

    static void validateRelative(String root, String relative) {
        Style kind = style(root)
        if (relative == null || relative.startsWith('/')) throw new IllegalArgumentException('Invalid stored relative path')
        validateComponents(relative, kind != Style.POSIX, true)
    }

    private static void validateComponents(String tail, boolean win, boolean emptyAllowed) {
        if (tail.isEmpty() && emptyAllowed) return
        for (String part : tail.split('/', -1)) {
            if (part.isEmpty() || part == '.' || part == '..' || part.indexOf(0) >= 0)
                throw new IllegalArgumentException('Path contains empty, dot, traversal or NUL components')
            if (win) {
                if (part ==~ /.*[\\<>:"|?*\x00-\x1f].*/ || part.endsWith(' ') || part.endsWith('.'))
                    throw new IllegalArgumentException('Unsupported Windows path component')
                String stem = part.split('\\.', 2)[0].toUpperCase(Locale.ROOT)
                if (stem ==~ /CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]/)
                    throw new IllegalArgumentException('Windows device names are not filesystem scan paths')
            }
        }
    }

    /** Explicit Windows input only; never use this on an arbitrary POSIX filename. */
    static String encodeWindowsAbsolute(String value) {
        if (value == null) throw new IllegalArgumentException('Missing Windows root')
        String s = value
        if (s.regionMatches(true, 0, '\\\\?\\UNC\\', 0, 8)) s = '\\\\' + s.substring(8)
        else if (s.startsWith('\\\\?\\')) {
            s = s.substring(4)
            if (!(s ==~ /[A-Za-z]:[\\\/].*/)) throw new IllegalArgumentException('Windows device/volume namespace is unsupported')
        }
        if (s.startsWith('\\\\.\\')) throw new IllegalArgumentException('Windows device namespace is unsupported')
        // This conversion is scoped to a known Windows lexical representation.
        s = s.replace('\\', '/')
        if (s ==~ /[A-Za-z]:\/.*/) s = s.substring(0,1).toUpperCase(Locale.ROOT) + s.substring(1)
        if (s.length() > 3 && s.endsWith('/')) s = s.substring(0,s.length()-1)
        Style kind = style(s)
        if (kind == Style.POSIX) throw new IllegalArgumentException('Windows root must include drive or UNC share')
        return s
    }

    static String storeAbsolute(Path path) {
        if (!path.isAbsolute()) throw new IllegalArgumentException('Only absolute native roots can be stored')
        String result
        if (path.fileSystem.separator == '\\') result = encodeWindowsAbsolute(path.toString())
        else {
            List<String> parts = new ArrayList<>()
            for (Path component : path) parts.add(component.toString())
            result = '/' + String.join('/',parts)
        }
        style(result)
        return result
    }

    static String storeRelative(Path root, Path child) {
        if (!child.startsWith(root)) throw new IllegalArgumentException('Child is outside scan root')
        Path relative = root.relativize(child)
        if (relative.toString().isEmpty()) return ''
        List<String> parts = new ArrayList<>()
        for (Path component : relative) parts.add(component.toString())
        String result = String.join('/', parts)
        validateRelative(storeAbsolute(root), result)
        return result
    }

    static String display(String root, String relative) {
        validateRelative(root, relative)
        if (relative.isEmpty()) return root
        return root + (root.endsWith('/') ? '' : '/') + relative
    }

    static Path materialize(String root, String relative = '') {
        validateRelative(root, relative)
        Style kind = style(root)
        if ((kind == Style.POSIX) == windows()) throw new ForeignStoredPathException(root, kind)
        Path nativeRoot = Path.of(root)
        if (!nativeRoot.isAbsolute()) throw new IllegalArgumentException('Stored root is not locally absolute')
        Path result = nativeRoot
        if (!relative.isEmpty()) for (String component : relative.split('/', -1)) {
            // A POSIX backslash stays inside a component; Windows was validated above.
            result = result.resolve(component)
        }
        if (!result.startsWith(nativeRoot)) throw new IllegalArgumentException('Stored path escapes root')
        return result
    }

    static void validateRequestedRoot(Path requested) {
        if (requested == null) throw new IllegalArgumentException('A scan root is required')
        if (windows()) {
            String s = requested.toString()
            if (s ==~ /(?i)[a-z]:(?![\\\/]).*/ || (!requested.isAbsolute() && requested.root != null))
                throw new IllegalArgumentException('Drive-relative and current-drive-rooted paths are not supported')
            if (s.startsWith('\\\\?\\') || s.startsWith('\\\\.\\')) encodeWindowsAbsolute(s)
        }
    }

    static Path canonicalRoot(Path requested) {
        validateRequestedRoot(requested)
        Path root = requested.toRealPath()
        String stored = storeAbsolute(root)
        // Serialization must not normalize meaningful components silently.
        Path normal = materialize(stored)
        if (!Files.isSameFile(root, normal)) throw new IllegalArgumentException('Root has no unambiguous portable representation')
        return normal
    }
}

class ForeignStoredPathException extends IOException {
    final StoredPath.Style style
    final String storedRoot
    ForeignStoredPathException(String root, StoredPath.Style style) {
        super('Scan root belongs to another platform and cannot be opened on this host: ' + root)
        this.storedRoot = root; this.style = style
    }
}
