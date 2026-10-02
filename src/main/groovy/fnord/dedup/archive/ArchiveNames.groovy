package fnord.dedup.archive

import groovy.transform.CompileStatic
import java.util.regex.Matcher

/** Only a cheap candidate filter, never proof of archive validity. Case-sensitive set stems. */
@CompileStatic
final class ArchiveNames {
    static final String SQL_PATTERN = '(?i)\\.(zip|tar|tgz|tbz2?|txz|tzst|gz|bz2|xz|zst|rar|7z|r[0-9]{2,3}|z[0-9]{2,3}|7z\\.[0-9]+|zip\\.[0-9]+)$'

    static Map describe(String path) {
        if (path == null) return null
        int slash = path.lastIndexOf('/')
        String dir = slash < 0 ? '' : path.substring(0, slash + 1)
        String name = path.substring(slash + 1)
        Matcher m = name =~ /(?i)^(.*)\.part([0-9]+)\.rar$/
        if (m.matches()) return info(dir + m.group(1), 'rar-parts', number(m.group(2)))
        m = name =~ /(?i)^(.*\.(7z|zip))\.([0-9]+)$/
        if (m.matches()) return info(dir + m.group(1), m.group(2).equalsIgnoreCase('7z') ? '7z-split' : 'zip-chunks', number(m.group(3)))
        m = name =~ /(?i)^(.*)\.r([0-9]{2,3})$/
        if (m.matches()) return info(dir + m.group(1), 'rar-old', number(m.group(2)) + 1)
        m = name =~ /(?i)^(.*)\.z([0-9]{2,3})$/
        if (m.matches()) return info(dir + m.group(1), 'zip-split', number(m.group(2)))
        m = name =~ /(?i)^(.*)\.rar$/
        if (m.matches()) return info(dir + m.group(1), 'rar-old', 0)
        m = name =~ /(?i)^(.*)\.zip$/
        if (m.matches()) return info(dir + m.group(1), 'zip-split', 0)
        if (name ==~ /(?i).*\.(tar|tgz|tbz2?|txz|tzst|gz|bz2|xz|zst|7z)$/) return info(path, 'single', 0)
        null
    }

    private static int number(String text) {
        try { return Integer.parseInt(text) } catch (NumberFormatException ignored) { return Integer.MAX_VALUE }
    }

    private static Map info(String stem, String flavor, int slot) {
        [group_key: flavor + ':' + stem, flavor: flavor, slot: slot]
    }

    /** Mutates bounded volume rows into canonical order; does not guess a missing final part. */
    static Map arrange(List<Map> volumes, String flavor) {
        volumes.sort { Map a, Map b ->
            long x = ((Number) a.slot).longValue(), y = ((Number) b.slot).longValue()
            if (flavor == 'zip-split') { if (x == 0L) x = Long.MAX_VALUE; if (y == 0L) y = Long.MAX_VALUE }
            Long.compare(x, y)
        }
        Set<Integer> slots = new HashSet<>()
        boolean ambiguous = false
        for (Map v : volumes) if (!slots.add(((Number) v.slot).intValue())) ambiguous = true
        boolean missing = false
        if (flavor == 'zip-split') {
            missing = !slots.contains(0)
            int expected = 1
            for (Map v : volumes) { int slot = ((Number) v.slot).intValue(); if (slot != 0 && slot != expected++) missing = true }
        } else if (flavor != 'single') {
            int expected = flavor == 'rar-old' ? 0 : 1
            for (Map v : volumes) if (((Number) v.slot).intValue() != expected++) missing = true
        }
        String readerFlavor = flavor
        if (volumes.size() == 1 && ((Number) volumes.get(0).slot).intValue() == 0) readerFlavor = 'single'
        [missing: missing, ambiguous: ambiguous, reader_flavor: readerFlavor]
    }
}
