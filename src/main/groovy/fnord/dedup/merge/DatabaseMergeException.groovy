package fnord.dedup.merge

/** A bounded, actionable refusal rather than a stack trace for expected input problems. */
class DatabaseMergeException extends RuntimeException {
    final String code
    final int exitCode
    final Map details
    DatabaseMergeException(String code, String message, int exitCode = 3, Map details = [:]) {
        super(message)
        this.code = code; this.exitCode = exitCode; this.details = details
    }
}
