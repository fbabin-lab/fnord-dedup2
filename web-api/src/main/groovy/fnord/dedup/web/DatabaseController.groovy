package fnord.dedup.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping('/api/v1/database')
class DatabaseController {
    private final ScannerDatabase database

    DatabaseController(ScannerDatabase database) { this.database = database }

    @GetMapping
    Map<String, Object> registration() { database.registration() }

    @GetMapping('/status')
    Map<String, Object> status() { database.status() }
}
