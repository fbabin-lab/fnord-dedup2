import fnord.dedup.Dedup
import fnord.dedup.StopToken
import fnord.dedup.archive.ArchiveOptions
import groovy.json.JsonOutput
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Run with the distribution's fnord-dedup2-groovy launcher after discovery.
if (args.length < 2 || args.length > 3) {
    System.err.println('Usage: archives.groovy DATABASE SCAN_NAME [TEMP_DIRECTORY]')
    System.exit(2)
}

StopToken stop = new StopToken()
CountDownLatch finished = new CountDownLatch(1)
Thread hook = new Thread({ ->
    stop.cancel()
    try { finished.await(30, TimeUnit.SECONDS) }
    catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
} as Runnable, 'archive-example-shutdown')
Runtime.runtime.addShutdownHook(hook)
int code = 0
try {
    ArchiveOptions options = new ArchiveOptions()
    if (args.length == 3) options.tempDirectory = Path.of(args[2])
    Dedup.open(Path.of(args[0])).withCloseable { d ->
        d.progress = { Map event -> System.err.println(JsonOutput.toJson(event)) }
        Map status = d.analyzeArchives(args[1], options, stop)
        System.err.println(JsonOutput.toJson(status))
        // Streaming callbacks must not issue another query on the same engine.
        d.eachArchive(args[1]) { Map row -> println JsonOutput.toJson(row) }
        code = stop.cancelled ? 130 : (status.errors as long) > 0 ? 3 : 0
    }
} finally {
    finished.countDown()
    try { Runtime.runtime.removeShutdownHook(hook) }
    catch (IllegalStateException ignored) { /* JVM is already shutting down. */ }
}
System.exit(code)
