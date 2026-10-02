package fnord.dedup.merge

import fnord.dedup.ScanOptions
import fnord.dedup.StopToken
import groovy.json.JsonOutput
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Command(name='merge-database.groovy',mixinStandardHelpOptions=true,
    description='Atomically import all scans from one fnord-dedup2 database into another. Duplicate names refuse the entire merge.')
class MergeCommand implements Callable<Integer> {
    @Option(names='--source',required=true) Path source
    @Option(names='--destination',required=true) Path destination
    @Option(names='--dry-run',description='Validate and plan only; both databases remain read-only.') boolean dryRun
    @Option(names='--quiet',description='Suppress progress on stderr.') boolean quiet
    @Option(names='--verbose',description='Include stack traces for troubleshooting.') boolean verbose
    @Option(names='--memory-limit',defaultValue='1GB',description='DuckDB memory budget, separate from JVM heap.') String memoryLimit
    @Option(names='--database-threads',defaultValue='2') int threads
    @Spec CommandLine.Model.CommandSpec spec

    @Override Integer call() {
        StopToken stop=new StopToken()
        CountDownLatch done=new CountDownLatch(1)
        Thread hook=new Thread({ stop.cancel(); done.await(30,TimeUnit.SECONDS) } as Runnable,'merge-shutdown')
        Runtime.runtime.addShutdownHook(hook)
        try {
            def service=new DatabaseMerger(new ScanOptions(memoryLimit:memoryLimit,databaseThreads:threads),stop)
            service.progress={ Map event -> if (!quiet) { spec.commandLine().err.println(JsonOutput.toJson(event)); spec.commandLine().err.flush() } }
            service.merge(source,destination,dryRun) { Map summary, Closure mappings ->
                def out=spec.commandLine().out
                String header=JsonOutput.toJson(summary)
                out.print(header.substring(0,header.length()-1)); out.print(',"scan_id_map":[')
                boolean first=true
                mappings { Map row -> if (!first) out.print(','); out.print(JsonOutput.toJson(row)); first=false }
                out.println(']}'); out.flush()
                if (out.checkError()) throw new IOException('Could not write the import report to stdout')
            }
            0
        } catch (DatabaseMergeException e) {
            spec.commandLine().err.println(JsonOutput.toJson([status:e.details.database_committed?'COMMITTED_WITH_REPORTING_ERROR':'REFUSED',code:e.code,message:e.message]+e.details))
            if (verbose) e.printStackTrace(spec.commandLine().err)
            e.exitCode
        } catch (CancellationException e) {
            spec.commandLine().err.println('Merge cancelled before commit; no imported data was committed.')
            130
        } catch (Exception e) {
            spec.commandLine().err.println(JsonOutput.toJson([status:'FAILED',message:e.message ?: e.class.simpleName]))
            if (verbose) e.printStackTrace(spec.commandLine().err)
            1
        } finally {
            done.countDown()
            try { Runtime.runtime.removeShutdownHook(hook) } catch (IllegalStateException ignored) { }
            spec.commandLine().err.flush()
        }
    }
    static int run(String[] args) { new CommandLine(new MergeCommand()).execute(args) }
}
