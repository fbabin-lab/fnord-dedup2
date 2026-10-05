package fnord.dedup.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping('/api/v1')
class InventoryController {
    private final InventoryService inventory
    private final SignatureService signatures
    private final ArchiveService archives

    InventoryController(InventoryService inventory, SignatureService signatures, ArchiveService archives) {
        this.inventory = inventory
        this.signatures = signatures
        this.archives = archives
    }

    @GetMapping('/dashboard')
    Map dashboard() { inventory.dashboard() }

    @GetMapping('/scans')
    Map scans(@RequestParam(value='limit', required=false) String limit,
              @RequestParam(value='after', required=false) String after,
              @RequestParam(value='name', required=false) String name,
              @RequestParam(value='phase', required=false) String phase,
              @RequestParam(value='hasErrors', required=false) String hasErrors) {
        inventory.scans(limit, after, name, phase, hasErrors)
    }

    @GetMapping('/scans/{scanId}')
    Map scan(@PathVariable('scanId') String scanId) {
        inventory.scan(InventoryService.positiveId(scanId))
    }

    @GetMapping('/scans/{scanId}/summary')
    Map summary(@PathVariable('scanId') String scanId) {
        inventory.scan(InventoryService.positiveId(scanId))
    }

    @GetMapping('/scans/{scanId}/entries/{entryId}')
    Map entry(@PathVariable('scanId') String scanId, @PathVariable('entryId') String entryId) {
        signatures.entrySignals(archives.filesystemEntry(inventory.entry(InventoryService.positiveId(scanId), InventoryService.positiveId(entryId))))
    }

    @GetMapping('/scans/{scanId}/entries/{entryId}/children')
    Map children(@PathVariable('scanId') String scanId, @PathVariable('entryId') String entryId,
                 @RequestParam(value='limit', required=false) String limit,
                 @RequestParam(value='cursor', required=false) String cursor) {
        signatures.pageSignals(archives.filesystemPage(inventory.children(InventoryService.positiveId(scanId), InventoryService.positiveId(entryId),
                           limit, cursor)))
    }

    @GetMapping('/scans/{scanId}/entries/{entryId}/breadcrumbs')
    List<Map> breadcrumbs(@PathVariable('scanId') String scanId, @PathVariable('entryId') String entryId) {
        inventory.breadcrumbs(InventoryService.positiveId(scanId), InventoryService.positiveId(entryId))
    }

    @GetMapping('/scans/{scanId}/errors')
    Map errors(@PathVariable('scanId') String scanId,
               @RequestParam(value='limit', required=false) String limit,
               @RequestParam(value='cursor', required=false) String cursor) {
        inventory.errors(InventoryService.positiveId(scanId), limit, cursor)
    }
}
