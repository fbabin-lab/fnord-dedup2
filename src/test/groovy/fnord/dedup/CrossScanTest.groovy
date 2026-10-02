package fnord.dedup

import fnord.dedup.cross.*
import fnord.dedup.hash.*
import fnord.dedup.cli.Main
import fnord.dedup.merge.DatabaseMerger
import fnord.dedup.path.StoredPath
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import static org.junit.jupiter.api.Assertions.assertThrows

class CrossScanTest {
    @TempDir Path work
    Path database() { work.resolve('scan.duckdb') }
    ScanOptions tuning(int batch=3, int workers=1) {
        new ScanOptions(batchSize:batch, workers:workers, databaseThreads:1,memoryLimit:'128MB')
    }
    Path seed(Dedup d, String name, Map<String,String> files, boolean discoverOnly=true) {
        Path root=Files.createDirectory(work.resolve(UUID.randomUUID().toString()))
        files.each { String path,String data ->
            Path file=root.resolve(path); Files.createDirectories(file.parent); Files.writeString(file,data)
        }
        d.scan(name,root,new StopToken(),discoverOnly)
        root
    }
    static Map compare(Dedup d,List<String> names=['A','B'],CrossScanOptions opts=new CrossScanOptions(),StopToken stop=new StopToken()) {
        List<Map> rows=[]
        Map summary=d.crossDuplicates(names,opts,stop) { Map row -> rows.add(new LinkedHashMap(row)) }
        [summary:summary,rows:rows]
    }
    static List<Map> durableScans(Dedup d) { d.store.rows('SELECT * FROM scans ORDER BY scan_id') }
    static List<Map> hashes(Dedup d) { d.store.rows('SELECT * FROM hashes ORDER BY scan_id,entry_id') }
    static List<String> tables(Dedup d) { d.store.rows("SELECT table_name FROM information_schema.tables WHERE table_catalog<>'temp' ORDER BY table_name")*.table_name }
    static void assertNoTemps(Dedup d) { assert d.store.rows("SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'cross_%'").empty }

