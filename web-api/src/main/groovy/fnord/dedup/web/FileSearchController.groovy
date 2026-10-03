package fnord.dedup.web

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping('/api/v1/search')
class FileSearchController {
    private final FileSearchService search

    FileSearchController(FileSearchService search) { this.search = search }

    @PostMapping('/files')
    Map files(@RequestBody(required=false) Map request) { search.search(request ?: [:]) }
}
