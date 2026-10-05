package fnord.dedup.web

import fnord.dedup.path.StoredPath
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.sql.Connection

import static fnord.dedup.web.ArchiveQueries.*

/** Browses immutable scanner metadata. No extraction, filesystem access, or scanner writes. */
@Service
class ArchiveService {
    private final ScannerDatabase database
    ArchiveService(ScannerDatabase database) { this.database = database }

    Map summary(List<Long> ids = null) {
        read { Connection c, boolean available ->
            Map query = scope(c, ids, available)
            checkGraph(c, query)
            ArchiveQueries.summary(c, query)
        } as Map
    }

    Map filesystemEntry(Map item) { filesystemItems([item]); item }
    Map filesystemPage(Map result) { filesystemItems(result.items as List<Map>); result }

    private void filesystemItems(List<Map> items) {
        if (!items) return
        if (items.size()>500) throw invalid('Unbounded inventory page.')
        read { Connection c, boolean available ->
            items.each { it.storageKind='FILESYSTEM' }
            if (!available) return null
            List<Map> files = items.findAll { it.kind=='FILE' && it.scanId && it.entryId }
            if (!files) return null
            List values=[]
            files.each { values.addAll([it.scanId, it.entryId]) }
            Map roots=[:]
            rows(c, '''SELECT i.scan_id,i.source_id,j.first_entry,j.status,j.result_id,j.duplicate,r.state
                FROM archive_inputs i JOIN archive_jobs j USING(scan_id,group_key)
                LEFT JOIN archive_results r ON r.result_id=j.result_id
                JOIN (VALUES ''' + (['(CAST(? AS BIGINT),CAST(? AS BIGINT))'] * files.size()).join(',') +
                ''') AS wanted(scan_id,entry_id) ON i.scan_id=wanted.scan_id AND i.source_id=wanted.entry_id
                LIMIT 501''', values).each { Map row ->
                    String key=row.scan_id.toString()+':'+row.source_id
                    if (roots.containsKey(key)) throw invalid('Ambiguous recorded archive input.')
                    roots[key]=[rootEntryId:row.first_entry, status:row.status, resultId:row.result_id,
                        reused:row.duplicate, browsable:row.status in ['COMPLETE','PARTIAL','SKIPPED'] &&
                            row.state in ['COMPLETE','PARTIAL','SKIPPED']]
                }
            files.each { it.archive=roots[it.scanId.toString()+':'+it.entryId] }
            Map query=scope(c,null,true)
            checkGraph(c,query)
            decorate(c,query,files)
            null
        }
    }

