import fnord.dedup.Dedup
import fnord.dedup.StopToken
import fnord.dedup.image.ImageOptions
import groovy.json.JsonOutput
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

if (args.length < 2 || args.length > 3) {
    System.err.println('Usage: images.groovy DATABASE SCAN_NAME [TEMP_DIRECTORY]')
    System.exit(2)
}
StopToken stop = new StopToken()
CountDownLatch finished = new CountDownLatch(1)
Thread hook = new Thread({ ->
    stop.cancel()
    try { finished.await(30, TimeUnit.SECONDS) }
    catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
} as Runnable, 'image-example-shutdown')
Runtime.runtime.addShutdownHook(hook)
int code = 0
try {
    ImageOptions options = new ImageOptions()
    if (args.length == 3) options.tempDirectory = Path.of(args[2])
    Dedup.open(Path.of(args[0])).withCloseable { d ->
        d.progress = { Map event -> System.err.println(JsonOutput.toJson(event)) }
        Map status = d.analyzeImages(args[1], options, stop)
        System.err.println(JsonOutput.toJson(status))
        d.eachImage(args[1]) { Map row -> println JsonOutput.toJson(row) }
        code = stop.cancelled ? 130 : (status.errors as long) > 0 ? 3 : 0
    }
} finally {
    finished.countDown()
    try { Runtime.runtime.removeShutdownHook(hook) }
    catch (IllegalStateException ignored) { }
}
System.exit(code)
