package fnord.dedup.web

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping('/api/v1/scenarios')
class ScenarioController {
    private final ScenarioService scenarios
    ScenarioController(ScenarioService scenarios) { this.scenarios = scenarios }

    @GetMapping
    Map list(@RequestParam(value='limit', defaultValue='50') int limit,
             @RequestParam(value='cursor', required=false) String cursor) { scenarios.list(limit, cursor) }
    @GetMapping('/{id}')
    Map get(@PathVariable('id') String id) { scenarios.get(id) }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map create(@RequestBody Map body) { scenarios.create(body) }
    @PutMapping('/{id}')
    Map update(@PathVariable('id') String id, @RequestBody Map body) { scenarios.update(id, body) }
    @DeleteMapping('/{id}')
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable('id') String id, @RequestParam('revision') long revision) { scenarios.delete(id, revision) }
    @PostMapping('/{id}/generate')
    Map generate(@PathVariable('id') String id, @RequestBody Map body) { scenarios.generate(id, body) }
    @PostMapping('/{id}/validate')
    Map validate(@PathVariable('id') String id, @RequestBody Map body) { scenarios.validate(id, body) }
    @PostMapping('/{id}/overrides')
    Map override(@PathVariable('id') String id, @RequestBody Map body) { scenarios.override(id, body) }
    @PostMapping('/{id}/overrides/reset')
    Map reset(@PathVariable('id') String id, @RequestBody Map body) { scenarios.resetOverrides(id, body) }
    @GetMapping('/{id}/groups')
    Map groups(@PathVariable('id') String id, @RequestParam(value='limit', defaultValue='100') int limit,
               @RequestParam(value='cursor', required=false) String cursor) { scenarios.groups(id, limit, cursor) }
    @GetMapping('/{id}/groups/{groupId}/decisions')
    Map decisions(@PathVariable('id') String id, @PathVariable('groupId') String groupId,
                  @RequestParam(value='limit', defaultValue='100') int limit,
                  @RequestParam(value='cursor', required=false) String cursor) { scenarios.decisions(id, groupId, limit, cursor) }
}
