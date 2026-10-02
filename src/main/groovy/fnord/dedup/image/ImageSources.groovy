package fnord.dedup.image

import fnord.dedup.StopToken
import fnord.dedup.hash.Sha256Hasher
import fnord.dedup.store.DuckStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Bounded header-only reference validation; QEMU remains the actual disk decoder.
 * No native image opener runs until every referenced source has been approved.
 */
class ImageSources {
    static final Map<String,String> FORMATS = [vmdk:'vmdk', qcow:'qcow', qcow2:'qcow2', vdi:'vdi', vhd:'vpc', vpc:'vpc', vhdx:'vhdx', qed:'qed', img:'raw', raw:'raw', dd:'raw', iso:'raw', dmg:'dmg']
    final DuckStore store
    final Map scan
    final ImageOptions options
    final StopToken stop
    final List<Map> components = []
    private final Set<String> active = [] as Set
    private final Path root

    ImageSources(DuckStore store, Map scan, ImageOptions options, StopToken stop) {
        this.store=store; this.scan=scan; this.options=options; this.stop=stop
        root=Path.of(scan.root as String)
    }
    static String format(String name) { FORMATS[name.tokenize('.').last().toLowerCase(Locale.ROOT)] }

    Map resolve(Map source) {
        components.clear(); active.clear()
        Map graph = visit(source, source.format as String, -1L, 'PRIMARY')
        [graph:graph, components:components]
    }

    static Path validatePath(Path root, String relative) {
        Path path = root.resolve(relative).normalize()
        if (!path.startsWith(root) || path == root) throw new ImageFailure('UNSAFE_DEPENDENCY','Image dependency is outside the source root','SECURITY')
        Path current=root
        for (Path part : root.relativize(path)) {
            current=current.resolve(part)
            if (Files.isSymbolicLink(current)) throw new ImageFailure('UNSAFE_DEPENDENCY','Image sources cannot traverse symlinks','SECURITY')
        }
        path
    }

    void validate(Map row) {
        stop.check()
        Path path=validatePath(root,row.relative_path as String)
        BasicFileAttributes a
        try { a=Files.readAttributes(path,BasicFileAttributes,LinkOption.NOFOLLOW_LINKS) }
        catch (IOException e) { throw new ImageFailure('MISSING_COMPONENT',e.message) }
        def t=a.lastModifiedTime().toInstant()
        if (!a.isRegularFile() || a.size()!= (row.size as long) || t.epochSecond != (row.modified_sec as long) || t.nano != (row.modified_nano as int))
            throw new ImageFailure('SOURCE_CHANGED','Image component differs from saved inventory: '+row.relative_path,'CONTENT')
    }

    Map dependency(Map parent, String reference) {
        if (!reference || reference.indexOf('\u0000')>=0 || reference.contains(':') || reference.contains('\\') || Path.of(reference).isAbsolute())
            throw new ImageFailure('UNSAFE_DEPENDENCY','Absolute, protocol or invalid image dependency rejected','SECURITY')
        Path p=Path.of(parent.relative_path as String).parent ?: Path.of('')
        String relative=p.resolve(reference).normalize().toString()
        validatePath(root, relative)
        List<Map> rows=store.rows("SELECT entry_id AS source_id,relative_path,size,modified_sec,modified_nano FROM entries WHERE scan_id=? AND relative_path=? AND kind='FILE'",scan.scan_id,relative)
        if (rows.size()!=1) throw new ImageFailure('MISSING_COMPONENT','Required image component is not uniquely present in this scan: '+relative)
        rows[0]
    }

