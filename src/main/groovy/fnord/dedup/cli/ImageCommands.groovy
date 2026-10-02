package fnord.dedup.cli

import fnord.dedup.Dedup
import fnord.dedup.image.ImageOptions
import groovy.json.JsonOutput
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(name='images',mixinStandardHelpOptions=true,description='Analyze saved disk-image candidates read-only; repeat this command to resume.')
class ImagesCommand extends NamedCommand {
    @Option(names='--image-temp') Path temp
    @Option(names='--image-acceleration',defaultValue='auto') String acceleration
    @Option(names='--image-container-hash',defaultValue='candidate') String containerHash
    @Option(names='--image-max-files',defaultValue='5000000') long maxFiles
    @Option(names='--image-max-temp-bytes',defaultValue='1099511627776') long maxTemp
    @Option(names='--image-temp-min-free',defaultValue='5368709120') long minFree
    @Option(names='--image-max-listing-bytes',defaultValue='268435456') long maxListing
    @Option(names='--image-timeout-seconds',defaultValue='14400') long timeout
    @Option(names='--image-native-memory-bytes',defaultValue='4294967296') long memory
    @Option(names='--image-appliance-memory-mib',defaultValue='768') int appliance
    @Option(names='--image-max-components',defaultValue='256') int components
    @Option(names='--python',defaultValue='/usr/bin/python3') String python
    @Option(names='--retry-errors') boolean retry
    @Option(names='--force') boolean force
    Integer call() {
        ImageOptions o=new ImageOptions(tempDirectory:temp,containerHash:containerHash,acceleration:acceleration,maxFiles:maxFiles,maxTempBytes:maxTemp,
          minFreeBytes:minFree,maxListingBytes:maxListing,timeoutSeconds:timeout,nativeMemoryBytes:memory,
          applianceMemoryMiB:appliance,maxComponents:components,python:python,retryErrors:retry,force:force)
        parent.withEngine { Dedup d -> parent.report(d.analyzeImages(name,o,parent.stop)) } as Integer
    }
}
@Command(name='image-status',mixinStandardHelpOptions=true,description='Image phase and persisted counts as JSON.')
class ImageStatusCommand extends NamedCommand {
    Integer call() { parent.withEngine { Dedup d -> parent.json(d.imageStatus(name)); 0 } as Integer }
}
@Command(name='image-list',mixinStandardHelpOptions=true,description='Stream source image jobs and canonical result IDs.')
class ImageListCommand extends NamedCommand {
    Integer call() { parent.withEngine { Dedup d -> d.eachImage(name) { parent.spec.commandLine().out.println(JsonOutput.toJson(it)) }; 0 } as Integer }
}
@Command(name='image-errors',mixinStandardHelpOptions=true,description='Stream finalized image errors.')
class ImageErrorsCommand extends NamedCommand {
    Integer call() { parent.withEngine { Dedup d -> d.eachImageError(name) { parent.spec.commandLine().out.println(JsonOutput.toJson(it)) }; 0 } as Integer }
}
abstract class ImageResultCommand implements Callable<Integer> {
    @ParentCommand Main parent
    @Option(names='--result',required=true) String result
    abstract String operation()
    Integer call() {
        parent.withEngine { Dedup d ->
            d."${operation()}"(result) { parent.spec.commandLine().out.println(JsonOutput.toJson(it)) }
            parent.spec.commandLine().out.flush(); 0
        } as Integer
    }
}
@Command(name='image-entries',mixinStandardHelpOptions=true,description='Stream guest entries of a finalized result.')
class ImageEntriesCommand extends ImageResultCommand { String operation() { 'eachImageEntry' } }
@Command(name='image-filesystems',mixinStandardHelpOptions=true,description='Stream discovered filesystems.')
class ImageFilesystemsCommand extends ImageResultCommand { String operation() { 'eachImageFilesystem' } }
@Command(name='image-partitions',mixinStandardHelpOptions=true,description='Stream discovered partitions.')
class ImagePartitionsCommand extends ImageResultCommand { String operation() { 'eachImagePartition' } }
@Command(name='image-components',mixinStandardHelpOptions=true,description='Stream physical components and backing-chain provenance.')
class ImageComponentsCommand extends ImageResultCommand { String operation() { 'eachImageComponent' } }
