package fnord.dedup.hash

import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor

@CompileStatic
@TupleConstructor
final class HashValue {
    final String hex
    final long bytesRead
}
