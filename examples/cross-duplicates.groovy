import fnord.dedup.Dedup
import fnord.dedup.StopToken
import fnord.dedup.cross.CrossScanOptions
import groovy.json.JsonOutput
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

if (args.length < 3) {
    System.err.println('Usage: cross-duplicates.groovy DATABASE SCAN_NAME SCAN_NAME [SCAN_NAME ...]')
    System.exit(2)
}
StopToken stop = new StopToken()
CountDownLatch finished = new CountDownLatch(1)
Thread hook = new Thread({ ->
    stop.cancel()
    try { finished.await(30, TimeUnit.SECONDS) }
    catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
} as Runnable, 'cross-example-shutdown')
Runtime.runtime.addShutdownHook(hook)
int code = 1
try {
    Dedup.open(Path.of(args[0])).withCloseable { d ->
        CrossScanOptions options = new CrossScanOptions(onError: { Map error ->
            System.err.println(JsonOutput.toJson(error))
        })
        Map summary = d.crossDuplicates(args.drop(1).toList(), options, stop) { Map row ->
            println JsonOutput.toJson(row)
        }
        System.err.println(JsonOutput.toJson([event:'cross_scan_summary'] + summary))
        code = summary.cancelled ? 130 : summary.partial ? 3 : 0
    }
} finally {
    finished.countDown()
    try { Runtime.runtime.removeShutdownHook(hook) } catch (IllegalStateException ignored) { }
}
System.exit(code)
