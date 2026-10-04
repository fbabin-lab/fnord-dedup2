package fnord.dedup.web

import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping('/api/v1/duplicates')
class DuplicateController {
    private final DuplicateService duplicates
    private final SignatureService signatures

    DuplicateController(DuplicateService duplicates, SignatureService signatures) {
        this.duplicates = duplicates
        this.signatures = signatures
    }

    @PostMapping('/groups')
    Map groups(@RequestBody(required=false) Map request) { signatures.pageSignals(duplicates.groups(request ?: [:]), true) }

    @PostMapping('/groups/{groupId}/occurrences')
    Map occurrences(@PathVariable('groupId') String groupId,
                    @RequestBody(required=false) Map request) {
        signatures.pageSignals(duplicates.occurrences(groupId, request ?: [:]))
    }
}
