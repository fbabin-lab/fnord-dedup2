package fnord.dedup.archive

import fnord.dedup.Dedup
import fnord.dedup.StopToken
import fnord.dedup.hash.Sha256Hasher
import groovy.json.JsonOutput
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Synchronous, depth-first archive coordinator. Only this thread accesses JDBC. */
class ArchiveAnalysis {
    final Dedup engine
    final ArchiveOptions options
    final ArchiveStore storage
    ArchiveProvider provider = new NativeArchiveProvider()
    private final Sha256Hasher hasher = new Sha256Hasher()
    private StopToken stop
    private ArchiveTemp temporary
    private String providerId
    private String policy
    private long occupiedBytes = 0L
    private long scanId

    ArchiveAnalysis(Dedup engine, ArchiveOptions options = new ArchiveOptions()) {
        this.engine = engine
        this.options = options.validate()
        storage = new ArchiveStore(engine.store, engine.options.batchSize)
    }

    Map analyze(String name, StopToken stop = new StopToken()) {
        this.stop = stop
        Map scan = engine.store.scan(name)
        scanId = scan.scan_id as long
        if (scan.phase == 'DISCOVERING') throw new IllegalStateException('Finish filesystem discovery before archive analysis')
        temporary = new ArchiveTemp(engine.store, options, storage.instanceId, Path.of(scan.root as String))
        try {
            storage.recover()
            Path probe = temporary.create()
            try { providerId = provider.identity(probe, options, stop) }
            finally { temporary.release(probe) }
            policy = digest(JsonOutput.toJson([provider:providerId, options:options.policy()]))
            storage.identify(scan, stop)
            engine.store.exec("UPDATE archive_runs SET phase='ANALYZING',updated_at=current_timestamp WHERE scan_id=?", scanId)
            long after = -1L
            while (true) {
                stop.check()
                List<Map> jobs = engine.store.rows('SELECT * FROM archive_jobs WHERE scan_id=? AND first_entry>? ORDER BY first_entry LIMIT 128', scanId, after)
                if (jobs.empty) break
                for (Map job : jobs) {
                    stop.check()
                    after = job.first_entry as long
                    List<Map> inputs = engine.store.rows('SELECT * FROM archive_inputs WHERE scan_id=? AND group_key=? ORDER BY slot,source_id LIMIT ?', scanId, job.group_key, options.maxVolumes + 1)
                    boolean belowMinimum = archiveSize(inputs) < options.minSizeBytes
                    if (!(belowMinimum || options.force || job.status in ['PENDING','SKIPPED'] || job.retryable || (options.retryErrors && job.status != 'COMPLETE'))) continue
                    if (belowMinimum && job.status == 'SKIPPED') continue
                    engine.store.exec("UPDATE archive_jobs SET status='RUNNING',result_id=NULL,duplicate=false,retryable=false,diagnostic=NULL WHERE scan_id=? AND group_key=?", scanId, job.group_key)
                    inputs.each { Map v -> v.path = Path.of(scan.root as String).resolve(v.relative_path as String) }
                    try {
                        Map found = process(inputs, job.flavor as String, 0, job.group_key as String, new LinkedHashSet<String>())
                        Map result = storage.result(found.result_id as String)
                        engine.store.exec('UPDATE archive_jobs SET status=?,result_id=?,duplicate=?,retryable=? WHERE scan_id=? AND group_key=?',
                            result.state,found.result_id,found.duplicate,result.retryable,scanId,job.group_key)
                    } catch (IOException failure) {
                        engine.store.exec("UPDATE archive_jobs SET status='PARTIAL',retryable=?,diagnostic=? WHERE scan_id=? AND group_key=?",
                            !(failure instanceof ArchiveSourceChanged), (failure.message ?: failure.class.simpleName).take(4096), scanId, job.group_key)
                    }
                }
            }
            Map status = storage.status(name)
            String phase = (status.errors as long) > 0 ? 'COMPLETE_WITH_ERRORS' : 'COMPLETE'
            engine.store.exec('UPDATE archive_runs SET phase=?,updated_at=current_timestamp WHERE scan_id=?', phase, scanId)
        } catch (CancellationException ignored) {
            engine.store.exec("UPDATE archive_runs SET phase=CASE WHEN phase='IDENTIFYING' THEN phase ELSE 'PAUSED' END,updated_at=current_timestamp WHERE scan_id=?", scanId)
        } finally {
            temporary.close()
        }
        storage.status(name)
    }

