package fnord.dedup.image

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

/** Structured bridge to native QEMU/libguestfs, not a parser for human-oriented display output. */
class NativeImageProvider implements DiskImageProvider {
    private static final int MAX_LINE = 262144

    @Override String identity(Path work, ImageOptions options, StopToken stop) {
        Map result = execute(work, [mode:'version'], options, stop, { Map ignored -> }, true)
        if (result.event != 'version' || result.protocol != 1 || !result.provider) {
            throw new IOException('Native image helper unavailable; install python3 and the qemu-utils and python3-guestfs runtime')
        }
        'native-reader-v1/' + result.provider
    }

    @Override Map inspect(Path work, Map configuration, ImageOptions options, StopToken stop, Closure event) {
        execute(work, configuration + [mode:'inspect'], options, stop, event, false)
    }

    @Override Map probe(Path work, Map config, ImageOptions options, StopToken stop) {
        execute(work, config + [mode:'probe'], options, stop, { Map ignored -> }, false)
    }

    private Map execute(Path work, Map config, ImageOptions options, StopToken stop, Closure event, boolean version) {
        Path script = work.resolve('reader.py')
        if (!Files.exists(script)) {
            getClass().getResourceAsStream('/image/native_image.py').withCloseable { input ->
                if (input == null) throw new IOException('Packaged native image helper is missing')
                Files.copy(input, script)
            }
        }
        Path guard=work.resolve('safe_exec.py')
        if (!Files.exists(guard)) getClass().getResourceAsStream('/image/safe_exec.py').withCloseable { Files.copy(it,guard) }
        List<String> argv = [options.python, '-I', script.toString(), ProcessHandle.current().pid().toString()]

        ProcessBuilder builder = new ProcessBuilder(argv).directory(work.toFile())
        builder.environment().put('LC_ALL', 'C.UTF-8')
        builder.environment().put('TZ', 'UTC')
        // Do not inherit guestfs/QEMU configuration or environment-injected plugins.
        builder.environment().clear()
        builder.environment().putAll([PATH:'/usr/bin:/bin', LC_ALL:'C.UTF-8', TZ:'UTC', HOME:work.toString(), LIBGUESTFS_BACKEND:'direct'])
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
                            if (line.size() >= MAX_LINE) throw new IOException('Image helper exceeded protocol record limit')
                            line.write(b)
                        }
                    }
                }
                if (line.size() != 0) throw new IOException('Truncated image helper record')
            } catch (Throwable e) { readFailure.set(e) }
            finally { done.set(true) }
        } as Runnable, 'image-events')
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
        } as Runnable, 'image-diagnostics')
        output.daemon = true; errors.daemon = true
        output.start(); errors.start()
        Map summary = null
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(version ? 30L : options.timeoutSeconds)
        try {
            process.outputStream.withCloseable { out ->
                if (config != null) out.write(JsonOutput.toJson(config).getBytes(StandardCharsets.UTF_8))
            }
            while (!done.get() || !queue.empty || process.alive) {
                stop.check()
                if (System.nanoTime() > deadline) throw new IOException('Image extractor time limit reached')
                if (readFailure.get() != null) throw new IOException('Image helper protocol failure', readFailure.get())
                Map row = queue.poll(100, TimeUnit.MILLISECONDS)
                if (row != null) {
                    if (row.event in ['summary', 'version']) {
                        if (summary != null) throw new IOException('Duplicate image summary')
                        summary = row
                    } else if (row.event == 'fatal') {
                        throw new ImageFailure((row.code ?: 'NATIVE_PROCESS_ERROR') as String, row.message as String, (row.category ?: 'OPERATIONAL') as String)
                    } else {
                        if (summary != null) throw new IOException('Image event after summary')
                        event.call(row)
                    }
                }
            }
            if (readFailure.get() != null) throw new IOException('Image helper protocol failure', readFailure.get())
            if (summary == null || summary.protocol != 1 || !(process.exitValue() in [0, 3, 4])) {
                throw new IOException('Image helper exited without a valid result: ' + process.exitValue())
            }
            summary.exit_code = process.exitValue()
            synchronized (diagnostics) { summary.diagnostics = diagnostics.toString(StandardCharsets.UTF_8) }
            summary
        } finally {
            if (process.alive) {
                process.destroy()
                if (!process.waitFor(8, TimeUnit.SECONDS)) {
                    process.toHandle().descendants().forEach { ProcessHandle child -> child.destroyForcibly() }
                    process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS)
                }
            }
            output.interrupt(); errors.interrupt()
            try { process.inputStream.close() } catch (IOException ignored) { }
            try { process.errorStream.close() } catch (IOException ignored) { }
            output.join(2000); errors.join(2000)
        }
    }
}