    private Map visit(Map row, String fmt, long parent, String role) {
        validate(row)
        if (components.size()>=options.maxComponents) throw new ImageFailure('MAX_COMPONENTS','Image dependency limit reached','LIMIT')
        String key=row.relative_path as String
        if (!active.add(key)) throw new ImageFailure('DEPENDENCY_CYCLE','Cyclic image dependencies')
        long ordinal=components.size()
        Map c=new LinkedHashMap(row)
        c.putAll([ordinal:ordinal,parent_ordinal:parent,role:role,format:fmt,path:root.resolve(key).toString()])
        components.add(c)
        try {
            Map h=header(Path.of(c.path as String),fmt)
            Map graph=[driver:fmt, file:[driver:'file',filename:c.path]]
            if (fmt in ['qcow','qcow2','qed','vmdk']) {
                graph.backing=null
                if (h.backing) {
                    Map child=dependency(row,h.backing as String)
                    String childFormat=(h.backing_format ?: format(child.relative_path as String) ?: 'raw') as String
                    if (!(childFormat in FORMATS.values())) throw new ImageFailure('UNSUPPORTED_BACKING_FORMAT','Unsupported backing format','CAPABILITY')
                    graph.backing=visit(child,childFormat,ordinal,'BACKING')
                }
            }
            for (Map extent : (h.extents ?: [])) {
                Map child=dependency(row,extent.name as String)
                if (child.relative_path==row.relative_path) continue // Embedded descriptor's self extent.
                validate(child)
                if (components.size()>=options.maxComponents) throw new ImageFailure('MAX_COMPONENTS','Image component limit reached','LIMIT')
                // Sparse extents must not smuggle a second descriptor/dependency graph.
                if (extent.type=='SPARSE') {
                    Map eh=header(root.resolve(child.relative_path as String),'vmdk',true)
                    if (eh.backing || eh.extents) throw new ImageFailure('UNSAFE_DEPENDENCY','Nested VMDK extent descriptors are not supported','SECURITY')
                }
                components.add(new LinkedHashMap(child)+[ordinal:(long)components.size(),parent_ordinal:ordinal,role:'EXTENT',format:'raw',path:root.resolve(child.relative_path as String).toString()])
            }
            validate(row)
            graph
        } finally { active.remove(key) }
    }

    static byte[] readAt(RandomAccessFile file, long offset, int length) {
        if (offset<0 || length<0 || length>1048576 || offset>file.length()-length) throw new ImageFailure('INVALID_IMAGE','Truncated/oversized image header','CONTENT')
        byte[] data=new byte[length]; file.seek(offset); file.readFully(data); data
    }

