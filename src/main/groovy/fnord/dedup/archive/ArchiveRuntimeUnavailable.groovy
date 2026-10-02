package fnord.dedup.archive

/** Optional native archive runtime is unavailable; filesystem-only features remain usable. */
class ArchiveRuntimeUnavailable extends IOException {
    ArchiveRuntimeUnavailable(String message) { super(message) }
    ArchiveRuntimeUnavailable(String message, Throwable cause) { super(message, cause) }
}
