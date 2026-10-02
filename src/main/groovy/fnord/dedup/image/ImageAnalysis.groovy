package fnord.dedup.image

import fnord.dedup.ScanOptions
import fnord.dedup.StopToken
import fnord.dedup.archive.ArchiveTemp
import fnord.dedup.archive.ArchiveOptions
import fnord.dedup.store.DuckStore
import groovy.json.JsonOutput
import java.nio.file.*
import java.util.concurrent.CancellationException

/** Synchronous, single-owner image phase. Published generations are immutable. */
class ImageAnalysis {
    static final String POLICY='image-policy-v1/current-state/no-recursion/no-links/full-sha256'
    final DuckStore db
    final ImageStore images
    final ImageOptions options
    final ScanOptions scanOptions
    final StopToken stop
    final Closure progress
    private Path applianceCache
    final DiskImageProvider provider

    ImageAnalysis(DuckStore db,ScanOptions scanOptions,ImageOptions options,StopToken stop,Closure progress,DiskImageProvider provider=new NativeImageProvider()) {
        this.db=db; this.images=new ImageStore(db); this.options=options.validate()
        this.scanOptions=scanOptions; this.stop=stop; this.progress=progress; this.provider=provider
    }

    Map run(String name) {
        Map scan=db.scan(name)
        if (scan.phase=='DISCOVERING') throw new IllegalStateException('Finish discovery before image analysis')
        Path root=Path.of(scan.root as String)
        Path tempRoot=(options.tempDirectory ?: Path.of(db.database.toString()+'.images-tmp')).toAbsolutePath().normalize()
        if (tempRoot.startsWith(root)) throw new IllegalArgumentException('Image temporary storage must be outside the scanned source root')
        ArchiveOptions tempOptions=new ArchiveOptions(tempDirectory:tempRoot)
        images.recover(); images.identify(scan)
        db.transaction {
            if (options.force) db.exec("UPDATE image_jobs SET state='PENDING',result_id=NULL,duplicate=false,retryable=false,diagnostic=NULL,component_of=NULL WHERE scan_id=?",scan.scan_id)
            else if (options.retryErrors) db.exec("UPDATE image_jobs SET state='PENDING',diagnostic=NULL WHERE scan_id=? AND state='PARTIAL'",scan.scan_id)
            else db.exec("UPDATE image_jobs SET state='PENDING',diagnostic=NULL WHERE scan_id=? AND retryable",scan.scan_id)
        }
        try (ArchiveTemp temp=new ArchiveTemp(db,tempOptions,'images-'+images.instanceId,root,'image_temp_roots')) {
            Path identityWork=temp.create()
            String identity
            identity=provider.identity(identityWork,options,stop)
            applianceCache=Files.createDirectory(identityWork.resolve('appliance-cache'))
            // Keyset pages. Descriptor-like primaries precede obvious standalone extent names.
            for (int pass=0; pass<2; pass++) {
                long after=-1L
                while (!stop.cancelled) {
                    List<Map> jobs=db.rows('''SELECT * FROM image_jobs WHERE scan_id=? AND source_id>? AND state='PENDING'
                      AND CASE WHEN regexp_matches(lower(relative_path),'-(flat|[sf][0-9]+)\\.vmdk$') THEN 1 ELSE 0 END=?
                      ORDER BY source_id LIMIT 128''',scan.scan_id,after,pass)
                    if (jobs.empty) break
                    for (Map job : jobs) {
                        after=job.source_id as long
                        stop.check()
                        if (db.rows('SELECT state FROM image_jobs WHERE scan_id=? AND source_id=?',scan.scan_id,job.source_id)[0].state!='PENDING') continue
                        process(scan,job,identity,temp)
                    }
                }
            }
            db.exec('UPDATE image_runs SET phase=?,updated_at=now() WHERE scan_id=?',stop.cancelled ? 'PAUSED' : ((images.status(name).errors as long)>0 ? 'COMPLETE_WITH_ERRORS' : 'COMPLETE'),scan.scan_id)
        } catch (CancellationException ignored) {
            db.exec("UPDATE image_runs SET phase='PAUSED',updated_at=now() WHERE scan_id=?",scan.scan_id)
        } catch (Exception failure) {
            db.exec("UPDATE image_runs SET phase='PAUSED',updated_at=now() WHERE scan_id=?",scan.scan_id)
            throw failure
        }
        images.status(name)
    }