    @Test void hashesUniqueWithinEachScanAndKeepsEveryOtherPersistentTableUntouched() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['one':'same']);seed(d,'B',['two':'same'])
            def before=durableScans(d);def schema=tables(d)
            def result=compare(d)
            assert result.rows.size()==2
            assert result.rows*.scan_name==['A','B']
            assert result.rows.every {it.copies==2 && it.scan_count==2 && !it.partial}
            assert result.summary.candidate_sizes==1
            assert result.summary.hashes_needed==2 && result.summary.hashes_completed_this_run==2
            assert result.summary.phase=='COMPLETE'
            assert durableScans(d)==before
            assert d.store.rows('SELECT * FROM scan_errors').empty
            assert tables(d)==schema
            assertNoTemps(d)
        }
    }

    @Test void sameSizeDifferentContentIsNotDuplicate() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['one':'same']);seed(d,'B',['two':'diff'])
            def r=compare(d)
            assert r.rows.empty && r.summary.duplicate_groups==0
            assert r.summary.hashes_completed_this_run==2 && !r.summary.partial
        }
    }

    @Test void existingNormalHashIsReusedWithoutEvenStattingAnOfflineFile() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            Path a=seed(d,'A',['one':'same','two':'same'],false)
            seed(d,'B',['three':'same'])
            assert d.status('A').hashes_completed==2
            Files.delete(a.resolve('one'));Files.delete(a.resolve('two'));Files.delete(a)
            def counter=new Counter();d.hasher=counter
            def r=compare(d)
            assert r.rows.size()==3 && r.summary.existing_candidate_hashes==2
            assert r.summary.hashes_completed_this_run==1 && counter.paths.size()==1
            assert !r.summary.partial
        }
    }

    @Test void allKnownHashesAreUsableOfflineAndRepeatedInvocationDoesNoReads() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            Path a=seed(d,'A',['one':'same']);Path b=seed(d,'B',['two':'same'])
            def first=compare(d);def old=hashes(d)
            Files.delete(a.resolve('one'));Files.delete(a);Files.delete(b.resolve('two'));Files.delete(b)
            d.hasher=[algorithm:{'SHA-256'},hash:{Path p,StopToken s,int n -> throw new AssertionError('Unexpected filesystem read')}] as FileHasher
            def r=compare(d,['B','A'])
            assert r.rows*.scan_name==['B','A']
            assert r.summary.hashes_needed==0 && r.summary.hashes_attempted==0
            assert r.summary.existing_candidate_hashes==2 && r.summary.report_complete
            assert hashes(d)==old
        }
    }

    @Test void selectsOnlySizesAcrossDistinctSelectedScans() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['match':'same','local-one':'local','local-two':'local','only-A':'a'])
            seed(d,'B',['match':'same','only-B':'bb'])
            seed(d,'C',['not-selected':'local'])
            def counter=new Counter();d.hasher=counter
            def r=compare(d)
            assert r.summary.candidate_files==2 && r.summary.candidate_sizes==1
            assert counter.paths.size()==2 && counter.paths.every { it.fileName.toString()=='match' }
            assert d.status('C').hashes_completed==0
        }
    }

    @Test void sameScanOnlyMatchesAreNotReportedAndThreeScansKeepAllOccurrences() {
        Dedup.open(database(),tuning(2,3)).withCloseable { d ->
            seed(d,'A',['a':'same','b':'same','only-here-1':'else','only-here-2':'else'])
            seed(d,'B',['c':'same','different':'abcd'])
            seed(d,'C',['d':'same','unmatched':'wxyz'])
            def counter=new Counter();d.hasher=counter
            def r=compare(d,['A','B','C'])
            assert r.summary.candidate_files==8 && r.summary.hashes_completed_this_run==8
            assert r.summary.duplicate_groups==1 && r.rows.size()==4
            assert r.rows.every { it.copies==4 && it.scan_count==3 }
            assert hashes(d).size()==8 && counter.paths.size()==8
        }
    }

    @Test void noCrossScanSizesMeansNoHashingAndZeroResults() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['a':'one','b':'one']);seed(d,'B',['b':'twos'])
            def counter=new Counter();d.hasher=counter
            def r=compare(d)
            assert r.summary.candidate_files==0 && r.summary.hashes_needed==0
            assert r.summary.duplicate_groups==0 && !r.summary.partial && counter.paths.empty
        }
    }

    @Test void emptyScansAreValidSelections() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',[:]);seed(d,'B',[:])
            assert compare(d).summary.phase=='COMPLETE'
        }
    }

    @Test void zeroBytesStillPassThroughValidatedHasher() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['empty':'']);seed(d,'B',['empty':''])
            def counter=new Counter();d.hasher=counter
            def r=compare(d)
            assert counter.paths.size()==2 && r.rows.size()==2
            assert r.rows*.sha256.unique()==['e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855']
        }
    }

    @Test void invalidSelectionNeverWritesHashes() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['a':'same']);seed(d,'B',['b':'same'])
            def counter=new Counter();d.hasher=counter
            [null,[],['A'],['A','A'],['A','A','B'],['A',''],['A',null]].each { names ->
                assertThrows(IllegalArgumentException) { compare(d,names) }
            }
            def error=assertThrows(IllegalArgumentException) { compare(d,['missing1','A','missing2']) }
            assert error.message.contains('missing1') && error.message.contains('missing2')
            assert hashes(d).empty && counter.paths.empty
            assertNoTemps(d)
        }
    }

    @Test void discoveringOrInconsistentCheckpointAndAlgorithmsAreRejectedBeforeReads() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['a':'same'])
            Path b=Files.createDirectory(work.resolve('B'));Files.writeString(b.resolve('b'),'same');d.createScan('B',b)
            assertThrows(IllegalStateException) { compare(d) }
            d.discover('B')
            d.store.exec("UPDATE scans SET algorithm='BLAKE3' WHERE name='B'")
            assertThrows(IllegalArgumentException) { compare(d) }
            d.store.exec("UPDATE scans SET algorithm='SHA-256',active_dir=1 WHERE name='B'")
            assertThrows(IllegalStateException) { compare(d) }
            d.store.exec("UPDATE scans SET active_dir=NULL WHERE name='B'")
            d.hasher=[algorithm:{'BLAKE3'},hash:{Path p,StopToken s,int n -> null}] as FileHasher
            assertThrows(IllegalArgumentException) { compare(d) }
            assert hashes(d).empty
        }
    }

    @Test void interruptedOrdinaryHashingIsAcceptedWithoutChangingItsLifecycle() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['a':'same']);seed(d,'B',['b':'same'])
            d.store.exec("UPDATE scans SET phase='HASHING' WHERE name='B'")
            def before=durableScans(d)
            assert compare(d).summary.hashes_completed_this_run==2
            assert durableScans(d)==before
        }
    }

    @Test void priorScanErrorsAreWarningsAndAreNeverClearedOrReclassified() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['a':'same']);seed(d,'B',['b':'same'])
            d.store.exec("INSERT INTO scan_errors VALUES (1,'DISCOVERY','offline','unreadable directory',1)")
            d.store.exec("UPDATE scans SET phase='COMPLETE_WITH_ERRORS' WHERE name='A'")
            def oldErrors=d.store.rows('SELECT * FROM scan_errors');def before=durableScans(d)
            def r=compare(d)
            assert r.rows.size()==2 && r.rows.every {it.partial}
            assert r.summary.partial && r.summary.hash_failures==0
            assert r.summary.scan_warnings[0].discovery_errors==1
            assert r.summary.prior_scan_errors==1
            assert durableScans(d)==before && d.store.rows('SELECT * FROM scan_errors')==oldErrors
        }
    }

    @Test void missingAndChangedCandidatesStayUnknownWhileGoodDuplicatesRemainVisible() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            Path a=seed(d,'A',['good':'same','missing':'same','changed':'same'])
            seed(d,'B',['good':'same'])
            Files.delete(a.resolve('missing'))
            def time=Files.getLastModifiedTime(a.resolve('changed')).toInstant()
            Files.writeString(a.resolve('changed'),'diff')
            Files.setLastModifiedTime(a.resolve('changed'),FileTime.from(time.plusNanos(1)))
            List<Map> errors=[]
            def r=compare(d,['A','B'],new CrossScanOptions(onError:{errors.add(it)},maxErrorSamples:1))
            assert r.summary.hash_failures==2 && r.summary.unresolved_candidates==2 && r.summary.partial
            assert r.rows.size()==2 && r.rows.every { it.partial }
            assert errors*.code.toSet()==['SOURCE_CHANGED','FILE_MISSING'].toSet()
            assert r.summary.error_samples.size()==1
            assert hashes(d).size()==2 && d.store.rows('SELECT * FROM scan_errors').empty
        }
    }

    @Test void readFailuresCanBeRetriedUsingOnlyAbsentHashes() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['a':'same']);seed(d,'B',['b':'same'])
            d.hasher=new DenyBHasher()
            def r=compare(d)
            assert r.summary.hash_failures==1 && r.rows.empty && r.summary.partial
            assert r.summary.error_samples[0].code=='PERMISSION_DENIED'
            def counter=new Counter();d.hasher=counter
            def retry=compare(d)
            assert retry.rows.size()==2 && !retry.summary.partial
            assert retry.summary.hashes_completed_this_run==1 && counter.paths.size()==1
        }
    }

    @Test void changingAFileDuringHashingDiscardsDigest() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['a':'same']);seed(d,'B',['b':'same'])
            def real=new Sha256Hasher()
            d.hasher=[algorithm:{'SHA-256'},hash:{Path p,StopToken s,int n ->
                def value=real.hash(p,s,n)
                if (p.fileName.toString()=='a') Files.setLastModifiedTime(p,FileTime.from(Files.getLastModifiedTime(p).toInstant().plusSeconds(1)))
                value
            }] as FileHasher
            def r=compare(d)
            assert r.summary.hash_failures==1 && hashes(d).size()==1
            assert r.summary.error_samples[0].code=='SOURCE_CHANGED'
        }
    }

    @Test void invalidHasherDigestOrLengthDoesNotBecomeConfirmedContent() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['a':'same']);seed(d,'B',['b':'same'])
            d.hasher=[algorithm:{'SHA-256'},hash:{Path p,StopToken s,int n -> new HashValue('invalid',4)}] as FileHasher
            assertThrows(IllegalArgumentException) { compare(d) }
            assert hashes(d).empty;assertNoTemps(d)
            d.hasher=[algorithm:{'SHA-256'},hash:{Path p,StopToken s,int n -> new HashValue('a'*64,3)}] as FileHasher
            def r=compare(d)
            assert hashes(d).empty && r.summary.hash_failures==2 && r.rows.empty
        }
    }

    @Test void replacedRegularFileSymlinkIsNeverFollowed() {
        Assumptions.assumeFalse(StoredPath.windowsHost(), 'POSIX symlink fixture')
        Dedup.open(database(),tuning()).withCloseable { d ->
            Path a=seed(d,'A',['file':'same','was-file':'same']);seed(d,'B',['file':'same'])
            Files.delete(a.resolve('was-file'));Files.createSymbolicLink(a.resolve('was-file'),a.resolve('file'))
            def r=compare(d)
            assert r.rows.size()==2 && r.summary.hash_failures==1
            assert r.summary.error_samples[0].code=='NOT_REGULAR_ANYMORE'
        }
    }

    @Test void linksAndFifosInInventoryAreNotHashCandidates() {
        Assumptions.assumeFalse(StoredPath.windowsHost(), 'POSIX FIFO fixture')
        Dedup.open(database(),tuning()).withCloseable { d ->
            Path a=Files.createDirectory(work.resolve('special-A'))
            Path b=Files.createDirectory(work.resolve('special-B'))
            [a,b].each { Path root ->
                Files.writeString(root.resolve('regular'),'same')
                Files.createSymbolicLink(root.resolve('link'),root.resolve('regular'))
                def process=new ProcessBuilder('mkfifo',root.resolve('pipe').toString()).start()
                assert process.waitFor()==0
            }
            d.scan('A',a,new StopToken(),true);d.scan('B',b,new StopToken(),true)
            def counter=new Counter();d.hasher=counter
            def r=compare(d)
            assert r.summary.candidate_files==2 && r.rows.size()==2
            assert counter.paths.every {it.fileName.toString()=='regular'}
        }
    }

    @Test void thousandsOfMissingCandidatesUseSmallPagesAndBoundedBatches() {
        Dedup.open(database(),tuning(127,3)).withCloseable { d ->
            Map<String,String> files=(1..1500).collectEntries { ['file-'+it,'same'] }
            seed(d,'A',files);seed(d,'B',files)
            long previous=0L;int commits=0
            d.progress={Map e -> if(e.phase=='HASHES_COMMITTED') {
                long current=e.hashes_completed_this_run
                assert current-previous<=127
                previous=current;commits++
            }}
            long reported=0L
            def summary=d.crossDuplicates(['A','B']) { reported++ }
            assert reported==3000 && summary.hashes_completed_this_run==3000
            assert commits>=24
            assert d.store.rows('SELECT scan_id,entry_id FROM hashes GROUP BY scan_id,entry_id HAVING count(*)<>1').empty
        }
    }

    @Test void cancellationPreservesOnlyCommittedHashesAndRerunContinues() {
        Map<String,String> files=(1..12).collectEntries { ["f${it}".toString(),'same'] }
        def stop=new StopToken()
        def before
        Dedup.open(database(),tuning(1)).withCloseable { d ->
            seed(d,'A',files);seed(d,'B',files);before=durableScans(d)
            d.progress={Map e -> if(e.phase=='HASHES_COMMITTED') stop.cancel()}
            def r=compare(d,['A','B'],new CrossScanOptions(),stop)
            assert r.summary.phase=='PAUSED' && r.summary.cancelled && !r.summary.report_complete
            assert r.summary.hashes_completed_this_run==1 && r.summary.unresolved_candidates==23
            assert r.rows.empty && hashes(d).size()==1
            assert durableScans(d)==before
        }
        Dedup.open(database(),tuning(2,3)).withCloseable { d ->
            def counter=new Counter();d.hasher=counter
            def r=compare(d)
            assert r.rows.size()==24 && r.summary.hashes_completed_this_run==23
            assert counter.paths.size()==23 && hashes(d).size()==24 && durableScans(d)==before
            assertNoTemps(d)
        }
    }

    @Test void cancellationDuringReportingDoesNotClaimACompleteReport() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['a':'same']);seed(d,'B',['b':'same'])
            def stop=new StopToken()
            def result=d.crossDuplicates(['A','B'],new CrossScanOptions(),stop) { stop.cancel() }
            assert result.cancelled && !result.report_complete && result.duplicate_observations_reported==1
            assert result.hashes_completed_this_run==2
        }
    }

    @Test void samePhysicalPathAndUnusualNamesRetainIndependentObservations() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            String a='Scan, A \"é\"',b='scan, A \"é\"'
            Map<String,String> files = StoredPath.windowsHost() ? ['a é':'same','-leading':'same'] : ['a\n\t"é':'same','-leading':'same']
            Path root=seed(d,a,files)
            d.scan(b,root,new StopToken(),true)
            def r=compare(d,[a,b])
            assert r.rows.size()==4 && r.rows*.path.toSet().size()==2
            assert r.rows*.scan_name.toSet()==[a,b].toSet()
            assert r.rows*.filename.toSet()==files.keySet()
        }
    }

    @Test void archiveAndImagePayloadsAreNotExtractedByComparison() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['backup.zip':'not really zip','disk.qcow2':'not really qcow'])
            seed(d,'B',['backup.zip':'not really zip','disk.qcow2':'not really qcow'])
            def r=compare(d)
            assert r.summary.duplicate_groups==2 && r.rows.size()==4
            assert tables(d).every { !it.startsWith('archive_') && !it.startsWith('image_') }
        }
    }

    @Test void databaseMergedScansCompareAfterIdRemappingAndRemainMergeable() {
        Path source=work.resolve('source.duckdb')
        Dedup.open(source,tuning()).withCloseable { seed(it,'imported',['a':'same']) }
        Dedup.open(database(),tuning()).withCloseable { seed(it,'existing',['b':'same']) }
        new DatabaseMerger(tuning()).merge(source,database())
        Dedup.open(database(),tuning()).withCloseable { d ->
            assert d.store.scan('imported').scan_id!=1
            def r=compare(d,['existing','imported'])
            assert r.rows.size()==2 && r.summary.hashes_completed_this_run==2
        }
        Path other=work.resolve('other.duckdb')
        Dedup.open(other,tuning()).close()
        assert new DatabaseMerger(tuning()).merge(database(),other).status=='IMPORTED'
    }

    @Test void ambiguousPersistentHashKeysAreRefusedRatherThanMultiplied() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['a':'same']);seed(d,'B',['b':'same'])
            compare(d)
            d.store.exec('INSERT INTO hashes SELECT * FROM hashes LIMIT 1')
            assertThrows(IllegalStateException) { compare(d) }
            assertNoTemps(d)
        }
    }

    @Test void sqlBatchRefusesConflictingHashesAndIsTransactional() {
        Dedup.open(database(),tuning()).withCloseable { d ->
            seed(d,'A',['a':'same']);seed(d,'B',['b':'same'])
            new CrossScanStore(d.store).withCloseable { store ->
                store.select(['A','B'],new StopToken());store.prepare(new StopToken())
                def page=store.page(0,100)
                def one=[scan_id:page[0].scan_id,entry_id:page[0].entry_id,sha256:'a'*64]
                assert store.saveHashes([one])==1
                assert store.saveHashes([one])==0
                def good=[scan_id:page[1].scan_id,entry_id:page[1].entry_id,sha256:'b'*64]
                assertThrows(IllegalStateException) {store.saveHashes([good,one+[sha256:'c'*64]])}
                assert hashes(d).size()==1
            }
        }
    }

    @Test void cliProducesOnlyOccurrencesOnStdoutAndAlwaysSummaryOnStderr() {
        Dedup.open(database(),tuning()).withCloseable {d -> seed(d,'A, one',['a':'same']);seed(d,'B two',['b':'same'])}
        StringWriter out=new StringWriter(),err=new StringWriter()
        def cli=Main.commandLine().setOut(new PrintWriter(out)).setErr(new PrintWriter(err))
        int code=cli.execute(['--db',database().toString(),'cross-duplicates','--scan','A, one','--scan','B two','--workers','2','--format','jsonl','--quiet'] as String[])
        assert code==0 : err.toString()
        def rows=out.toString().readLines().collect { new JsonSlurper().parseText(it) }
        assert rows.size()==2 && rows.every { it.sha256 && it.scan_count==2 }
        def summary=new JsonSlurper().parseText(err.toString())
        assert summary.event=='cross_scan_summary' && summary.hashes_completed_this_run==2
        assert summary.report_complete && !summary.partial
    }

    @Test void cliPartialAndUsageExitCodesAreExplicit() {
        Path a
        Dedup.open(database(),tuning()).withCloseable { d -> a=seed(d,'A',['a':'same']);seed(d,'B',['b':'same']) }
        Files.delete(a.resolve('a'))
        def invoke={ List<String> args ->
            StringWriter out=new StringWriter(),err=new StringWriter()
            int code=Main.commandLine().setOut(new PrintWriter(out)).setErr(new PrintWriter(err)).execute((['--db',database().toString(),'cross-duplicates']+args) as String[])
            [code:code,out:out.toString(),err:err.toString()]
        }
        assert invoke([]).code==2
        assert invoke(['--scan','A']).code==1
        assert invoke(['--scan','A','--scan','A']).code==1
        assert invoke(['--scan','A','--scan','B','--format','unknown']).code==2
        def partial=invoke(['--scan','A','--scan','B','--quiet','--format','jsonl'])
        assert partial.code==3 && partial.out.empty
        def events=partial.err.readLines().collect {new JsonSlurper().parseText(it)}
        assert events[0].code=='FILE_MISSING'
        assert events.last().partial && events.last().unresolved_candidates==1
    }

    static class DenyBHasher implements FileHasher {
        final Sha256Hasher actual=new Sha256Hasher()
        String algorithm() {'SHA-256'}
        HashValue hash(Path p,StopToken s,int n) {
            if (p.fileName.toString()=='b') throw new AccessDeniedException(p.toString())
            actual.hash(p,s,n)
        }
    }

    static class Counter implements FileHasher {
        final Queue<Path> paths=new ConcurrentLinkedQueue<>()
        final Sha256Hasher actual=new Sha256Hasher()
        String algorithm() {'SHA-256'}
        HashValue hash(Path path,StopToken stop,int buffer) {paths.add(path);actual.hash(path,stop,buffer)}
    }
}
