package fnord.dedup.web

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

@SpringBootApplication
class WebApplication {
    private static final log = LoggerFactory.getLogger(WebApplication)

    static void main(String[] args) {
        List<String> translated = []
        for (int i = 0; i < args.length; i++) {
            String argument = args[i]
            if (argument == '--db' || argument == '--port' || argument == '--state-db') {
                if (++i >= args.length) throw new IllegalArgumentException("Missing value for ${argument}")
                String property = argument == '--db' ? 'dedup.web.database' :
                    argument == '--state-db' ? 'dedup.web.state-database' : 'server.port'
                translated.add("--${property}=${args[i]}".toString())
            } else if (argument.startsWith('--db=')) {
                translated.add('--dedup.web.database=' + argument.substring(5))
            } else if (argument.startsWith('--state-db=')) {
                translated.add('--dedup.web.state-database=' + argument.substring(11))
            } else if (argument.startsWith('--port=')) {
                translated.add('--server.port=' + argument.substring(7))
            } else {
                translated.add(argument)
            }
        }
        SpringApplication.run(WebApplication, translated as String[])
    }

    @Bean
    ApplicationRunner startupNotice(Environment environment, ScannerDatabase database) {
        return { ignored ->
            String address = environment.getProperty('server.address', '127.0.0.1')
            log.info('Scanner database: {}', database.configuredPath ?: '(not configured)')
            if (!(address in ['127.0.0.1', '::1', 'localhost'])) {
                log.warn('Web UI is listening on {} without built-in authentication. Restrict access with a trusted reverse proxy.', address)
            }
        } as ApplicationRunner
    }
}