    private void process(Map scan,Map job,String identity,ArchiveTemp temp) {
        String result=UUID.randomUUID().toString()
        Path work=temp.create()
        ImageBatch batch=new ImageBatch(db,scanOptions.batchSize)
        ImageSources sources=new ImageSources(db,scan,options,stop)
        db.transaction {
            db.exec("INSERT INTO image_results(result_id,scan_id,source_id,state,provider,policy) VALUES (?,?,?,'RUNNING',?,?)",result,scan.scan_id,job.source_id,identity,POLICY)
            db.exec("UPDATE image_jobs SET state='RUNNING',result_id=?,diagnostic=NULL WHERE scan_id=? AND source_id=?",result,scan.scan_id,job.source_id)
        }
        long entries=0, errors=0
        try {
            progress.call([stage:'image',source:job.relative_path,state:'RESOLVING_DEPENDENCIES'])
            Map resolved=sources.resolve(job)
            String signature=sources.fingerprint(false)
            boolean hash=options.containerHash=='always' || (options.containerHash=='candidate' && !db.rows('''SELECT 1 FROM image_jobs WHERE format=? AND size=? AND NOT (scan_id=? AND source_id=?) AND state!='COMPONENT' LIMIT 1''',job.format,job.size,scan.scan_id,job.source_id).empty)
            String fingerprint=null
            if (hash) {
                progress.call([stage:'image',source:job.relative_path,state:'CHECKSUMMING'])
                fingerprint=sources.fingerprint(true)
            }
            sources.components.each { Map c -> batch.add('image_components',[result,c.ordinal,c.parent_ordinal,c.role,c.format,c.source_id,c.relative_path,c.size,c.modified_sec,c.modified_nano,c.sha256]) }
            batch.flush()
            db.exec('UPDATE image_results SET fingerprint=?,signature=? WHERE result_id=?',fingerprint,signature,result)
            // Extent-only sources are not independent guest disks. Backing images remain independently inspectable.
            sources.components.findAll { it.role=='EXTENT' }.each { Map c ->
                db.exec("UPDATE image_jobs SET state='COMPONENT',component_of=? WHERE scan_id=? AND source_id=? AND state='PENDING'",job.source_id,scan.scan_id,c.source_id)
            }
            if (fingerprint && !options.force) {
                List<Map> cached=db.rows("SELECT result_id FROM image_results WHERE fingerprint=? AND provider=? AND policy=? AND reusable AND state='COMPLETE' ORDER BY started_at LIMIT 1",fingerprint,identity,POLICY)
                if (!cached.empty) {
                    sources.components.each { sources.validate(it) }
                    db.transaction {
                        db.exec('DELETE FROM image_components WHERE result_id=?',result)
                        db.exec('DELETE FROM image_results WHERE result_id=?',result)
                        db.exec("UPDATE image_jobs SET state='COMPLETE',result_id=?,duplicate=true,retryable=false WHERE scan_id=? AND source_id=?",cached[0].result_id,scan.scan_id,job.source_id)
                    }
                    progress.call([stage:'image',source:job.relative_path,state:'DUPLICATE'])
                    return
                }
            }
            Map cfg=options.nativeSettings()+[graph:resolved.graph,components:sources.components,work:work.toString(),appliance_cache:applianceCache.toString(),cache_lease:applianceCache.parent.resolve('.extract-lock').toString()]
            Map info=provider.probe(work,cfg,options,stop)
            if (!(info.virtual_size instanceof Number) || (info.virtual_size as long)<0) throw new IOException('Invalid native image size')
            db.exec('UPDATE image_results SET virtual_size=? WHERE result_id=?',info.virtual_size,result)
            Map summary=provider.inspect(work,cfg,options,stop) { Map row ->
                stop.check()
                switch (row.event) {
                    case 'progress': progress.call([stage:'image',source:job.relative_path,state:row.state]); break
                    case 'partition': batch.add('image_partitions',[result,row.device,row.number,row.start_bytes,row.size_bytes,row.table_type]); break
                    case 'filesystem': batch.add('image_filesystems',[result,row.filesystem_id,row.device,row.type,row.uuid,row.label,row.size_bytes,row.state]); break
                    case 'entry':
                        if (++entries>options.maxFiles) throw new ImageFailure('MAX_FILE_COUNT','Image provider exceeded configured entry limit','LIMIT')
                        validateEntry(row)
                        batch.add('image_entries',[result,row.filesystem_id,row.entry_id,row.relative_path,row.filename,row.kind,row.size,row.actual_size,row.modified_sec,row.modified_nano,row.sha256,row.integrity])
                        if (entries % Math.min(scanOptions.batchSize,4096)==0) {
                            batch.flush()
                            progress.call([stage:'image',source:job.relative_path,state:'SCANNING',entries_written_this_run:entries])
                        }
                        break
                    case 'error':
                        errors++
                        batch.add('image_errors',[result,row.filesystem_id,row.entry_id,row.category,row.code,(row.message ?: '').toString().take(4096)])
                        break
                    default: throw new IOException('Unknown image provider event: '+row.event)
                }
            }
            stop.check()
            sources.components.each { sources.validate(it) }
            if ((summary.errors as long)<errors || (summary.entries as long)!=entries) throw new IOException('Image provider summary does not match streamed records')
            batch.flush()
            boolean partial=errors>0 || (summary.errors as long)>0
            publish(scan,job,result,partial,(summary.retryable ?: false) as boolean,summary)
        } catch (CancellationException e) {
            throw e // RUNNING result is hidden until replay discards it.
        } catch (ImageFailure e) {
            failAttempt(scan,job,result,batch,sources,e)
        } catch (IOException e) {
            failAttempt(scan,job,result,batch,sources,new ImageFailure('NATIVE_PROCESS_ERROR',e.message ?: 'Native operation failed','OPERATIONAL'))
        } finally {
            temp.release(work)
        }
    }

