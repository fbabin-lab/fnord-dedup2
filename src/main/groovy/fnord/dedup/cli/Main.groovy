package fnord.dedup.cli

import fnord.dedup.Dedup
import fnord.dedup.ScanOptions
import fnord.dedup.StopToken
import groovy.json.JsonOutput
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import picocli.CommandLine.ScopeType
import picocli.CommandLine.Model.CommandSpec
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Command(name='fnord-dedup2', mixinStandardHelpOptions=true, version='fnord-dedup2 0.1.0',
    description='Resumable, read-only duplicate-file scanning with DuckDB.',
    subcommands=[ScanCommand, ResumeCommand, HashCommand, ListCommand, StatusCommand, DuplicatesCommand, ErrorsCommand])
class Main implements Runnable {
    @Option(names='--db', scope=ScopeType.INHERIT, defaultValue='scans.duckdb', description='DuckDB file (default: ${DEFAULT-VALUE}).')
    Path database
    @Option(names='--batch-size', scope=ScopeType.INHERIT, defaultValue='16384', description='Maximum rows per checkpoint/candidate page.')
    int batchSize
    @Option(names='--directory-batch-size', scope=ScopeType.INHERIT, defaultValue='128', description='Maximum directories per work page.')
    int directoryBatchSize
    @Option(names='--workers', scope=ScopeType.INHERIT, defaultValue='1', description='Hash workers, 1..64. Start with 1 for HDDs.')
    int workers
    @Option(names='--buffer-kib', scope=ScopeType.INHERIT, defaultValue='1024', description='Read buffer per hash worker, in KiB.')
    int bufferKiB
    @Option(names='--commit-interval-ms', scope=ScopeType.INHERIT, defaultValue='2000', description='Checkpoint interval while results are available.')
    long commitIntervalMillis
    @Option(names='--database-threads', scope=ScopeType.INHERIT, defaultValue='2', description='DuckDB query threads, independent of hash workers.')
    int databaseThreads
    @Option(names='--memory-limit', scope=ScopeType.INHERIT, defaultValue='1GB', description='DuckDB memory limit, e.g. 512MB or 4GB; separate from JVM heap.')
    String memoryLimit
    @Option(names='--quiet', scope=ScopeType.INHERIT, description='Suppress checkpoint progress on stderr.')
    boolean quiet
    @Spec CommandSpec spec
    final StopToken stop = new StopToken()
    private long lastProgressNanos = 0L
    private String lastStage = ''

    @Override void run() { spec.commandLine().usage(spec.commandLine().out) }

    def withEngine(Closure action) {
        ScanOptions options = new ScanOptions(batchSize: batchSize, directoryBatchSize: directoryBatchSize,
            workers: workers, bufferBytes: Math.multiplyExact(bufferKiB, 1024),
            commitIntervalMillis: commitIntervalMillis, databaseThreads: databaseThreads, memoryLimit: memoryLimit)
        Dedup.open(database, options).withCloseable { Dedup engine ->
            engine.progress = { Map event ->
                long now = System.nanoTime()
                if (!quiet && (event.stage != lastStage || now - lastProgressNanos >= 1_000_000_000L)) {
                    spec.commandLine().err.println(JsonOutput.toJson(event))
                    spec.commandLine().err.flush()
                    lastProgressNanos = now
                    lastStage = event.stage
                }
            }
            action.call(engine)
        }
    }

    void json(Object value) {
        spec.commandLine().out.println(JsonOutput.prettyPrint(JsonOutput.toJson(value)))
        spec.commandLine().out.flush()
    }

    int report(Map status) {
        json(status)
        stop.cancelled ? 130 : (status.errors as long) > 0L ? 3 : 0
    }

    static CommandLine commandLine(Main main = new Main()) {
        CommandLine cli = new CommandLine(main)
        cli.setExecutionExceptionHandler({ Exception failure, CommandLine command, CommandLine.ParseResult ignored ->
            command.err.println('Error: ' + JsonOutput.toJson(failure.message ?: failure.class.simpleName))
            command.err.flush()
            1
        } as CommandLine.IExecutionExceptionHandler)
        cli
    }

