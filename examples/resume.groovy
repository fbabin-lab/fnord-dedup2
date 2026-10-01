import fnord.dedup.Dedup
import fnord.dedup.StopToken
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

if (args.length != 2) {
    System.err.println('Usage: fnord-dedup2-groovy examples/resume.groovy DATABASE NAME')
    System.exit(2)
}

// Scripts embedding the API own their signal/lifecycle policy; the CLI installs
// this pattern automatically. Another thread may also call stop.cancel().
def stop = new StopToken()
def finished = new CountDownLatch(1)
def hook = new Thread({
    stop.cancel()
    finished.await(30, TimeUnit.SECONDS)
} as Runnable)
Runtime.runtime.addShutdownHook(hook)
try {
    Dedup.open(Path.of(args[0])).withCloseable { d ->
        println d.resume(args[1], stop)
    }
} finally {
    finished.countDown()
    try { Runtime.runtime.removeShutdownHook(hook) }
    catch (IllegalStateException ignored) { /* JVM is shutting down. */ }
}
