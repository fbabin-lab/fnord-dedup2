package fnord.dedup.web

import fnord.dedup.*
import fnord.dedup.archive.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.zip.*
import static org.junit.jupiter.api.Assertions.assertThrows

class ArchiveServiceTest {
    @TempDir Path work
    static final String HELLO=sha('hello'.bytes)
    static final String EMPTY=sha(new byte[0])

    @Test void separatesAllLogicalAliasesAndNestedOccurrencesFromFilesystemAccounting() {
        Map f=fixture()
        Map summary=f.service.summary([1L])
        assert summary.filesystem.candidateFiles=='3'
        assert summary.filesystem.candidateBytes=='19'
        assert summary.archive.candidateMembers=='16'
        assert summary.archive.candidateLogicalBytes=='48'
        assert summary.archive.directCleanupBytes=='0'
        assert summary.coverage.partialRoots=='2'
        assert summary.coverage.unfinishedRoots=='1'
        assert summary.coverage.skippedRoots=='1'
        assert summary.coverage.unresolvedMembers=='8'
        Map group=f.service.groups([scanIds:[1]]).items.find { it.sha256==HELLO }
        assert group.filesystemOccurrences==1 && group.archiveOccurrences==6 && group.occurrences==7
        assert group.filesystemObservedBytes=='5' && group.archiveLogicalBytes=='30'
        Map file=f.service.filesystemEntry(f.inventory.entry(1,f.ids['plain.txt'] as long))
        assert file.duplicateCandidate && file.duplicateCount==7
        assert file.filesystemOccurrenceCount==1 && file.archiveOccurrenceCount==6
        List<Map> occurrences=allPages { cursor -> f.service.occurrences([scanIds:[1],size:5,
            sha256:HELLO,storageKind:'ARCHIVE_MEMBER',limit:1,cursor:cursor]) }
        assert occurrences.size()==6
        assert occurrences.collect { [it.scanId,it.locationId,it.chain,it.ordinal] }.unique().size()==6
        assert occurrences.every { !it.directCleanupEligible && it.entryId==null }
        assert occurrences*.rootEntryId.unique().sort()==[f.ids['a.zip'],f.ids['b.zip']].sort()
        assert occurrences.count { it.chain=='3' }==2
        assert f.service.occurrences([scanIds:[1],size:5,sha256:HELLO,storageKind:'FILESYSTEM']).items*.filename==['plain.txt']
        assert !f.service.groups([scanIds:[1]]).items.any { it.sha256=='d'*64 }
    }

    @Test void virtualDirectoriesUnicodeDuplicateNamesSearchAndCursorsPreserveIdentity() {
        Map f=fixture();long root=f.ids['a.zip'] as long
        Map page=f.service.browse(1,root,'','','','1',null)
        assert page.items[0].kind=='DIRECTORY'
        assert page.page.hasMore
        assert failure { f.service.browse(1,root,'','d','','1',page.page.nextCursor) }=='INVALID_ARCHIVE_REQUEST'
        assert failure { f.service.browse(1,f.ids['b.zip'] as long,'','','','1',page.page.nextCursor) }=='INVALID_ARCHIVE_REQUEST'
        List<Map> folders=allPages { cursor -> f.service.browse(1,root,'','','','1',cursor) }
        assert folders.find { it.filename=='emptydir' }.kind=='DIRECTORY'
        assert folders.find { it.filename=='😀' }.kind=='DIRECTORY'
        assert folders.find { it.filename=='escape' }.integrity=='SKIPPED'
        List<Map> children=allPages { cursor -> f.service.browse(1,root,'','d','','1',cursor) }
        assert children.findAll { it.filename=='shared.txt' }*.ordinal==[1L,2L]
        assert children.find { it.filename=='child.zip' }.nestedArchive.ordinal==3
        assert f.service.browse(1,root,'','😀','','100',null).items*.filename==['µ.txt']
        assert f.service.browse(1,root,'','emptydir','','100',null).items.empty
        assert f.service.browse(1,root,'','','shared','100',null).items*.ordinal==[1L,2L]
        assert f.service.browse(1,root,'3','','','100',null).items.size()==2
        assert failure { f.service.browse(1,root,'1','','','100',null) }=='ARCHIVE_NOT_FOUND'
        assert failure { f.service.browse(1,root,'','../outside','','100',null) }=='INVALID_ARCHIVE_REQUEST'
        assert failure { f.service.browse(1,root,'','missing','','100',null) }=='ARCHIVE_DIRECTORY_NOT_FOUND'
        assert failure { f.service.browse(1,root,'','','','501',null) }=='INVALID_ARCHIVE_REQUEST'
        assert f.service.member(1,root,'',1).path.endsWith('a.zip!/d/shared.txt')
        assert f.service.member(1,root,'3',1).path.endsWith('a.zip!/d/child.zip!/shared.txt')
        Map alias=f.service.browse(1,f.ids['part.zip.002'] as long,'','','','100',null)
        assert alias.archive.rootEntryId==root // Another volume is a link, not another archive occurrence.
        assert failure { f.service.member(1,root,'',999) }=='ARCHIVE_MEMBER_NOT_FOUND'
    }