    private Map process(List<Map> volumes, String flavor, int depth, String label, Set<String> ancestors) {
        stop.check()
        String id = UUID.randomUUID().toString()
        engine.store.exec("INSERT INTO archive_results(result_id,scan_id,source_label,policy,provider,state) VALUES (?,?,?,?,?,'RUNNING')", id,scanId,label,policy,providerId)
        ArchiveBatch writer = storage.batch()
        long archiveBytes = archiveSize(volumes)
        if (archiveBytes < options.minSizeBytes) {
            int ordinal = 0
            for (Map volume : volumes) {
                writer.add('archive_volumes',[id,++ordinal,volume.slot,volume.source_id,volume.relative_path,volume.size,null])
            }
            writer.flush()
            Map summary = [reason:'below minimum archive size',archive_bytes:archiveBytes,minimum_bytes:options.minSizeBytes]
            engine.store.exec("UPDATE archive_results SET state='SKIPPED',retryable=false,reusable=false,height=0,summary_json=?,completed_at=current_timestamp WHERE result_id=?",
                JsonOutput.toJson(summary),id)
            engine.progress.call([stage:'archive',status:'SKIPPED',source:label,depth:depth,result_id:id,archive_bytes:archiveBytes,minimum_bytes:options.minSizeBytes])
            return [result_id:id,duplicate:false]
        }
        Map arrangement = ArchiveNames.arrange(volumes, flavor)
        if (volumes.empty || volumes.size() > options.maxVolumes || arrangement.ambiguous) {
            error(writer,id,null,'LIMIT',arrangement.ambiguous ? 'AMBIGUOUS_VOLUMES' : 'VOLUME_LIMIT', 'Archive volume set is ambiguous or exceeds the configured volume limit')
            writer.flush()
            finish(id, false, false, 0, [reason:'volume set rejected'])
            return [result_id:id,duplicate:false]
        }
        int ordinal = 0
        for (Map volume : volumes) {
            stop.check()
            BasicFileAttributes before = attributes(volume.path as Path)
            if (volume.containsKey('modified_sec')) validate(volume, before)
            volume.expected_size = before.size()
            volume.expected_sec = before.lastModifiedTime().toInstant().epochSecond
            volume.expected_nano = before.lastModifiedTime().toInstant().nano
            String hex = volume.sha256 as String
            if (!hex) {
                def value = hasher.hash(volume.path as Path, stop, engine.options.bufferBytes)
                if (value.bytesRead != before.size()) throw new ArchiveSourceChanged('Archive changed while checksumming: ' + volume.relative_path)
                hex = value.hex
            }
            volume.sha256 = hex
            validateCurrent(volume)
            writer.add('archive_volumes',[id,++ordinal,volume.slot,volume.source_id,volume.relative_path,before.size(),hex])
        }
        String fingerprint = fingerprint(volumes, flavor)
        engine.store.exec('UPDATE archive_results SET fingerprint=? WHERE result_id=?', fingerprint, id)
        boolean missing = arrangement.missing as boolean
        boolean eligible = !missing && !options.force
        if (eligible && depth <= options.maxDepth) {
            String states = options.retryErrors ? "state='COMPLETE'" : "state IN ('COMPLETE','PARTIAL')"
            List<Map> cache = engine.store.rows("SELECT result_id FROM archive_results WHERE fingerprint=? AND policy=? AND reusable AND ${states} AND height<=? ORDER BY completed_at,result_id LIMIT 1",
                fingerprint,policy,options.maxDepth-depth)
            if (!cache.empty) {
                // There are no alias chains: occurrences reference immutable results directly.
                writer.flush()
                engine.store.transaction {
                    engine.store.exec('DELETE FROM archive_volumes WHERE result_id=?', id)
                    engine.store.exec('DELETE FROM archive_results WHERE result_id=?', id)
                }
                engine.progress.call([stage:'archive',status:'DUPLICATE',source:label,result_id:cache[0].result_id])
                return [result_id:cache[0].result_id,duplicate:true]
            }
        }
        if (missing) error(writer,id,null,'CONTENT','MISSING_VOLUMES','Known volume gap or missing lead/final ZIP volume; attempt available data only')
        if (depth > options.maxDepth || ancestors.contains(fingerprint)) {
            error(writer,id,null,'LIMIT','DEPTH_LIMIT','Nested archive depth or active-ancestor fingerprint limit reached')
            writer.flush(); finish(id,false,false,0,[reason:'recursion limit'])
            return [result_id:id,duplicate:false]
        }
        long budget = Math.min(options.maxExpandedBytes, options.maxTempBytes - occupiedBytes)
        if (budget <= 0) {
            error(writer,id,null,'LIMIT','TEMP_STACK_LIMIT','No temporary byte budget remains for nested extraction')
            writer.flush(); finish(id,false,false,0,[reason:'temporary stack limit'])
            return [result_id:id,duplicate:false]
        }
        Path work = temporary.create()
        long retained = 0L
        boolean retryable = false
        boolean reusable = !missing
        int height = 0
        Map summary = [:]
        try {
            writer.flush()
            engine.progress.call([stage:'archive',status:'EXTRACTING',source:label,depth:depth,result_id:id])
            Map cfg = [volumes:volumes.collect { it.path.toString() },flavor:arrangement.reader_flavor,
                raw_compressed:true,raw_name:'data',
                byte_budget:budget,min_free_bytes:options.minFreeBytes,max_members:options.maxMembers,
                native_memory_bytes:options.nativeMemoryBytes]
            try {
                summary = provider.extract(work,cfg,options,stop) { Map event ->
                    stop.check()
                    if (event.event == 'error') {
                        error(writer,id,event.ordinal,'native' == event.category ? 'CONTENT' : event.category as String,event.code as String,event.message as String)
                    } else if (event.event == 'member') {
                        ingest(writer,id,work,event)
                    } else throw new IOException('Unknown archive helper event: ' + event.event)
                }
            } catch (IOException failure) {
                error(writer,id,null,'OPERATIONAL','EXTRACTOR_FAILED',failure.message ?: 'Extractor failed')
                summary = [operational:true,limited:false,reached_eof:false,temporary_bytes:0]
            }
            writer.flush()
            for (Map volume : volumes) validateCurrent(volume)
            if (!summary.reached_eof && (summary.errors ?: 0) == 0 && !summary.operational && !summary.limited) {
                error(writer,id,null,'CONTENT','INCOMPLETE_STREAM','Native reader did not confirm end of archive')
            }
            retryable = summary.operational as boolean
            reusable &= !retryable && !(summary.limited as boolean) && (summary.reached_eof as boolean) && (summary.volume_complete as boolean)
            // An operationally interrupted extractor may have left extra unreported
            // files. Publish recovered members, but do not descend until a clean retry.
            if (!summary.operational && !summary.limited) {
                retained = (summary.temporary_bytes ?: 0) as long
                occupiedBytes = Math.addExact(occupiedBytes, retained)
                Set<String> nextAncestors = new LinkedHashSet<>(ancestors)
                nextAncestors.add(fingerprint)
                writer.flush()
                String groups = 'archive_groups_' + depth
                engine.store.exec("""CREATE OR REPLACE TEMP TABLE ${groups} AS
                    SELECT row_number() OVER (ORDER BY min(ordinal)) AS seq, group_key,
                        min(flavor) AS flavor,min(ordinal) AS source_ordinal
                    FROM archive_members WHERE result_id=? AND group_key IS NOT NULL GROUP BY group_key""", id)
                long after = 0L
                while (true) {
                    stop.check()
                    List<Map> page = engine.store.rows("SELECT * FROM ${groups} WHERE seq>? AND seq<=? ORDER BY seq",after,after+128L)
                    if (page.empty) break
                    for (Map group : page) {
                        after = group.seq as long
                        List<Map> parts = engine.store.rows('''SELECT ordinal AS source_id,relative_path,actual_size AS size,sha256,volume_slot AS slot
                            FROM archive_members WHERE result_id=? AND group_key=? ORDER BY volume_slot,ordinal LIMIT ?''',id,group.group_key,options.maxVolumes+1)
                        parts.each { Map part -> part.path = work.resolve(part.source_id.toString()) }
                        Map child
                        try {
                            child = process(parts,group.flavor as String,depth+1,label + '!' + group.group_key,nextAncestors)
                        } catch (IOException failure) {
                            error(writer,id,group.source_ordinal,'OPERATIONAL','NESTED_IO_ERROR',failure.message)
                            retryable = true; reusable = false
                            continue
                        }
                        Map childResult = storage.result(child.result_id as String)
                        writer.add('archive_nested',[id,group.group_key,group.source_ordinal,child.result_id,child.duplicate])
                        height = Math.max(height,1 + (childResult.height as int))
                        if (childResult.state != 'SKIPPED') {
                            if (childResult.state != 'COMPLETE') error(writer,id,group.source_ordinal,'CHILD','NESTED_ERRORS','Nested archive has incomplete/error results: ' + child.result_id)
                            retryable |= childResult.retryable as boolean
                            reusable &= childResult.reusable as boolean
                        }
                    }
                }
            }
            writer.flush()
            // No completed result may claim reuse after member hashing I/O errors,
            // policy limits, or capability failures (e.g. unsupported encryption).
            Map flags = engine.store.rows("""SELECT count(*) FILTER (WHERE category='OPERATIONAL') AS operational,
                count(*) FILTER (WHERE category IN ('LIMIT','CAPABILITY') OR code='ENCRYPTED') AS restricted
                FROM archive_errors WHERE result_id=?""",id)[0]
            retryable |= (flags.operational as long) > 0
            reusable &= !retryable && (flags.restricted as long) == 0
            finish(id,retryable,reusable,height,summary)
            engine.progress.call([stage:'archive',status:storage.result(id).state,source:label,depth:depth,result_id:id])
            [result_id:id,duplicate:false]
        } finally {
            occupiedBytes -= retained
            temporary.release(work)
        }
    }

