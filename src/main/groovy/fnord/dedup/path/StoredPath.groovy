package fnord.dedup.path

import groovy.transform.CompileStatic
import java.nio.file.Path

/**
 * Codec between native java.nio Paths and the portable path identity persisted in DuckDB.
 * Stored paths always use '/' separators. Native filesystem access always uses Path.
 */
@CompileStatic
final class StoredPath {
    enum Style { POSIX, WINDOWS_DRIVE, WINDOWS_UNC, UNKNOWN }

    static boolean windowsHost() {
        System.getProperty('os.name', '').toLowerCase(Locale.ROOT).contains('win')
    }

    static Style classify(String value) {
        if (value == null || value.isEmpty()) return Style.UNKNOWN
        if (value ==~ /(?i)^[a-z]:\/.*$/) return Style.WINDOWS_DRIVE
        if (value.startsWith('//')) {
            String rest = value.substring(2)
            int first = rest.indexOf('/')
            if (first > 0 && first < rest.length() - 1) return Style.WINDOWS_UNC
            if (first > 0 && first == rest.length() - 1) return Style.UNKNOWN
            if (first > 0) return Style.WINDOWS_UNC
        }
        if (value.startsWith('/')) return Style.POSIX
        Style.UNKNOWN
    }

    static String storeAbsolute(Path nativePath) {
        if (nativePath == null || !nativePath.isAbsolute()) throw new IllegalArgumentException('Native path must be absolute')
        Path normalized = nativePath.normalize()
        Path root = normalized.root
        if (root == null) throw new IllegalArgumentException('Native path has no filesystem root')
        List<String> names = new ArrayList<>()
        for (Path part : normalized) names.add(part.toString())
        if (windowsHost()) {
            String portableRoot = windowsRoot(root.toString())
            if (portableRoot.endsWith('/')) return portableRoot + names.join('/')
            return names.empty ? portableRoot : portableRoot + '/' + names.join('/')
        }
        String rootText = root.toString()
        if (rootText != '/') throw new IllegalArgumentException('Unsupported non-POSIX filesystem root: ' + rootText)
        names.empty ? '/' : '/' + names.join('/')
    }

    static String storeRelative(Path relative) {
        if (relative == null) throw new IllegalArgumentException('Relative path must not be null')
        if (relative.isAbsolute()) throw new IllegalArgumentException('Relative path must not be absolute')
        if (relative.toString().isEmpty()) return ''
        List<String> names = new ArrayList<>()
        for (Path part : relative) {
            String text = part.toString()
            if (!text || text == '.' || text == '..') throw new IllegalArgumentException('Unsafe relative path component')
            names.add(text)
        }
        names.join('/')
    }

    static Path nativeRoot(String storedRoot) throws ForeignStoredPathException {
        Style style = classify(storedRoot)
        if (style == Style.UNKNOWN) throw new IllegalArgumentException('Unsupported stored root path: ' + storedRoot)
        boolean windows = windowsHost()
        if (windows && style == Style.POSIX) throw new ForeignStoredPathException(storedRoot, style)
        if (!windows && style in [Style.WINDOWS_DRIVE, Style.WINDOWS_UNC]) throw new ForeignStoredPathException(storedRoot, style)
        Path path = Path.of(storedRoot).normalize()
        if (!path.isAbsolute()) throw new IllegalArgumentException('Stored root is not absolute: ' + storedRoot)
        path
    }

    static Path resolve(Path nativeRoot, String storedRelative) {
        if (nativeRoot == null || !nativeRoot.isAbsolute()) throw new IllegalArgumentException('Native root must be absolute')
        if (storedRelative == null || storedRelative.isEmpty()) return nativeRoot
        if (storedRelative.startsWith('/')) throw new IllegalArgumentException('Stored relative path must not be absolute')
        String[] parts = storedRelative.split('/', -1)
        Path resolved = nativeRoot
        for (String part : parts) {
            if (!part || part == '.' || part == '..') throw new IllegalArgumentException('Unsafe stored relative path')
            if (windowsHost() && part.indexOf('\\') >= 0) throw new IllegalArgumentException('Backslash separator found in stored Windows relative path')
            resolved = resolved.resolve(part)
        }
        Path normalized = resolved.normalize()
        if (!normalized.startsWith(nativeRoot.normalize())) throw new IllegalArgumentException('Inventory path escapes its scan root')
        normalized
    }

    static Path resolve(String storedRoot, String storedRelative) throws ForeignStoredPathException {
        resolve(nativeRoot(storedRoot), storedRelative)
    }

    static String join(String storedRoot, String storedRelative) {
        if (classify(storedRoot) == Style.UNKNOWN) throw new IllegalArgumentException('Unsupported stored root path: ' + storedRoot)
        if (storedRelative == null || storedRelative.isEmpty()) return storedRoot
        if (storedRelative.startsWith('/')) throw new IllegalArgumentException('Stored relative path must not be absolute')
        storedRoot.endsWith('/') ? storedRoot + storedRelative : storedRoot + '/' + storedRelative
    }

    static boolean isForeign(String storedRoot) {
        Style style = classify(storedRoot)
        if (style == Style.UNKNOWN) return false
        windowsHost() ? style == Style.POSIX : style in [Style.WINDOWS_DRIVE, Style.WINDOWS_UNC]
    }

    static void validateRequestedRoot(Path requested) {
        if (requested == null) throw new IllegalArgumentException('Scan root is required')
        if (windowsHost()) {
            String text = requested.toString()
            String syntax = text.replace('\\', '/')
            if (syntax ==~ /(?i)^[a-z]:(?!\/).*$/) {
                throw new IllegalArgumentException('Windows drive-relative roots such as C:folder are not supported; use C:/folder')
            }
            String lower = syntax.toLowerCase(Locale.ROOT)
            if (lower.startsWith('//./') || lower.startsWith('//?/volume{')) {
                throw new IllegalArgumentException('Windows device/volume namespace roots are not supported')
            }
        }
    }

    private static String windowsRoot(String nativeRootText) {
        String root = nativeRootText.replace('\\', '/')
        if (root.regionMatches(true, 0, '//?/UNC/', 0, 8)) root = '//' + root.substring(8)
        else if (root.regionMatches(true, 0, '//?/', 0, 4)) root = root.substring(4)
        if (root ==~ /(?i)^[a-z]:\/?$/) return root.substring(0,1).toUpperCase(Locale.ROOT) + ':/'
        if (root.startsWith('//')) {
            while (root.endsWith('/') && root.length() > 2) root = root.substring(0, root.length() - 1)
            String rest = root.substring(2)
            String[] parts = rest.split('/', -1)
            if (parts.length < 2 || !parts[0] || !parts[1]) throw new IllegalArgumentException('Invalid UNC filesystem root: ' + nativeRootText)
            return '//' + parts[0] + '/' + parts[1]
        }
        throw new IllegalArgumentException('Unsupported Windows filesystem root: ' + nativeRootText)
    }
}

class ForeignStoredPathException extends IOException {
    final StoredPath.Style style
    final String storedRoot
    ForeignStoredPathException(String root, StoredPath.Style style) {
        super('Scan root belongs to another platform and cannot be opened on this host: ' + root)
        this.storedRoot = root
        this.style = style
    }
}
