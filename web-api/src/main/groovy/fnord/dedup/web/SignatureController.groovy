package fnord.dedup.web

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping('/api/v1/signatures')
class SignatureController {
    private final SignatureService signatures

    SignatureController(SignatureService signatures) { this.signatures = signatures }

    @GetMapping
    Map list(@RequestParam(value='limit', required=false) String limit,
             @RequestParam(value='cursor', required=false) String cursor) { signatures.list(limit, cursor) }

    @GetMapping('/{id}')
    Map get(@PathVariable('id') String id) { signatures.get(id) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map create(@RequestBody Map body) { signatures.create(body) }

    @PutMapping('/{id}')
    Map update(@PathVariable('id') String id, @RequestBody Map body) { signatures.update(id, body) }

    @DeleteMapping('/{id}')
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable('id') String id) { signatures.delete(id) }

    @GetMapping('/{id}/matches')
    Map matches(@PathVariable('id') String id,
                @RequestParam(value='limit', required=false) String limit,
                @RequestParam(value='cursor', required=false) String cursor) {
        signatures.matches(id, limit, cursor)
    }
}