    static void main(String[] args) {
        Main main = new Main()
        CountDownLatch finished = new CountDownLatch(1)
        Thread hook = new Thread({ ->
            main.stop.cancel()
            try { finished.await(30, TimeUnit.SECONDS) }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
        } as Runnable, 'dedup-shutdown')
        Runtime.runtime.addShutdownHook(hook)
        int exitCode
        try { exitCode = commandLine(main).execute(args) }
        finally {
            finished.countDown()
            try { Runtime.runtime.removeShutdownHook(hook) }
            catch (IllegalStateException ignored) { /* JVM shutdown is already underway. */ }
        }
        System.exit(exitCode)
    }
}

abstract class NamedCommand implements Callable<Integer> {
    @ParentCommand Main parent
    @Option(names='--name', required=true, description='Named scan in this database.')
    String name
}

@Command(name='scan', mixinStandardHelpOptions=true, description='Create a new named scan, discover, then hash same-size files.')
class ScanCommand extends NamedCommand {
    @Option(names='--root', required=true, description='Root directory. Stored canonically for future resumes.') Path root
    @Option(names='--discover-only', description='Stop after collecting metadata; do not read file contents.') boolean discoverOnly
    @Override Integer call() {
        parent.withEngine { Dedup d -> parent.report(d.scan(name, root, parent.stop, discoverOnly)) } as Integer
    }
}

@Command(name='resume', mixinStandardHelpOptions=true, description='Resume saved discovery/hashing; reuse committed checksums.')
class ResumeCommand extends NamedCommand {
    @Option(names='--discover-only', description='Resume discovery without starting hashing.') boolean discoverOnly
    @Option(names='--rehash', description='Discard saved checksums and recompute candidates after discovery.') boolean rehash
    @Override Integer call() {
        parent.withEngine { Dedup d -> parent.report(d.resume(name, parent.stop, discoverOnly, rehash)) } as Integer
    }
}

@Command(name='hash', mixinStandardHelpOptions=true, description='Hash pending same-size candidates after completed discovery.')
class HashCommand extends NamedCommand {
    @Option(names='--rehash', description='Discard all saved checksums for this scan before hashing.') boolean rehash
    @Override Integer call() {
        parent.withEngine { Dedup d -> parent.report(d.hash(name, parent.stop, rehash)) } as Integer
    }
}

@Command(name='list', mixinStandardHelpOptions=true, description='List named scans as JSON.')
class ListCommand implements Callable<Integer> {
    @ParentCommand Main parent
    @Override Integer call() { parent.withEngine { Dedup d -> parent.json(d.listScans()); 0 } as Integer }
}

@Command(name='status', mixinStandardHelpOptions=true, description='Print scan phase and counts as JSON. Phase is not a liveness indicator.')
class StatusCommand extends NamedCommand {
    @Override Integer call() { parent.withEngine { Dedup d -> parent.json(d.status(name)); 0 } as Integer }
}

@Command(name='duplicates', mixinStandardHelpOptions=true, description='Stream matching files, grouped by size and SHA-256. Never deletes files.')
class DuplicatesCommand extends NamedCommand {
    enum Format { text, jsonl }
    @Option(names='--format', defaultValue='text', description='Output format: ${COMPLETION-CANDIDATES}.') Format format
    @Option(names='--allow-partial', description='Explicitly allow results from an unfinished scan.') boolean allowPartial
    @Override Integer call() {
        parent.withEngine { Dedup d ->
            Map scan = d.store.scan(name)
            if (scan.phase != 'COMPLETE') parent.spec.commandLine().err.println('Warning: results may be incomplete; inspect status and errors.')
            String previous = ''
            d.eachDuplicate(name, allowPartial) { Map row ->
                if (format == Format.jsonl) parent.spec.commandLine().out.println(JsonOutput.toJson(row))
                else {
                    String group = row.size.toString() + ':' + row.sha256
                    if (group != previous) {
                        parent.spec.commandLine().out.println("${row.copies} files | ${row.size} bytes each | SHA-256 ${row.sha256}")
                        previous = group
                    }
                    // Escaped names cannot inject new lines or terminal control sequences.
                    parent.spec.commandLine().out.println('  ' + JsonOutput.toJson(row.path))
                }
            }
            parent.spec.commandLine().out.flush()
            0
        } as Integer
    }
}

@Command(name='errors', mixinStandardHelpOptions=true, description='Stream scan errors as JSON Lines.')
class ErrorsCommand extends NamedCommand {
    @Override Integer call() {
        parent.withEngine { Dedup d ->
            d.eachError(name) { Map row -> parent.spec.commandLine().out.println(JsonOutput.toJson(row)) }
            parent.spec.commandLine().out.flush()
            0
        } as Integer
    }
}
