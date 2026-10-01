package fnord.dedup

import groovy.transform.CompileStatic
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** The one object deliberately safe to share with a cancelling thread. */
@CompileStatic
final class StopToken {
    private final AtomicBoolean flag = new AtomicBoolean(false)
    void cancel() { flag.set(true) }
    boolean isCancelled() { flag.get() || Thread.currentThread().isInterrupted() }
    void check() {
        if (isCancelled()) throw new CancellationException('Scan paused')
    }
}
