import fnord.dedup.*
import fnord.dedup.path.*
import fnord.dedup.merge.*
import java.nio.file.*
import java.util.concurrent.TimeUnit
import groovy.json.JsonSlurper

assert StoredPath.windows()
Path project=Path.of('').toAbsolutePath()
Path base=Files.createDirectories(project.resolve('build/windows-smoke'))
Path temp=Files.createTempDirectory('fnord-windows-smoke-')
Path java=Path.of(System.getProperty('java.home'),'bin','java.exe')
String cp=System.getProperty('java.class.path')
ScanOptions opts=new ScanOptions(batchSize:2,databaseThreads:1,memoryLimit:'128MB')
Path harness=temp.resolve('crash.groovy')
Files.writeString(harness, '''
import fnord.dedup.*
import fnord.dedup.cross.*
import fnord.dedup.merge.*
import java.nio.file.*
import java.util.concurrent.*
Path db=Path.of(args[0]),marker=Path.of(args[1]);String mode=args[2]
StopToken stop=new StopToken();CountDownLatch finished=new CountDownLatch(1)
Thread hook=new Thread({stop.cancel();finished.await(25,TimeUnit.SECONDS)} as Runnable)
Runtime.runtime.addShutdownHook(hook)
try {
    def progress={ Map e ->
        boolean hit=mode=='discovery' ? e.stage=='discovery' : mode=='hash' ? e.stage=='hashing' :
          mode=='cross' ? e.phase=='HASHES_COMMITTED' : (e.phase=='TABLE_COPIED' && e.table=='entries')
        if(hit){Files.writeString(marker,'checkpoint');while(!stop.cancelled)Thread.sleep(20)}
    }
    def options=new ScanOptions(batchSize:2,databaseThreads:1,memoryLimit:'128MB')
    if(mode=='merge') {
        def merger=new DatabaseMerger(options,stop);merger.progress=progress
        merger.merge(Path.of(args[3]),db)
    } else Dedup.open(db,options).withCloseable {d ->
        d.progress=progress
        if(mode=='cross')d.crossDuplicates(['A','B'],new CrossScanOptions(),stop){}
        else d.resume('A',stop)
    }
} finally {finished.countDown();try{Runtime.runtime.removeShutdownHook(hook)}catch(IllegalStateException ignored){}}
''')
List<String> messages=[]
try {
    for(String stage:['discovery','hash','cross','merge']) {
        Path folder=Files.createDirectory(temp.resolve(stage))
        Path root=Files.createDirectory(folder.resolve('source data'))
        24.times{Files.writeString(root.resolve('f-'+it),'same')}
        Path db=folder.resolve('state data.duckdb'),other=folder.resolve('other.duckdb'),marker=folder.resolve('marker')
        Dedup.open(db,opts).withCloseable{d ->
            if(stage=='discovery')d.createScan('A',root)
            else d.scan('A',root,new StopToken(),true)
            if(stage=='cross')d.scan('B',root,new StopToken(),true)
        }
        if(stage=='merge')Dedup.open(other,opts).withCloseable{d ->d.scan('B',root)}
        List<String> argv=[java.toString(),'-Xmx256m','-cp',cp,'groovy.ui.GroovyMain',harness.toString(),db.toString(),marker.toString(),stage,other.toString()]
        Path log=base.resolve(stage+'.log')
        Process child=new ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(90)
            while(!Files.exists(marker) && child.isAlive() && System.nanoTime()<end)Thread.sleep(30)
            assert Files.exists(marker):'Missing checkpoint: '+Files.readString(log)
            child.destroyForcibly()
            assert child.waitFor(15,TimeUnit.SECONDS)
        } finally {if(child.isAlive()){child.destroyForcibly();child.waitFor()}}
        if(stage=='merge') {
            Dedup.open(db,opts).withCloseable{d ->assert d.listScans()*.name==['A'];assert d.status('A').files==24}
            assert new DatabaseMerger(opts).merge(other,db).status=='IMPORTED'
        } else Dedup.open(db,opts).withCloseable{d ->
            if(stage=='cross'){
                assert d.status('A').hashes_completed>0
                def r=d.crossDuplicates(['A','B']){}
                assert !r.partial && r.duplicate_observations==48
                assert d.listScans()*.phase==['READY','READY']
            } else {
                if(stage=='hash')assert d.status('A').hashes_completed>0
                def s=d.resume('A');assert s.files==24 && s.hashes_completed==24 && s.phase=='COMPLETE'
            }
            assert d.store.rows('SELECT scan_id,entry_id FROM hashes GROUP BY scan_id,entry_id HAVING count(*)>1').empty
        }
        messages.add('PASS: Windows force termination and replay for '+stage)
        println messages.last()
    }
} finally {
    // Only this freshly allocated fixture directory is removed; no source paths are followed.
    Files.walk(temp).withCloseable{paths ->paths.sorted(Comparator.reverseOrder()).forEach{Files.delete(it)}}
    Files.writeString(base.resolve('summary.txt'),messages.join('\n')+'\n')
}
