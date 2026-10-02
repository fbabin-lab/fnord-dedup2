package fnord.dedup.archive

import fnord.dedup.StopToken
import java.nio.file.Path

/** A provider returns bounded, structured member/error events, never human listing lines.
 * Extraction is synchronous. Payload names are member ordinals. No source writes or shell.
 * The coordinator calls at most one provider at a time and alone owns JDBC.
 */
interface ArchiveProvider {
    String identity(Path work, ArchiveOptions options, StopToken stop)
    Map extract(Path work, Map configuration, ArchiveOptions options, StopToken stop, Closure event)
}
