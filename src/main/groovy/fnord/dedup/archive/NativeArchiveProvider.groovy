package fnord.dedup.archive

import fnord.dedup.StopToken
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Structured bridge to native libarchive, not a parser for tar/unzip display output. */
class NativeArchiveProvider implements ArchiveProvider {
    private static final int MAX_LINE = 262144

    @Override String identity(Path work, ArchiveOptions options, StopToken stop) {
        Map result = execute(work, null, options, stop, { Map ignored -> }, true)
        if (result.event != 'version' || result.protocol != 1 || !result.provider) {
            throw new IOException('Native archive helper unavailable; install python3 and the distro libarchive runtime')
        }
        'native-reader-v1/' + result.provider
    }

    @Override Map extract(Path work, Map configuration, ArchiveOptions options, StopToken stop, Closure event) {
        execute(work, configuration, options, stop, event, false)
    }

    private Map execute(Path work, Map config, ArchiveOptions options, StopToken stop, Closure event, boolean version) {
        Path script = work.resolve('reader.py')
        if (!Files.exists(script)) {
            getClass().getResourceAsStream('/archive/native_reader.py').withCloseable { input ->
                if (input == null) throw new IOException('Packaged native archive helper is missing')
                Files.copy(input, script)
            }
        }
        List<String> argv = [options.python, '-I', script.toString(), ProcessHandle.current().pid().toString()]
        if (version) argv.add('--version')
        ProcessBuilder builder = new ProcessBuilder(argv).directory(work.toFile())
        builder.environment().put('LC_ALL', 'C.UTF-8')
        builder.environment().put('TZ', 'UTC')
        // Disallow libarchive fallback to external filter programs: only built-in native codecs.
        builder.environment().put('PATH', '/nonexistent')
        Process process = builder.start()
        ArrayBlockingQueue<Map> queue = new ArrayBlockingQueue<>(32)
        AtomicBoolean done = new AtomicBoolean(false)
        AtomicReference<Throwable> readFailure = new AtomicReference<>()
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream()
        Thread output = new Thread({ ->
            try {
                ByteArrayOutputStream line = new ByteArrayOutputStream()
                byte[] buffer = new byte[8192]
                int length
                while ((length = process.inputStream.read(buffer)) >= 0) {
                    for (int i = 0; i < length; i++) {
                        int b = buffer[i] & 255
                        if (b == 10) {
                            if (line.size() > 0) {
                                Map parsed = (Map) new JsonSlurper().parseText(line.toString(StandardCharsets.UTF_8))
                                queue.put(parsed)
                                line.reset()
                            }
                        } else {
                            if (line.size() >= MAX_LINE) throw new IOException('Archive helper exceeded protocol record limit')
                            line.write(b)
                        }
                    }
                }
                if (line.size() != 0) throw new IOException('Truncated archive helper record')
            } catch (Throwable e) { readFailure.set(e) }
            finally { done.set(true) }
        } as Runnable, 'archive-events')
        Thread errors = new Thread({ ->
            try {
                byte[] buffer = new byte[8192]
                int length
                while ((length = process.errorStream.read(buffer)) >= 0) {
                    synchronized (diagnostics) {
                        int keep = Math.min(length, 65536 - diagnostics.size())
                        if (keep > 0) diagnostics.write(buffer, 0, keep)
                    }
                }
            } catch (IOException ignored) { }
        } as Runnable, 'archive-diagnostics')
        output.daemon = true; errors.daemon = true
        output.start(); errors.start()
        Map summary = null
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(version ? 20L : options.timeoutSeconds)
        try {
            process.outputStream.withCloseable { out ->
                if (config != null) out.write(JsonOutput.toJson(config).getBytes(StandardCharsets.UTF_8))
            }
            while (!done.get() || !queue.empty || process.alive) {
                stop.check()
                if (System.nanoTime() > deadline) throw new IOException('Archive extractor time limit reached')
                if (readFailure.get() != null) throw new IOException('Archive helper protocol failure', readFailure.get())
                Map row = queue.poll(100, TimeUnit.MILLISECONDS)
                if (row != null) {
                    if (row.event in ['summary', 'version']) {
                        if (summary != null) throw new IOException('Duplicate archive summary')
                        summary = row
                    } else if (row.event == 'fatal') {
                        throw new IOException('Archive helper failed: ' + row.message)
                    } else {
                        if (summary != null) throw new IOException('Archive event after summary')
                        event.call(row)
                    }
                }
            }
            if (readFailure.get() != null) throw new IOException('Archive helper protocol failure', readFailure.get())
            if (summary == null || summary.protocol != 1 || !(process.exitValue() in [0, 3, 4])) {
                throw new IOException('Archive helper exited without a valid result: ' + process.exitValue())
            }
            summary.exit_code = process.exitValue()
            synchronized (diagnostics) { summary.diagnostics = diagnostics.toString(StandardCharsets.UTF_8) }
            summary
        } finally {
            if (process.alive) {
                process.toHandle().descendants().forEach { ProcessHandle child -> child.destroyForcibly() }
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            }
            output.interrupt(); errors.interrupt()
            try { process.inputStream.close() } catch (IOException ignored) { }
            try { process.errorStream.close() } catch (IOException ignored) { }
            output.join(2000); errors.join(2000)
        }
    }
}