    @Test void excludesDamagedEncryptedRunningAndOrphanDataAndReportsUnresolvedInsteadOfUnique() {
        Map f=fixture();long root=f.ids['a.zip'] as long
        for(long ordinal:[5L,6L,7L,9L,10L]) {
            Map member=f.service.member(1,root,'',ordinal)
            assert member.sha256==null && member.duplicateCount==null && !member.duplicateCandidate
            assert !member.directCleanupEligible
        }
        assert f.service.member(1,root,'',6).recoveredSha256==HELLO
        Map staging=f.service.browse(1,f.ids['running.zip'] as long,'','','','100',null)
        assert !staging.archive.browsable && staging.items.empty
        assert failure { f.service.member(1,f.ids['running.zip'] as long,'',1) }=='ARCHIVE_NOT_READY'
        Map skipped=f.service.browse(1,f.ids['skipped.zip'] as long,'','','','100',null)
        assert skipped.archive.status=='SKIPPED' && skipped.items.empty
        assert failure { f.service.memberOccurrences(1,root,'',6,'ARCHIVE_MEMBER','100',null) }=='HASH_UNAVAILABLE'
    }

    @Test void signaturesUseOnlyRecordedEvidenceAndScenariosNeverContainArchiveMembers() {
        Map f=fixture();long root=f.ids['a.zip'] as long
        byte[] before=digest(f.db as Path)
        new WebStateStore(work.resolve('web-state.duckdb').toString(),f.scanner).withCloseable { state ->
            SignatureService signatures=new SignatureService(state,f.scanner)
            ArchiveController controller=new ArchiveController(f.service,signatures,f.inventory)
            Map signature=controller.signature('1',root.toString(),'1','',[tag:'Archive junk',memo:'All copies'])
            assert signature.sha256==HELLO && signature.size=='5'
            assert controller.fileArchiveOccurrences('1',f.ids['plain.txt'].toString(),'100',null).items.size()==6
            assert controller.member('1',root.toString(),'1','').signatureMatch
            assert controller.member('1',f.ids['b.zip'].toString(),'1','3').signatureMatch
            assert !controller.member('1',root.toString(),'6','').signatureMatch
            assert failure { controller.signature('1',root.toString(),'6','',[:]) }=='HASH_UNAVAILABLE'
            assert failure { controller.signature('1',root.toString(),'1','',[size:100]) }=='INVALID_ARCHIVE_REQUEST'
            assert failure { controller.signature('1',root.toString(),'2','',[:]) }=='SIGNATURE_EXISTS'
            Map member=controller.member('1',root.toString(),'1','')
            assert member.removalCandidate && !member.directCleanupEligible
            ScenarioService scenarios=new ScenarioService(state,f.scanner,new ScenarioEngine(new DuplicateService(f.scanner)))
            Map scenario=scenarios.create([name:'Filesystem only',description:'No archived REMOVE actions',config:[request:[scanIds:[1]]]])
            Map plan=scenarios.generate(scenario.id,[revision:1])
            assert plan.snapshot.summary.observations=='2' // Only two matching ordinary a.zip/b.zip files.
            assert plan.snapshot.summary.candidateBytes=='7'
            assert plan.snapshot.validation.valid
            signatures.delete(signature.id)
            assert !controller.member('1',root.toString(),'1','').signatureMatch
        }
        assert Arrays.equals(before,digest(f.db as Path))
        assert Files.readString(f.root.resolve('plain.txt'))=='hello'
    }