    static Map header(Path path, String fmt, boolean extentOnly=false) {
        if (fmt in ['raw','dmg']) return [:]
        new RandomAccessFile(path.toFile(),'r').withCloseable { RandomAccessFile f ->
            byte[] bytes=readAt(f,0,(int)Math.min(f.length(),512L))
            ByteBuffer b=ByteBuffer.wrap(bytes)
            if (fmt in ['qcow','qcow2']) {
                if (bytes.length<48 || b.getInt(0)!=0x514649fb) throw new ImageFailure('INVALID_IMAGE','Invalid QCOW signature','CONTENT')
                int version=b.getInt(4)
                if ((fmt=='qcow' && version!=1) || (fmt=='qcow2' && !(version in [2,3]))) throw new ImageFailure('INVALID_IMAGE','QCOW version does not match explicit format','CONTENT')
                if (b.getInt(version==1 ? 36 : 32)!=0) throw new ImageFailure('ENCRYPTED_UNREADABLE','Encrypted QCOW image','ENCRYPTION')
                if (version==3 && (bytes.length<104 || (b.getLong(72)&4L)!=0)) throw new ImageFailure('UNSUPPORTED_EXTERNAL_DATA','QCOW2 external data files are not supported','CAPABILITY')
                long offset=b.getLong(8); int size=b.getInt(16)
                if (size==0) return [:]
                if (size<0 || size>4096) throw new ImageFailure('INVALID_IMAGE','Invalid backing filename length','CONTENT')
                Map result=[backing:new String(readAt(f,offset,size),'UTF-8')]
                if (version>=2) {
                    long at=version==3 ? Integer.toUnsignedLong(b.getInt(100)) : 72L
                    int count=0
                    while (at+8<=offset && ++count<=128) {
                        ByteBuffer ext=ByteBuffer.wrap(readAt(f,at,8))
                        int magic=ext.getInt(0), length=ext.getInt(4)
                        if (magic==0) break
                        if (length<0 || length>1048576 || at+8L+length>offset) throw new ImageFailure('INVALID_IMAGE','Invalid QCOW extension','CONTENT')
                        if (magic==(int)0xe2792aca) result.backing_format=new String(readAt(f,at+8,length),'US-ASCII')
                        at+=8L+((length+7L)&~7L)
                    }
                }
                return result
            }
            if (fmt=='qed') {
                b.order(ByteOrder.LITTLE_ENDIAN)
                if (bytes.length<64 || b.getInt(0)!=0x00444551) throw new ImageFailure('INVALID_IMAGE','Invalid QED header','CONTENT')
                long features=b.getLong(16)
                if ((features&1L)==0) return [:]
                long offset=Integer.toUnsignedLong(b.getInt(56)); int length=b.getInt(60)
                if (length<1 || length>4096) throw new ImageFailure('INVALID_IMAGE','Invalid QED backing reference','CONTENT')
                return [backing:new String(readAt(f,offset,length),'UTF-8'), backing_format:(features&4L)!=0 ? 'raw' : null]
            }
            if (fmt=='vhdx') {
                if (bytes.length<8 || new String(bytes,0,8,'US-ASCII')!='vhdxfile') throw new ImageFailure('INVALID_IMAGE','Invalid VHDX signature','CONTENT')
                boolean parameters=false
                // Inspect both redundant region maps. A stale parent-bearing copy
                // is conservatively rejected; native QEMU still validates CRCs.
                for (long tableOffset : [196608L,262144L]) {
                    ByteBuffer table=ByteBuffer.wrap(readAt(f,tableOffset,65536)).order(ByteOrder.LITTLE_ENDIAN)
                    if (table.getInt(0)!=0x69676572) continue
                    int count=table.getInt(8)
                    if (count<0 || count>2047) throw new ImageFailure('INVALID_IMAGE','Invalid VHDX region count','CONTENT')
                    for (int i=0;i<count;i++) {
                        int pos=16+32*i
                        if (guid(table.array(),pos)!='8b7ca206-4790-4b9a-b8fe-575f050f886e') continue
                        long metaOffset=table.getLong(pos+16)
                        ByteBuffer meta=ByteBuffer.wrap(readAt(f,metaOffset,65536)).order(ByteOrder.LITTLE_ENDIAN)
                        if (new String(meta.array(),0,8,'US-ASCII')!='metadata') throw new ImageFailure('INVALID_IMAGE','Invalid VHDX metadata table','CONTENT')
                        int n=Short.toUnsignedInt(meta.getShort(10))
                        if (n>2047) throw new ImageFailure('INVALID_IMAGE','Invalid VHDX metadata count','CONTENT')
                        for (int j=0;j<n;j++) {
                            int p=32+32*j
                            String id=guid(meta.array(),p)
                            if (id=='a8d35f2d-b30b-454d-abf7-d3d84834ab0c') throw new ImageFailure('UNSUPPORTED_DEPENDENCY','VHDX parent locators are not supported','CAPABILITY')
                            if (id=='caa16737-fa36-4d43-b3b6-33f0aa44e76b') {
                                long offset=Integer.toUnsignedLong(meta.getInt(p+16))
                                ByteBuffer params=ByteBuffer.wrap(readAt(f,Math.addExact(metaOffset,offset),8)).order(ByteOrder.LITTLE_ENDIAN)
                                if ((params.getInt(4)&2)!=0) throw new ImageFailure('UNSUPPORTED_DEPENDENCY','Differencing VHDX is not supported','CAPABILITY')
                                parameters=true
                            }
                        }
                    }
                }
                if (!parameters) throw new ImageFailure('INVALID_IMAGE','VHDX file parameters were not found','CONTENT')
                return [:]
            }
            if (fmt=='vdi') {
                b.order(ByteOrder.LITTLE_ENDIAN)
                if (bytes.length<80 || b.getInt(64)!=(int)0xbeda107f) throw new ImageFailure('INVALID_IMAGE','Invalid VDI signature','CONTENT')
                if (b.getInt(76)==4) throw new ImageFailure('UNSUPPORTED_DEPENDENCY','Differencing VDI requires an unsupported parent layout','CAPABILITY')
                return [:]
            }
            if (fmt=='vpc') {
                ByteBuffer footer=ByteBuffer.wrap(readAt(f,f.length()-512,512))
                if (new String(footer.array(),0,8,'US-ASCII')!='conectix') throw new ImageFailure('INVALID_IMAGE','Invalid VHD footer','CONTENT')
                if (footer.getInt(60)==4) throw new ImageFailure('UNSUPPORTED_DEPENDENCY','Differencing VHD is not supported by this provider','CAPABILITY')
                return [:]
            }
            if (fmt=='vmdk') {
                b.order(ByteOrder.LITTLE_ENDIAN)
                String descriptor
                if (bytes.length>=44 && b.getInt(0)==0x564d444b) {
                    long offset=b.getLong(28), sectors=b.getLong(36)
                    if (offset==0 || sectors==0) {
                        if (extentOnly) return [:]
                        throw new ImageFailure('MISSING_DESCRIPTOR','VMDK sparse extent has no standalone descriptor')
                    }
                    if (offset<0 || sectors<1 || sectors>2048) throw new ImageFailure('INVALID_IMAGE','Oversized VMDK descriptor','CONTENT')
                    descriptor=new String(readAt(f,Math.multiplyExact(offset,512L),(int)(sectors*512L)),'UTF-8').split('\u0000',2)[0]
                } else {
                    if (f.length()>1048576) throw new ImageFailure('INVALID_IMAGE','Not a recognized VMDK descriptor','CONTENT')
                    descriptor=new String(readAt(f,0,(int)f.length()),'UTF-8')
                    if (!descriptor.contains('Disk DescriptorFile')) throw new ImageFailure('INVALID_IMAGE','Not a VMDK descriptor','CONTENT')
                }
                Map result=[extents:[]]
                descriptor.readLines().each { String line ->
                    line=line.trim()
                    if (line.startsWith('parentFileNameHint')) {
                        def m=line =~ /^parentFileNameHint\s*=\s*"([^"\r\n]+)"\s*$/
                        if (!m.matches()) throw new ImageFailure('INVALID_IMAGE','Ambiguous VMDK parent reference','CONTENT')
                        result.backing=m[0][1]; result.backing_format='vmdk'
                    } else if (line =~ /^(RW|RDONLY|NOACCESS)\s/) {
                        def m=line =~ /^(RW|RDONLY|NOACCESS)\s+[0-9]+\s+(SPARSE|FLAT)\s+"([^"\r\n]+)"(?:\s+[0-9]+)?\s*$/
                        if (m.matches()) result.extents.add([type:m[0][2],name:m[0][3]])
                        else if (!(line =~ /^(RW|RDONLY|NOACCESS)\s+[0-9]+\s+ZERO\s*$/).matches()) throw new ImageFailure('UNSUPPORTED_EXTENT','Unsupported VMDK extent declaration','CAPABILITY')
                    }
                }
                return result
            }
            [:]
        } as Map
    }

    private static String guid(byte[] source,int offset) {
        ByteBuffer b=ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
        String first=String.format('%08x-%04x-%04x-',b.getInt(offset),Short.toUnsignedInt(b.getShort(offset+4)),Short.toUnsignedInt(b.getShort(offset+6)))
        String tail=HexFormat.of().formatHex(Arrays.copyOfRange(source,offset+8,offset+16))
        first+tail.substring(0,4)+'-'+tail.substring(4)
    }

    String fingerprint(boolean contents) {
        MessageDigest digest=MessageDigest.getInstance('SHA-256')
        ByteArrayOutputStream bytes=new ByteArrayOutputStream()
        DataOutputStream out=new DataOutputStream(bytes)
        out.writeUTF(contents ? 'FNORD-IMAGE-SET-1' : 'FNORD-IMAGE-SHAPE-1'); out.writeInt(components.size())
        Sha256Hasher hasher=new Sha256Hasher()
        components.each { Map c ->
            validate(c)
            out.writeLong(c.ordinal as long); out.writeLong(c.parent_ordinal as long)
            out.writeUTF(c.role as String); out.writeUTF(c.format as String); out.writeLong(c.size as long)
            if (contents) {
                def hash=hasher.hash(Path.of(c.path as String),stop,1048576)
                if (hash.bytesRead!=(c.size as long)) throw new ImageFailure('SOURCE_CHANGED','Image component changed while checksumming','CONTENT')
                validate(c); c.sha256=hash.hex; out.write(HexFormat.of().parseHex(hash.hex))
            }
        }
        out.flush(); HexFormat.of().formatHex(digest.digest(bytes.toByteArray()))
    }
}