    Map browse(long scanId,long rootEntryId,String chainText,String pathText,String searchText,
               String limitText,String cursorText) {
        String path=directoryPath(pathText)
        String search=searchText ?: ''
        if (search.length()>256 || search.indexOf(0)>=0) throw invalid('Archive search is limited to 256 characters.')
        int pageLimit=limit(limitText)
        read { Connection c,boolean available ->
            requireArchives(available)
            Map context=context(c,scanId,rootEntryId,chainText)
            String binding=fingerprint(['children',scanId,context.rootEntryId,context.rootResultId,
                context.resultId,context.chain,path,search])
            Map after=cursor(cursorText,binding)
            if (after && (!(after.rank in [0,1]) || !(after.name instanceof String) ||
                after.name.length()>32768 || !(after.ordinal instanceof Number) || after.ordinal<0))
                throw invalid('Invalid archive directory cursor.')
            if (!context.browsable) return [archive:context,path:path,search:search,items:[],
                page:[limit:pageLimit,hasMore:false,nextCursor:null]]
            String safe="""relative_path IS NOT NULL AND NOT starts_with(relative_path,'/')
                AND NOT regexp_matches(relative_path,'(^|/)\\.\\.?(/|\$)')
                AND NOT regexp_matches(relative_path,'^[A-Za-z]:')"""
            String base='''WITH members AS (SELECT m.*,
                CASE WHEN '''+safe+''' THEN relative_path ELSE NULL END AS safe_path
                FROM archive_members m WHERE result_id=?), scoped AS (
                SELECT *,CASE WHEN safe_path IS NULL THEN '' ELSE substring(safe_path,?) END AS tail
                FROM members WHERE (safe_path IS NULL AND ?='') OR starts_with(safe_path,?)
            ), child_keys AS ('''
            String prefix=path ? path+'/' : ''
            List values=[context.resultId,prefix.codePointCount(0,prefix.length())+1,path,prefix]
            if (path && !rows(c,'SELECT 1 AS present FROM archive_members WHERE result_id=? AND '+
                "((relative_path=? AND kind='DIRECTORY') OR starts_with(relative_path,?)) LIMIT 1",
                [context.resultId,path,prefix]))
                throw new ApiFailure('ARCHIVE_DIRECTORY_NOT_FOUND',HttpStatus.NOT_FOUND,'Recorded archive directory not found.')
            if (search) {
                base += '''SELECT 1 AS rank,coalesce(filename,'[unavailable member name]') AS name,ordinal
                    FROM scoped WHERE kind<>'DIRECTORY' AND contains(lower(coalesce(relative_path,filename,'')),lower(?))'''
                values.add(search)
            } else {
                base += '''SELECT DISTINCT 0 AS rank,split_part(tail,'/',1) AS name,0::BIGINT AS ordinal
                    FROM scoped WHERE tail<>'' AND (strpos(tail,'/')>0 OR kind='DIRECTORY')
                    UNION ALL
                    SELECT 1,coalesce(filename,'[unavailable member name]'),ordinal FROM scoped
                    WHERE safe_path IS NULL OR (kind<>'DIRECTORY' AND tail<>'' AND strpos(tail,'/')=0)'''
            }
            base += ') SELECT * FROM child_keys WHERE true'
            if (after) {
                base += ' AND (rank>? OR (rank=? AND name>?) OR (rank=? AND name=? AND ordinal>?))'
                values.addAll([after.rank,after.rank,after.name,after.rank,after.name,after.ordinal])
            }
            base += ' ORDER BY rank,name,ordinal LIMIT ?'; values.add(pageLimit+1)
            List<Map> keys=rows(c,base,values)
            Map result=page(keys,pageLimit) { Map last -> encode([binding:binding,rank:last.rank,name:last.name,ordinal:last.ordinal]) }
            List ordinals=keys.findAll { it.rank==1 }.collect { it.ordinal }
            Map members=[:]
            if (ordinals) memberRows(c,context,ordinals).each { members[it.ordinal]=it }
            result.items=keys.collect { Map key ->
                key.rank==0 ? [key:'d:'+key.name,kind:'DIRECTORY',filename:key.name,virtual:true,
                    relativePath:prefix+key.name,size:'0',sha256:null,algorithm:'SHA-256',storageKind:'ARCHIVE_MEMBER',
                    duplicateCount:null,directCleanupEligible:false] : members[key.ordinal]
            }
            Map query=scope(c,null,true); checkGraph(c,query)
            decorate(c,query,result.items as List<Map>)
            result+[archive:context,path:path,search:search]
        } as Map
    }

    Map member(long scanId,long rootEntryId,String chainText,long ordinal) {
        withMember(scanId,rootEntryId,chainText,ordinal) { Map member -> member }
    }

    /** Callback runs under scanner access; state writes may acquire the state lock afterwards. */
    def withMember(long scanId,long rootEntryId,String chainText,long ordinal,Closure action) {
        read { Connection c,boolean available ->
            requireArchives(available)
            Map context=context(c,scanId,rootEntryId,chainText)
            if (!context.browsable) throw new ApiFailure('ARCHIVE_NOT_READY',HttpStatus.CONFLICT,'Archive contents are not finalized.')
            List<Map> found=memberRows(c,context,[ordinal])
            if (!found) throw new ApiFailure('ARCHIVE_MEMBER_NOT_FOUND',HttpStatus.NOT_FOUND,'Member not found in this archive occurrence.')
            Map item=found[0]
            Map query=scope(c,null,true); checkGraph(c,query); decorate(c,query,[item])
            item.relatedErrors=rows(c,'''SELECT category,code,message FROM archive_errors
                WHERE result_id=? AND (ordinal=? OR ordinal IS NULL) ORDER BY category,code,message LIMIT 20''',
                [context.resultId,ordinal])
            item.archiveContext=context
            action.call(item)
        }
    }