    @Test void allScanMemberLinksAndExplicitGroupScopesDoNotConfuseCanonicalOriginWithOccurrenceScan() {
        Map f=fixture()
        mutate(f.db as Path) { c ->
            exec(c,"INSERT INTO scans(scan_id,name,root,phase,algorithm,created_at,updated_at,next_entry_id) SELECT 2,'Second','Z:/unavailable',phase,algorithm,created_at,updated_at,next_entry_id FROM scans WHERE scan_id=1")
            exec(c,'INSERT INTO entries SELECT 2,entry_id,parent_id,relative_path,filename,kind,size,modified_sec,modified_nano FROM entries WHERE scan_id=1')
            exec(c,'INSERT INTO archive_inputs SELECT 2,group_key,flavor,slot,source_id,relative_path,size,modified_sec,modified_nano FROM archive_inputs WHERE scan_id=1')
            exec(c,'INSERT INTO archive_jobs SELECT 2,group_key,first_entry,flavor,status,result_id,true,retryable,diagnostic FROM archive_jobs WHERE scan_id=1')
        }
        assert f.service.summary([2L]).archive.candidateMembers=='16'
        Map group=f.service.groups([scanIds:[2]]).items.find { it.sha256==HELLO }
        assert group.archiveOccurrences==6 && group.filesystemOccurrences==0
        List<Map> found=allPages { cursor -> f.service.memberOccurrences(2,f.ids['a.zip'] as long,'',1,'ARCHIVE_MEMBER','1',cursor) }
        assert found.size()==12 && found*.scanId.toSet()==[1L,2L] as Set
        assert found.findAll { it.scanId==2 }.every { it.path.startsWith('Z:/unavailable/') }
        Map page=f.service.groups([scanIds:[1],limit:1])
        assert failure { f.service.groups([scanIds:[2],limit:1,cursor:page.page.nextCursor]) }=='INVALID_ARCHIVE_REQUEST'
        assert failure { f.service.groups([scanIds:[999]]) }=='SCAN_NOT_FOUND'
        assert failure { f.service.groups([scanIds:[1,1]]) }=='INVALID_ARCHIVE_REQUEST'
        assert failure { f.service.groups([:]) }=='INVALID_ARCHIVE_REQUEST'
    }

    @Test void exactHugeByteTotalsZeroBytesAndCycleFailuresDoNotInventSavings() {
        Map f=fixture();long root=f.ids['a.zip'] as long
        assert f.service.member(1,root,'',8).size=='0'
        assert f.service.groups([scanIds:[1]]).items.find { it.sha256==EMPTY }.archiveLogicalBytes=='0'
        mutate(f.db as Path) { c ->
            exec(c,"UPDATE archive_members SET actual_size=9000000000000000000,declared_size=9000000000000000000 WHERE result_id='root' AND ordinal IN (1,2)")
        }
        Map huge=f.service.groups([scanIds:[1]]).items.find { it.size=='9000000000000000000' }
        assert huge.archiveOccurrences==4 && huge.archiveLogicalBytes=='36000000000000000000'
        assert new BigInteger(f.service.summary([1L]).archive.candidateLogicalBytes)>Long.MAX_VALUE
        mutate(f.db as Path) { c -> exec(c,"INSERT INTO archive_nested VALUES ('child','cycle',1,'root',false)") }
        assert failure { f.service.summary([1L]) }=='ARCHIVE_GRAPH_LIMIT'
    }