    private void ingest(ArchiveBatch writer, String resultId, Path work, Map event) {
        if (!(event.ordinal instanceof Number) || (event.ordinal as long) < 1) throw new IOException('Invalid archive member ordinal')
        String path = event.path as String
        String normalHash = null, recoveredHash = null
        if (event.payload != null) {
            String expected = event.ordinal.toString()
            if (event.payload.toString() != expected || event.kind != 'FILE') throw new IOException('Invalid archive payload identity')
            Path file = work.resolve(expected)
            try {
                BasicFileAttributes before = attributes(file)
                def value = hasher.hash(file, stop, engine.options.bufferBytes)
                BasicFileAttributes after = attributes(file)
                if (value.bytesRead != (event.actual_size as long) || before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime()) {
                    throw new IOException('Extracted member changed during hashing')
                }
                if (event.integrity == 'READ_OK') normalHash = value.hex
                else recoveredHash = value.hex
            } catch (IOException failure) {
                event.integrity = 'UNREADABLE'
                event.diagnostic = failure.message
                error(writer,resultId,event.ordinal,'OPERATIONAL','HASH_IO_ERROR',failure.message)
            }
        }
        Map candidate = normalHash != null ? ArchiveNames.describe(path) : null
        if (candidate?.flavor == 'single') candidate.group_key = candidate.group_key + '#' + event.ordinal
        String filename = path == null ? null : path.substring(path.lastIndexOf('/')+1)
        writer.add('archive_members',[resultId,event.ordinal,path,filename,event.kind,event.declared_size,event.actual_size,
            event.modified_sec,event.modified_nano,normalHash,recoveredHash,event.integrity,event.encrypted,
            event.raw_path_base64,event.diagnostic,candidate?.group_key,candidate?.flavor,candidate?.slot])
        if ((event.ordinal as long) % Math.min(engine.options.batchSize,1024) == 0) {
            writer.flush()
            engine.progress.call([stage:'archive',status:'MEMBERS_STAGED',result_id:resultId,members_staged:event.ordinal])
        }
    }

