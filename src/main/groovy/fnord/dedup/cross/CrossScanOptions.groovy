package fnord.dedup.cross

/** Hash/DB tuning comes from Dedup's ScanOptions. Errors are streamed on the owner thread. */
class CrossScanOptions {
    Closure onError = { Map ignored -> }
    int maxErrorSamples = 20

    CrossScanOptions validate() {
        if (onError == null) throw new IllegalArgumentException('onError must not be null')
        if (maxErrorSamples < 0 || maxErrorSamples > 1000) throw new IllegalArgumentException('maxErrorSamples must be 0..1000')
        this
    }
}
