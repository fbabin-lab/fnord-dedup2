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

    DuplicateController(DuplicateService duplicates) { this.duplicates = duplicates }

    @PostMapping('/groups')
    Map groups(@RequestBody(required=false) Map request) { duplicates.groups(request ?: [:]) }

    @PostMapping('/groups/{groupId}/occurrences')
    Map occurrences(@PathVariable('groupId') String groupId,
                    @RequestBody(required=false) Map request) {
        duplicates.occurrences(groupId, request ?: [:])
    }
}
