package fnord.dedup.cli

import fnord.dedup.Dedup
import fnord.dedup.archive.ArchiveOptions
import groovy.json.JsonOutput
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(name='archives', mixinStandardHelpOptions=true, description='Analyze/resume archives after filesystem discovery. Native extraction is serial.')
class ArchivesCommand extends NamedCommand {
    @Option(names='--archive-temp', description='Temporary parent directory, outside the scanned tree.') Path temp
    @Option(names='--archive-max-depth', defaultValue='32') int maxDepth
    @Option(names='--archive-max-files', defaultValue='1000000') long maxFiles
    @Option(names='--archive-max-expanded-bytes', defaultValue='107374182400') long maxExpanded
    @Option(names='--archive-max-temp-bytes', defaultValue='107374182400') long maxTemp
    @Option(names='--archive-temp-min-free', defaultValue='1073741824') long minFree
    @Option(names='--archive-native-memory-bytes', defaultValue='2147483648') long nativeMemory
    @Option(names='--archive-timeout-seconds', defaultValue='3600') long timeout
    @Option(names='--archive-max-volumes', defaultValue='10000') int maxVolumes
    @Option(names='--python', defaultValue='/usr/bin/python3') String python
    @Option(names='--retry-errors', description='Retry partial results, excluding them from cache reuse.') boolean retryErrors
    @Option(names='--force', description='Reprocess all root archives and bypass completed-result caches.') boolean force

    @Override Integer call() {
        ArchiveOptions opts = new ArchiveOptions(tempDirectory:temp,maxDepth:maxDepth,maxMembers:maxFiles,
            maxExpandedBytes:maxExpanded,maxTempBytes:maxTemp,minFreeBytes:minFree,
            nativeMemoryBytes:nativeMemory,timeoutSeconds:timeout,maxVolumes:maxVolumes,python:python,
            retryErrors:retryErrors,force:force)
        parent.withEngine { Dedup d -> parent.report(d.analyzeArchives(name,opts,parent.stop)) } as Integer
    }
}

@Command(name='archive-status', mixinStandardHelpOptions=true, description='Archive coverage, status and error counts as JSON.')
class ArchiveStatusCommand extends NamedCommand {
    @Override Integer call() { parent.withEngine { Dedup d -> parent.json(d.archiveStatus(name)); 0 } as Integer }
}

@Command(name='archive-list', mixinStandardHelpOptions=true, description='Root and canonical nested archive occurrences as JSON Lines.')
class ArchiveListCommand extends NamedCommand {
    @Override Integer call() {
        parent.withEngine { Dedup d ->
            d.eachArchive(name) { Map row -> parent.spec.commandLine().out.println(JsonOutput.toJson(row)) }
            parent.spec.commandLine().out.flush(); 0
        } as Integer
    }
}

@Command(name='archive-errors', mixinStandardHelpOptions=true, description='Published archive/member errors as JSON Lines.')
class ArchiveErrorsCommand extends NamedCommand {
    @Override Integer call() {
        parent.withEngine { Dedup d ->
            d.eachArchiveError(name) { Map row -> parent.spec.commandLine().out.println(JsonOutput.toJson(row)) }
            parent.spec.commandLine().out.flush(); 0
        } as Integer
    }
}

abstract class ArchiveResultCommand implements Callable<Integer> {
    @ParentCommand Main parent
    @Option(names='--result', required=true, description='Published canonical result UUID from archive-list.') String result
}

@Command(name='archive-entries', mixinStandardHelpOptions=true, description='Stream one published archive result, preserving member ordinals.')
class ArchiveEntriesCommand extends ArchiveResultCommand {
    @Override Integer call() {
        parent.withEngine { Dedup d ->
            d.eachArchiveEntry(result) { Map row -> parent.spec.commandLine().out.println(JsonOutput.toJson(row)) }
            parent.spec.commandLine().out.flush(); 0
        } as Integer
    }
}

@Command(name='archive-volumes', mixinStandardHelpOptions=true, description='Stream canonical archive-volume fingerprints and provenance.')
class ArchiveVolumesCommand extends ArchiveResultCommand {
    @Override Integer call() {
        parent.withEngine { Dedup d ->
            d.eachArchiveVolume(result) { Map row -> parent.spec.commandLine().out.println(JsonOutput.toJson(row)) }
            parent.spec.commandLine().out.flush(); 0
        } as Integer
    }
}