    private void finish(String id, boolean retryable, boolean reusable, int height, Map summary) {
        long errors = engine.store.rows('SELECT count(*) AS n FROM archive_errors WHERE result_id=?',id)[0].n as long
        engine.store.exec('''UPDATE archive_results SET state=?,retryable=?,reusable=?,height=?,summary_json=?,completed_at=current_timestamp WHERE result_id=?''',
            errors == 0 ? 'COMPLETE' : 'PARTIAL',retryable,reusable,height,JsonOutput.toJson(summary),id)
    }

    private static void error(ArchiveBatch writer, String id, Object ordinal, String category, String code, String message) {
        writer.add('archive_errors',[id,ordinal,category,code,(message ?: 'Unknown archive error').take(4096)])
    }

    private static BasicFileAttributes attributes(Path path) {
        BasicFileAttributes a = Files.readAttributes(path,BasicFileAttributes,LinkOption.NOFOLLOW_LINKS)
        if (!a.isRegularFile()) throw new ArchiveSourceChanged('Archive input is no longer a regular file: ' + path)
        a
    }

    private static void validate(Map volume, BasicFileAttributes a) {
        def time = a.lastModifiedTime().toInstant()
        if (a.size() != (volume.size as long) || time.epochSecond != (volume.modified_sec as long) || time.nano != (volume.modified_nano as int)) {
            throw new ArchiveSourceChanged('SOURCE_CHANGED: archive no longer matches filesystem inventory: ' + volume.relative_path)
        }
    }