    @Test void absentArchiveSchemaStillHasWorkingFilesystemAndNoArchiveTotals() {
        Path root=Files.createDirectory(work.resolve('only-files'));Files.writeString(root.resolve('a'),'same');Files.writeString(root.resolve('b'),'same')
        Path db=work.resolve('files.duckdb')
        Dedup.open(db).withCloseable { it.scan('Files',root) }
        ArchiveService service=new ArchiveService(new ScannerDatabase(db.toString()))
        assert !service.summary().available
        assert service.summary().archive.candidateMembers=='0'
        assert service.summary().filesystem.candidateFiles=='2'
        assert service.groups([scanIds:[1]]).items.empty
        assert failure { service.browse(1,2,'','','','100',null) }=='ARCHIVES_UNAVAILABLE'
    }

    @Test void nativeNestedZipCanBeBrowsedOfflineWithoutExtractingAgain() {
        try {new NativeArchiveProvider().identity(Files.createDirectory(work.resolve('probe')),new ArchiveOptions(),new StopToken())}
        catch(ArchiveRuntimeUnavailable unavailable) {Assumptions.assumeTrue(false,unavailable.message)}
        Path root=Files.createDirectory(work.resolve('native'))
        byte[] inner=zip(['shared.txt':'hello'.bytes,'empty.txt':new byte[0]])
        byte[] outer=zip(['d/a.txt':'hello'.bytes,'d/inner.zip':inner])
        Files.write(root.resolve('a.zip'),outer);Files.write(root.resolve('b.zip'),outer);Files.writeString(root.resolve('plain.txt'),'hello')
        Path db=work.resolve('native.duckdb');long entry
        Dedup.open(db).withCloseable { d ->
            d.scan('ZIP',root);d.hash('ZIP',new StopToken(),false,true)
            assert d.analyzeArchives('ZIP',new ArchiveOptions(minFreeBytes:0)).duplicate_roots==1
            entry=d.store.rows("SELECT entry_id FROM entries WHERE filename='a.zip'")[0].entry_id as long
        }
        Files.walk(root).withCloseable { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        byte[] before=digest(db)
        ArchiveService service=new ArchiveService(new ScannerDatabase(db.toString()))
        assert service.browse(1,entry,'','d','','100',null).items*.filename==['a.txt','inner.zip']
        Map nested=service.browse(1,entry,'2','','','100',null)
        assert nested.items.find { it.filename=='shared.txt' }.archiveOccurrenceCount==4
        assert nested.items.find { it.filename=='shared.txt' }.filesystemOccurrenceCount==1
        assert Arrays.equals(before,digest(db))
    }

    private Map fixture() {
        Path root=Files.createDirectory(work.resolve('source'));Path db=work.resolve('scanner.duckdb')
        ['a.zip':'zipdata','b.zip':'zipdata','plain.txt':'hello','running.zip':'run-data','skipped.zip':'skip-data','part.zip.002':'part-data'].each { name,value -> Files.writeString(root.resolve(name),value) }
        Map ids
        Dedup.open(db).withCloseable { d ->
            d.scan('First',root);d.hash('First',new StopToken(),false,true)
            new ArchiveStore(d.store,2)
            ids=d.store.rows('SELECT filename,entry_id FROM entries').collectEntries { [it.filename,it.entry_id] }
        }
        mutate(db) { c ->
            ['root':'PARTIAL','child':'COMPLETE','orphan':'COMPLETE','running':'RUNNING','skipped':'SKIPPED'].each { name,state ->
                exec(c,'INSERT INTO archive_results(result_id,scan_id,state) VALUES (?,1,?)',[name,state])
            }
            ['a.zip':'root','b.zip':'root','running.zip':'running','skipped.zip':'skipped'].each { name,result ->
                String state=result=='root'?'PARTIAL':result=='running'?'RUNNING':'SKIPPED'
                exec(c,'INSERT INTO archive_inputs(scan_id,group_key,source_id,relative_path) VALUES(1,?,?,?)',[name,ids[name],name])
                exec(c,'INSERT INTO archive_jobs(scan_id,group_key,first_entry,status,result_id) VALUES(1,?,?,?,?)',[name,ids[name],state,result])
            }
            exec(c,"INSERT INTO archive_inputs(scan_id,group_key,source_id,relative_path) VALUES (1,'a.zip',?,'part.zip.002')",[ids['part.zip.002']])
            add(c,'root',1,'d/shared.txt','hello');add(c,'root',2,'d/shared.txt','hello')
            add(c,'root',3,'d/child.zip','X');add(c,'root',4,'😀/µ.txt','world')
            add(c,'root',5,'../escape','hello','SKIPPED');add(c,'root',6,'damaged','hello','DAMAGED')
            add(c,'root',7,'encrypted','hello');exec(c,"UPDATE archive_members SET encrypted=true WHERE result_id='root' AND ordinal=7")
            add(c,'root',8,'empty.txt','');add(c,'root',9,'unknown','hello');exec(c,"UPDATE archive_members SET actual_size=NULL,sha256=NULL WHERE result_id='root' AND ordinal=9")
            add(c,'root',10,'link','hello');exec(c,"UPDATE archive_members SET kind='SYMLINK' WHERE result_id='root' AND ordinal=10")
            add(c,'root',11,'emptydir','');exec(c,"UPDATE archive_members SET kind='DIRECTORY',integrity='METADATA',sha256=NULL WHERE result_id='root' AND ordinal=11")
            add(c,'root',12,'d/line\n#.txt','xyz')
            exec(c,"UPDATE archive_members SET group_key='nested' WHERE result_id='root' AND ordinal=3")
            exec(c,"INSERT INTO archive_nested VALUES('root','nested',3,'child',false)")
            add(c,'child',1,'shared.txt','hello');add(c,'child',2,'empty.txt','')
            add(c,'orphan',1,'never-visible','hello');add(c,'running',1,'staging','hello')
        }
        ScannerDatabase scanner=new ScannerDatabase(db.toString())
        [db:db,root:root,ids:ids,scanner:scanner,inventory:new InventoryService(scanner),service:new ArchiveService(scanner)]
    }
    private static void add(c,String result,long ordinal,String path,String content,String integrity='READ_OK') {
        exec(c,'''INSERT INTO archive_members(result_id,ordinal,relative_path,filename,kind,declared_size,actual_size,sha256,recovered_sha256,integrity,encrypted)
            VALUES(?,?,?,?,'FILE',?,?,?,?,?,false)''',[result,ordinal,path,path.tokenize('/').last(),content.bytes.length,content.bytes.length,sha(content.bytes),sha(content.bytes),integrity])
    }
    private static void mutate(Path db,Closure action) {DriverManager.getConnection('jdbc:duckdb:'+db).withCloseable { action.call(it) }}
    private static void exec(c,String sql,List values=[]) {c.prepareStatement(sql).withCloseable { s -> values.eachWithIndex { value,i -> s.setObject(i+1,value) };s.executeUpdate() }}
    private static List<Map> allPages(Closure fetch) {
        List<Map> items=[];String cursor=null;int pages=0
        while(true) {Map page=fetch.call(cursor);items.addAll(page.items);if(!page.page.hasMore)break;cursor=page.page.nextCursor;assert ++pages<100}
        items
    }
    private static String failure(Closure action) {assertThrows(ApiFailure) { action.call() }.code}
    private static String sha(byte[] bytes) {HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(bytes))}
    private static byte[] digest(Path path) {MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(path))}
    private static byte[] zip(Map<String,byte[]> files) {
        def bytes=new ByteArrayOutputStream();new ZipOutputStream(bytes).withCloseable { output ->
            files.each { name,payload -> def entry=new ZipEntry(name);entry.setTime(1700000000000L);output.putNextEntry(entry);output.write(payload);output.closeEntry() }
        };bytes.toByteArray()
    }
}
