package fnord.dedup.web

import org.springframework.web.bind.annotation.*

/** Metadata-only routes; location IDs are never interpreted as source paths. */
@RestController
@RequestMapping('/api/v1')
class ArchiveController {
    private final ArchiveService archives
    private final SignatureService signatures
    private final InventoryService inventory
    ArchiveController(ArchiveService archives, SignatureService signatures, InventoryService inventory) {
        this.archives=archives
        this.signatures=signatures
        this.inventory=inventory
    }

    @GetMapping('/archives/summary')
    Map summary() { archives.summary() }

    @GetMapping('/scans/{scanId}/archives/summary')
    Map scanSummary(@PathVariable('scanId') String scanId) {
        archives.summary([ArchiveQueries.id(scanId)])
    }

    @GetMapping('/scans/{scanId}/entries/{entryId}/archive')
    Map browse(@PathVariable('scanId') String scanId,@PathVariable('entryId') String entryId,
               @RequestParam(value='chain',required=false) String chain,
               @RequestParam(value='path',required=false) String path,
               @RequestParam(value='search',required=false) String search,
               @RequestParam(value='limit',required=false) String limit,
               @RequestParam(value='cursor',required=false) String cursor) {
        signatures.pageSignals(archives.browse(ArchiveQueries.id(scanId),ArchiveQueries.id(entryId),chain,path,search,limit,cursor))
    }

    @GetMapping('/scans/{scanId}/entries/{entryId}/archive/members/{ordinal}')
    Map member(@PathVariable('scanId') String scanId,@PathVariable('entryId') String entryId,
               @PathVariable('ordinal') String ordinal,
               @RequestParam(value='chain',required=false) String chain) {
        signatures.entrySignals(archives.member(ArchiveQueries.id(scanId),ArchiveQueries.id(entryId),chain,ArchiveQueries.id(ordinal)))
    }

    @GetMapping('/scans/{scanId}/entries/{entryId}/archive/members/{ordinal}/occurrences')
    Map memberOccurrences(@PathVariable('scanId') String scanId,@PathVariable('entryId') String entryId,
               @PathVariable('ordinal') String ordinal,
               @RequestParam(value='chain',required=false) String chain,
               @RequestParam('storageKind') String storageKind,
               @RequestParam(value='limit',required=false) String limit,
               @RequestParam(value='cursor',required=false) String cursor) {
        signatures.pageSignals(archives.memberOccurrences(ArchiveQueries.id(scanId),ArchiveQueries.id(entryId),
            chain,ArchiveQueries.id(ordinal),storageKind,limit,cursor))
    }

    @GetMapping('/scans/{scanId}/entries/{entryId}/archive-occurrences')
    Map fileArchiveOccurrences(@PathVariable('scanId') String scanId,@PathVariable('entryId') String entryId,
               @RequestParam(value='limit',required=false) String limit,
               @RequestParam(value='cursor',required=false) String cursor) {
        Map reference=inventory.entry(ArchiveQueries.id(scanId),ArchiveQueries.id(entryId))
        if (reference.kind!='FILE' || reference.algorithm!='SHA-256' || !reference.sha256)
            throw new ApiFailure('HASH_UNAVAILABLE',org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                'A complete saved SHA-256 file hash is required to find archive occurrences.')
        signatures.pageSignals(archives.occurrences([size:reference.size,sha256:reference.sha256,
            storageKind:'ARCHIVE_MEMBER',limit:limit,cursor:cursor],true))
    }

    @PostMapping('/archives/duplicates')
    Map groups(@RequestBody Map body) { signatures.pageSignals(archives.groups(body),true) }

    @PostMapping('/archives/occurrences')
    Map occurrences(@RequestBody Map body) { signatures.pageSignals(archives.occurrences(body)) }

    @PostMapping('/scans/{scanId}/entries/{entryId}/archive/members/{ordinal}/signature')
    Map signature(@PathVariable('scanId') String scanId,@PathVariable('entryId') String entryId,
                  @PathVariable('ordinal') String ordinal,
                  @RequestParam(value='chain',required=false) String chain,
                  @RequestBody Map body) {
        if (body==null || !(['tag','memo'] as Set).containsAll(body.keySet()))
            throw ArchiveQueries.invalid('Archive signatures accept only tag and memo; content is derived from the recorded member.')
        archives.withMember(ArchiveQueries.id(scanId),ArchiveQueries.id(entryId),chain,ArchiveQueries.id(ordinal)) { Map member ->
            signatures.createFromEvidence(member,body)
        } as Map
    }
}
