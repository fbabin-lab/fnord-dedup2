package fnord.dedup.image

import fnord.dedup.StopToken
import java.nio.file.Path

/** Native storage interpretation only. The Groovy coordinator owns inventory, approval, hashes and JDBC. */
interface DiskImageProvider {
    String identity(Path work, ImageOptions options, StopToken stop)
    Map probe(Path work, Map config, ImageOptions options, StopToken stop)
    Map inspect(Path work, Map config, ImageOptions options, StopToken stop, Closure events)
}