    private static void validateCurrent(Map volume) {
        BasicFileAttributes a = attributes(volume.path as Path)
        def time = a.lastModifiedTime().toInstant()
        if (a.size() != (volume.expected_size as long) || time.epochSecond != (volume.expected_sec as long) || time.nano != (volume.expected_nano as int)) {
            throw new ArchiveSourceChanged('SOURCE_CHANGED: archive changed while being read: ' + volume.relative_path)
        }
    }

    private static long archiveSize(List<Map> volumes) {
        long total = 0L
        for (Map volume : volumes) {
            Object raw = volume.size
            if (!(raw instanceof Number) || (raw as Number).longValue() < 0L) return Long.MAX_VALUE
            try { total = Math.addExact(total, (raw as Number).longValue()) }
            catch (ArithmeticException ignored) { return Long.MAX_VALUE }
        }
        total
    }

    private static String fingerprint(List<Map> volumes, String flavor) {
        if (volumes.size() == 1 && (volumes[0].slot as int) == 0) return 'file-sha256:' + volumes[0].sha256
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest)).withCloseable { out ->
            out.writeUTF('FNORD-ARCHIVE-SET-V1')
            out.writeUTF(flavor)
            out.writeInt(volumes.size())
            for (Map volume : volumes) {
                out.writeInt(volume.slot as int)
                out.writeLong(volume.expected_size as long)
                out.write(HexFormat.of().parseHex(volume.sha256 as String))
            }
        }
        'set-v1-sha256:' + HexFormat.of().formatHex(digest.digest())
    }

    private static String digest(String value) {
        HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(value.getBytes('UTF-8')))
    }
}

class ArchiveSourceChanged extends IOException {
    ArchiveSourceChanged(String message) { super(message) }
}