    private void failAttempt(Map scan,Map job,String result,ImageBatch batch,ImageSources sources,ImageFailure failure) {
        batch.flush()
        ImageFailure changed=null
        // Even failed native calls must not publish hashes against a mutated source.
        try { sources.components.each { sources.validate(it) } }
        catch (ImageFailure e) { changed=e }
        if (failure.code=='SOURCE_CHANGED' || changed!=null) {
            db.transaction {
                db.exec('DELETE FROM image_entries WHERE result_id=?',result)
                db.exec('DELETE FROM image_filesystems WHERE result_id=?',result)
                db.exec('DELETE FROM image_partitions WHERE result_id=?',result)
            }
        }
        ImageFailure diagnostic=changed ?: failure
        db.exec('INSERT INTO image_errors VALUES (?,NULL,NULL,?,?,?)',result,diagnostic.category,diagnostic.code,(diagnostic.message ?: 'Image failure').take(4096))
        publish(scan,job,result,true,diagnostic.category=='OPERATIONAL',[error:diagnostic.code])
    }

    private void publish(Map scan,Map job,String result,boolean partial,boolean retryable,Map summary) {
        String state=partial ? 'PARTIAL' : 'COMPLETE'
        db.transaction {
            db.exec('UPDATE image_results SET state=?,reusable=?,retryable=?,summary_json=?,completed_at=now() WHERE result_id=?',state,!partial,retryable,JsonOutput.toJson(summary),result)
            db.exec('UPDATE image_jobs SET state=?,retryable=?,duplicate=false WHERE scan_id=? AND source_id=?',state,retryable,scan.scan_id,job.source_id)
        }
        progress.call([stage:'image',source:job.relative_path,state:state])
    }

    static void validateEntry(Map r) {
        if (!(r.entry_id instanceof Number) || (r.entry_id as long)<1 || !(r.filesystem_id instanceof Number) || (r.filesystem_id as long)<1 ||
            !(r.relative_path instanceof String) || !(r.kind in ['FILE','DIRECTORY','SYMLINK','OTHER'])) throw new IOException('Invalid native image entry')
        if (r.sha256!=null && (r.kind!='FILE' || r.integrity!='READ_OK' || !(r.sha256 ==~ /[0-9a-f]{64}/) || r.actual_size==null || r.size!=r.actual_size))
            throw new IOException('Invalid confirmed guest checksum')
        if (r.integrity=='READ_OK' && r.sha256==null) throw new IOException('Missing completed guest checksum')
    }
}
