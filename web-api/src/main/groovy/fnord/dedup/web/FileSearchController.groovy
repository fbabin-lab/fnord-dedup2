package fnord.dedup.web

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping('/api/v1/search')
class FileSearchController {
    private final FileSearchService search
    private final SignatureService signatures

    FileSearchController(FileSearchService search, SignatureService signatures) {
        this.search = search
        this.signatures = signatures
    }

    @PostMapping('/files')
    Map files(@RequestBody(required=false) Map request) { signatures.pageSignals(search.search(request ?: [:])) }
}
