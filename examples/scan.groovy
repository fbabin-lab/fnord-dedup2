import fnord.dedup.Dedup
import fnord.dedup.ScanOptions
import groovy.json.JsonOutput
import java.nio.file.Path

if (args.length != 3) {
    System.err.println('Usage: fnord-dedup2-groovy examples/scan.groovy DATABASE NAME ROOT')
    System.exit(2)
}

Dedup.open(Path.of(args[0]), new ScanOptions(workers: 2)).withCloseable { d ->
    d.progress = { event -> System.err.println(JsonOutput.toJson(event)) }
    Map summary = d.scan(args[1], Path.of(args[2]))
    System.err.println(JsonOutput.prettyPrint(JsonOutput.toJson(summary)))
    d.eachDuplicate(args[1]) { row -> println JsonOutput.toJson(row) }
}