    Map groups(Map body) {
        if (body==null || !(['scanIds','limit','cursor'] as Set).containsAll(body.keySet()))
            throw invalid('Archive duplicate groups accept selected scans, limit and cursor.')
        List<Long> ids=scanIds(body.scanIds)
        int pageLimit=limit(body.limit)
        String binding=fingerprint(['archive-groups',ids])
        Map after=cursor(body.cursor as String,binding)
        if (after && (!(after.sha256 instanceof String) || !(after.sha256 ==~ /[0-9a-f]{64}/)))
            throw invalid('Invalid archive group cursor.')
        read { Connection c,boolean available ->
            Map query=scope(c,ids,available); checkGraph(c,query)
            String sql=query.sql+'SELECT * FROM content_groups WHERE occurrences>1 AND archive_count>0'
            List values=[]+query.values
            if (after) { sql+=' AND (size<? OR (size=? AND sha256>?))';
                long size=nonnegative(after.size); values.addAll([size,size,after.sha256]) }
            sql+=' ORDER BY size DESC,sha256 LIMIT ?';values.add(pageLimit+1)
            List<Map> items=rows(c,sql,values).collect { Map r ->
                [groupId:r.size.toString()+':'+r.sha256,size:r.size.toString(),sha256:r.sha256,
                 algorithm:'SHA-256',occurrences:r.occurrences,filesystemOccurrences:r.filesystem_count,
                 archiveOccurrences:r.archive_count,
                 filesystemObservedBytes:(new BigInteger(r.size.toString())*new BigInteger(r.filesystem_count.toString())).toString(),
                 archiveLogicalBytes:(new BigInteger(r.size.toString())*new BigInteger(r.archive_count.toString())).toString()]
            }
            page(items,pageLimit) { Map last -> encode([binding:binding,size:last.size,sha256:last.sha256]) } +
                [summary:ArchiveQueries.summary(c,query),scanIds:ids]
        } as Map
    }

    Map occurrences(Map body, boolean allScans = false) {
        if (body==null || !(['scanIds','size','sha256','storageKind','limit','cursor'] as Set).containsAll(body.keySet()))
            throw invalid('Invalid archive occurrence request.')
        List<Long> ids=allScans ? null : scanIds(body.scanIds)
        long size=nonnegative(body.size)
        if (!(body.sha256 instanceof String) || !(body.sha256 ==~ /[0-9a-f]{64}/)) throw invalid('A full saved SHA-256 is required.')
        String storage=body.storageKind as String
        if (!(storage in ['FILESYSTEM','ARCHIVE_MEMBER'])) throw invalid('Choose filesystem or archive occurrences explicitly.')
        int pageLimit=limit(body.limit)
        String binding=fingerprint(['archive-occurrences',ids,size,body.sha256,storage])
        Map after=cursor(body.cursor as String,binding)
        if (after && (!(after.chain instanceof String) || after.chain.length()>2048)) throw invalid('Invalid occurrence cursor.')
        read { Connection c,boolean available ->
            Map query=scope(c,ids,available);checkGraph(c,query)
            String sql=query.sql+'''SELECT e.*,s.name AS scan_name,s.root AS scan_root,
                coalesce(e.root_entry,e.entry_id) AS location_id,coalesce(e.ordinal,0) AS member_id,
                g.filesystem_count,g.archive_count,g.occurrences
                FROM evidence e JOIN selected s USING(scan_id) JOIN content_groups g USING(size,sha256)
                WHERE e.size=? AND e.sha256=? AND e.storage_kind=?'''
            List values=query.values+[size,body.sha256,storage]
            if (after) {
                long scan=id(after.scanId),location=id(after.locationId),member=nonnegative(after.memberId)
                sql+=''' AND (e.scan_id>? OR (e.scan_id=? AND coalesce(e.root_entry,e.entry_id)>?)
                    OR (e.scan_id=? AND coalesce(e.root_entry,e.entry_id)=? AND e.chain>?)
                    OR (e.scan_id=? AND coalesce(e.root_entry,e.entry_id)=? AND e.chain=? AND coalesce(e.ordinal,0)>?))'''
                values.addAll([scan,scan,location,scan,location,after.chain,scan,location,after.chain,member])
            }
            sql+=' ORDER BY e.scan_id,location_id,e.chain,member_id LIMIT ?';values.add(pageLimit+1)
            List<Map> items=rows(c,sql,values).collect { Map r ->
                [scanId:r.scan_id,scanName:r.scan_name,entryId:r.entry_id,parentId:r.parent_id,
                 rootEntryId:r.root_entry,chain:r.chain,ordinal:r.ordinal,locationId:r.location_id,memberId:r.member_id,
                 filename:r.filename,path:StoredPath.join(r.scan_root as String,r.location as String),
                 relativePath:r.location,kind:'FILE',storageKind:r.storage_kind,
                 size:r.size.toString(),sha256:r.sha256,algorithm:'SHA-256',duplicateCount:r.occurrences,
                 filesystemOccurrenceCount:r.filesystem_count,archiveOccurrenceCount:r.archive_count,
                 directCleanupEligible:r.storage_kind=='FILESYSTEM']
            }
            page(items,pageLimit) { Map last -> encode([binding:binding,scanId:last.scanId,
                locationId:last.locationId,chain:last.chain,memberId:last.memberId]) }+[scanIds:ids,storageKind:storage]
        } as Map
    }

