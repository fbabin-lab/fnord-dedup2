package fnord.dedup.web

import fnord.dedup.path.StoredPath
import groovy.json.JsonOutput
import org.springframework.http.HttpStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Definitions contain only explicit, bounded planning inputs, never executable commands. */
final class ScenarioConfig {
    static Map body(Map body, boolean updating = false) {
        keys(body, (['name', 'description', 'config'] + (updating ? ['revision'] : [])) as Set)
        String name = text(body.name, 'name', 120).trim()
        if (!name) throw invalid('Scenario name is required.')
        String description = body.description == null ? '' : text(body.description, 'description', 2000, true)
        Map config = normalize(body.config)
        String json = JsonOutput.toJson(config)
        if (json.getBytes(StandardCharsets.UTF_8).length > 131072) throw invalid('Scenario configuration is too large.')
        [name: name, description: description, configJson: json,
         revision: updating ? integer(body.revision, 'revision', 1, Long.MAX_VALUE) : null]
    }

    static Map normalize(Object value) {
        if (!(value instanceof Map)) throw invalid('Scenario configuration must be an object.')
        Map source = value as Map
        keys(source, ['request', 'rules', 'protections', 'manual', 'maxOccurrences', 'maxSeconds'] as Set)
        if (!(source.request instanceof Map)) throw invalid('An explicit duplicate scope is required.')
        Map request = DuplicateService.normalize(source.request as Map)
        request.remove('cursor')
        List rules = list(source.rules == null ? [[kind: 'SHALLOWEST']] : source.rules, 'rules', 32)
        List normalizedRules = rules.collect { item ->
            if (!(item instanceof Map)) throw invalid('Each retention rule must be an object.')
            Map rule = item as Map
            String kind = text(rule.kind, 'rule kind', 32)
            if (!(kind in ['PREFER_SCAN', 'PREFER_PATH', 'NEWEST', 'OLDEST', 'SHALLOWEST']))
                throw invalid('Unknown retention rule.')
            keys(rule, (kind == 'PREFER_SCAN' ? ['kind', 'scanId'] :
                kind == 'PREFER_PATH' ? ['kind', 'scanId', 'path'] : ['kind']) as Set)
            Map normalized = [kind: kind]
            if (kind == 'PREFER_SCAN' || (kind == 'PREFER_PATH' && rule.scanId != null))
                normalized.scanId = scan(rule.scanId, request)
            if (kind == 'PREFER_PATH') normalized.path = relative(rule.path)
            normalized
        }
        List protections = list(source.protections == null ? [] : source.protections, 'protections', 100).collect { item ->
            if (!(item instanceof Map)) throw invalid('Each protected path must be an object.')
            Map path = item as Map
            keys(path, ['scanId', 'path'] as Set)
            Map normalized = [path: relative(path.path)]
            if (path.scanId != null) normalized.scanId = scan(path.scanId, request)
            normalized
        }
        if (!(source.manual == null || source.manual instanceof Boolean)) throw invalid('manual must be a boolean.')
        [request: request, rules: normalizedRules, protections: protections, manual: source.manual ?: false,
         maxOccurrences: source.maxOccurrences == null ? 1000000L : integer(source.maxOccurrences, 'maxOccurrences', 1, 1000000),
         maxSeconds: source.maxSeconds == null ? 120L : integer(source.maxSeconds, 'maxSeconds', 1, 600)]
    }

    static long integer(Object value, String field, long min, long max) {
        try {
            if (!(value instanceof Number || (value instanceof String && value ==~ /[0-9]+/)))
                throw invalid('Invalid ' + field + '.')
            long parsed = new BigDecimal(value.toString()).longValueExact()
            if (parsed < min || parsed > max) throw invalid('Invalid ' + field + '.')
            parsed
        } catch (ArithmeticException | NumberFormatException ignored) { throw invalid('Invalid ' + field + '.') }
    }

    static void keys(Map value, Set allowed) {
        if (value == null || !allowed.containsAll(value.keySet())) throw invalid('Unknown or missing scenario field.')
    }

    static ApiFailure invalid(String message) {
        new ApiFailure('INVALID_SCENARIO', HttpStatus.BAD_REQUEST, message)
    }

    static String fingerprint(Map value) {
        HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(
            JsonOutput.toJson(normalize(value)).getBytes(StandardCharsets.UTF_8)))
    }

    private static long scan(Object value, Map request) {
        long id = integer(value, 'scan ID', 1, Long.MAX_VALUE)
        if (!request.scanIds.contains(id)) throw invalid('Rules and protections must refer to selected scans.')
        id
    }

    private static String relative(Object value) {
        String path = text(value, 'relative path', 4096, true)
        if (path.endsWith('/')) path = path.substring(0, path.length() - 1)
        try { StoredPath.validateRelative('/recorded-root', path) }
        catch (IllegalArgumentException ignored) { throw invalid('Use a recorded relative path without dot or traversal components.') }
        path
    }

    private static List list(Object value, String field, int max) {
        if (!(value instanceof List) || value.size() > max) throw invalid(field + ' exceeds its bounded list size.')
        value as List
    }

    private static String text(Object value, String field, int max, boolean empty = false) {
        if (!(value instanceof String) || value.length() > max || value.indexOf(0) >= 0 || (!empty && !value))
            throw invalid('Invalid scenario ' + field + '.')
        value as String
    }
}
