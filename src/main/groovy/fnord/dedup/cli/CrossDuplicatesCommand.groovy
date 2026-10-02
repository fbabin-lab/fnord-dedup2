package fnord.dedup.cli

import fnord.dedup.Dedup
import fnord.dedup.cross.CrossScanOptions
import groovy.json.JsonOutput
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

@Command(name='cross-duplicates', mixinStandardHelpOptions=true,
    description='Verify duplicates across explicitly named scans; hash only missing cross-scan size candidates.')
class CrossDuplicatesCommand implements Callable<Integer> {
    enum Format { table, text, jsonl }
    @ParentCommand Main parent
    @Option(names='--scan', required=true, arity='1', description='Exact scan name; repeat for at least two distinct scans. No comma splitting.')
    List<String> scans
    @Option(names='--format', defaultValue='table', description='Occurrence format: ${COMPLETION-CANDIDATES}. Summary/errors always go to stderr.')
    Format format

    @Override Integer call() {
        parent.withEngine { Dedup d ->
            def out = parent.spec.commandLine().out
            def err = parent.spec.commandLine().err
            String previous = ''
            long printed = 0L
            CrossScanOptions options = new CrossScanOptions(onError:{ Map error ->
                err.println(JsonOutput.toJson(error)); err.flush()
            })
            Map result = d.crossDuplicates(scans, options, parent.stop) { Map row ->
                if (format == Format.jsonl) out.println(JsonOutput.toJson(row))
                else {
                    if (previous != row.group_id) {
                        out.println("${row.copies} observations in ${row.scan_count} scans | ${row.size} bytes | SHA-256 ${row.sha256}")
                        previous = row.group_id as String
                    }
                    out.println('  [' + JsonOutput.toJson(row.scan_name) + '] ' + JsonOutput.toJson(row.path))
                }
                if (++printed % 1024L == 0L && out.checkError()) throw new IOException('Unable to write cross-scan result output')
            }
            out.flush()
            if (out.checkError()) throw new IOException('Unable to write cross-scan result output')
            // This final summary is not progress: --quiet must not hide incomplete coverage/errors.
            err.println(JsonOutput.toJson([event:'cross_scan_summary'] + result)); err.flush()
            result.cancelled ? 130 : result.partial ? 3 : 0
        } as Integer
    }
}