    Map memberOccurrences(long scanId,long rootEntryId,String chainText,long ordinal, String storageKind,String limitText,String cursorText) {
        Map reference=member(scanId,rootEntryId,chainText,ordinal)
        if (!reference.sha256) throw new ApiFailure('HASH_UNAVAILABLE',HttpStatus.UNPROCESSABLE_ENTITY,
            'Only a complete READ_OK member hash can identify confirmed occurrences.')
        occurrences([size:reference.size,sha256:reference.sha256,storageKind:storageKind,
            limit:limitText,cursor:cursorText],true)
    }

    private static Map context(Connection c,long scanId,long entryId,String chainText) {
        List<Long> chain=chain(chainText)
        List<Map> found=rows(c,'''SELECT j.*,r.state AS result_state,s.root,s.name AS scan_name,
                e.relative_path,e.filename,e.parent_id
            FROM archive_inputs i JOIN archive_jobs j USING(scan_id,group_key)
            JOIN scans s ON s.scan_id=j.scan_id
            JOIN entries e ON e.scan_id=j.scan_id AND e.entry_id=j.first_entry
            LEFT JOIN archive_results r ON r.result_id=j.result_id
            WHERE i.scan_id=? AND i.source_id=? LIMIT 2''',[scanId,entryId])
        if (!found) throw new ApiFailure('ARCHIVE_NOT_FOUND',HttpStatus.NOT_FOUND,'This file has no recorded archive analysis.')
        if (found.size()!=1) throw invalid('Ambiguous recorded archive input.')
        Map root=found[0]
        Map result=[scanId:scanId,scanName:root.scan_name,rootEntryId:root.first_entry,parentId:root.parent_id,
            rootResultId:root.result_id,resultId:root.result_id,chain:'',filename:root.filename,
            physicalPath:StoredPath.join(root.root as String,root.relative_path as String),
            status:root.status,diagnostic:root.diagnostic,reused:root.duplicate,
            browsable:root.status in ['COMPLETE','PARTIAL','SKIPPED'] && root.result_state in ['COMPLETE','PARTIAL','SKIPPED']]
        List<Map> containers=[[chain:'',name:root.filename,status:root.status]]
        Set<String> visited=[] as Set<String>
        if (root.result_id) visited.add(root.result_id as String)
        for (long ordinal:chain) {
            if (!result.browsable) throw new ApiFailure('ARCHIVE_NOT_READY',HttpStatus.CONFLICT,'Archive contents are not finalized.')
            List<Map> child=rows(c,'''SELECT n.child_result_id,n.duplicate,r.state,m.filename,m.relative_path
                FROM archive_nested n JOIN archive_results r ON r.result_id=n.child_result_id
                JOIN archive_members m ON m.result_id=n.parent_result_id AND m.ordinal=n.source_ordinal
                WHERE n.parent_result_id=? AND n.source_ordinal=? LIMIT 2''',[result.resultId,ordinal])
            if (child.size()!=1) throw new ApiFailure('ARCHIVE_NOT_FOUND',HttpStatus.NOT_FOUND,'Nested archive not found at this member ordinal.')
            Map item=child[0]
            if (!visited.add(item.child_result_id as String)) throw invalid('Recorded archive ancestry contains a cycle.')
            result.resultId=item.child_result_id
            result.chain=(result.chain ? result.chain+'.' : '')+ordinal
            result.filename=item.filename
            result.status=item.state
            result.reused=item.duplicate
            result.browsable=item.state in ['COMPLETE','PARTIAL','SKIPPED']
            containers.add([chain:result.chain,name:item.relative_path ?: item.filename,status:item.state])
        }
        result.containers=containers
        result.metadataOnly=true
        result
    }

