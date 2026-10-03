package fnord.dedup.web

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping('/api/v1/saved-searches')
class SavedSearchController {
    private final WebStateStore state

    SavedSearchController(WebStateStore state) { this.state = state }

    @GetMapping
    Map list() { state.list() }

    @GetMapping('/{id}')
    Map get(@PathVariable('id') String id) { state.get(id) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map create(@RequestBody Map body) { state.create(body) }

    @PutMapping('/{id}')
    Map update(@PathVariable('id') String id, @RequestBody Map body) { state.update(id, body) }

    @DeleteMapping('/{id}')
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable('id') String id) { state.delete(id) }
}