    private static List<Map> memberRows(Connection c,Map context,List ordinals) {
        rows(c,'''SELECT m.*,n.source_ordinal AS nested_ordinal,n.child_result_id AS nested_result,
                r.state AS nested_state,n.duplicate AS nested_reused
            FROM archive_members m LEFT JOIN archive_nested n
                ON n.parent_result_id=m.result_id AND n.group_key=m.group_key
            LEFT JOIN archive_results r ON r.result_id=n.child_result_id
            WHERE m.result_id=? AND m.ordinal IN ('''+marks(ordinals.size())+') ORDER BY m.ordinal',
            [context.resultId]+ordinals).collect { Map m ->
                boolean good=m.kind=='FILE' && m.integrity=='READ_OK' && !m.encrypted &&
                    m.actual_size!=null && m.actual_size>=0 && m.sha256 instanceof String && m.sha256 ==~ /[0-9a-f]{64}/
                String path=m.relative_path as String
                [key:'m:'+m.ordinal,ordinal:m.ordinal,entryId:0L,scanId:context.scanId,scanName:context.scanName,
                 filename:m.filename ?: '[unavailable member name]',relativePath:path,
                 path:context.physicalPath+'!/'+(context.containers.drop(1).collect { it.name }.join('!/') +
                    (context.containers.size()>1 ? '!/' : ''))+(path ?: '[unavailable member name]'),
                 kind:m.kind,storageKind:'ARCHIVE_MEMBER',algorithm:'SHA-256',virtual:true,
                 size:(m.actual_size != null ? m.actual_size : (m.declared_size != null ? m.declared_size : 0)).toString(),
                 actualSize:m.actual_size?.toString(),declaredSize:m.declared_size?.toString(),
                 modifiedSec:m.modified_sec,modifiedNano:m.modified_nano,
                 sha256:good ? m.sha256 : null,recoveredSha256:m.recovered_sha256,
                 hashState:good ? 'HASHED' : 'UNRESOLVED',integrity:m.integrity,encrypted:m.encrypted,
                 diagnostic:m.diagnostic,rawPathBase64:m.raw_path_base64,
                 directCleanupEligible:false,
                 archiveMember:[scanId:context.scanId,rootEntryId:context.rootEntryId,chain:context.chain,ordinal:m.ordinal],
                 nestedArchive:m.nested_result ? [ordinal:m.nested_ordinal,status:m.nested_state,reused:m.nested_reused,
                    browsable:m.nested_state in ['COMPLETE','PARTIAL','SKIPPED']] : null]
            }
    }

    static List<Long> chain(String text) {
        if (!text) return []
        if (text.length()>2048 || !(text ==~ /[1-9][0-9]*(\.[1-9][0-9]*)*/)) throw invalid('Invalid nested archive chain.')
        List<Long> ids=text.split('\\.').collect { id(it) }
        if (ids.size()>MAX_DEPTH) throw invalid('Archive nesting exceeds 64 levels.')
        ids
    }
    private static String directoryPath(String text) {
        String path=text ?: ''
        if (path.length()>32768 || path.indexOf(0)>=0 || path.startsWith('/') ||
            (path && path.split('/',-1).any { it in ['', '.', '..'] }) || path ==~ /^[A-Za-z]:.*/)
            throw invalid('Invalid virtual archive directory.')
        path
    }
    private static void requireArchives(boolean available) {
        if (!available) throw new ApiFailure('ARCHIVES_UNAVAILABLE',HttpStatus.NOT_FOUND,'This scanner database has no archive analysis.')
    }
    private def read(Closure action) {
        database.withConnection { Connection c,ignored ->
            Map schema=SchemaInspector.inspect(c)
            action.call(c,schema.archiveSchema!=null)
        }
    }
}
