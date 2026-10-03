package fnord.dedup

import fnord.dedup.cli.Main
import fnord.dedup.path.StoredPath
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliTest {
    @TempDir Path work

    Map runCli(List<String> args) {
        StringWriter out = new StringWriter(); StringWriter err = new StringWriter()
        def command = Main.commandLine().setOut(new PrintWriter(out)).setErr(new PrintWriter(err))
        int code = command.execute((['--db', work.resolve('cli.duckdb').toString(), '--memory-limit', '128MB', '--database-threads', '1'] + args) as String[])
        [code: code, out: out.toString(), err: err.toString()]
    }

    @Test void cliProvidesTwoPassCommandsAndJsonLines() {
        Path input = Files.createDirectory(work.resolve('input'))
        String unusual = StoredPath.windowsHost() ? 'first name' : "first\nname"
        Files.writeString(input.resolve(unusual), 'same')
        Files.writeString(input.resolve('second'), 'same')
        Map scan = runCli(['scan', '--name', 'cli', '--root', input.toString(), '--discover-only', '--quiet'])
        assert scan.code == 0 : scan.err
        assert new JsonSlurper().parseText(scan.out).phase == 'READY'
        assert runCli(['duplicates', '--name', 'cli']).code == 1
        Map hash = runCli(['hash', '--name', 'cli', '--quiet'])
        assert hash.code == 0 : hash.err
        Map report = runCli(['duplicates', '--name', 'cli', '--format', 'jsonl'])
        assert report.code == 0 : report.err
        List<Map> lines = report.out.readLines().collect { new JsonSlurper().parseText(it) as Map }
        assert lines.size() == 2
        assert lines*.filename.toSet() == [unusual, 'second'].toSet()
        assert new JsonSlurper().parseText(runCli(['list']).out)*.name == ['cli']
        assert new JsonSlurper().parseText(runCli(['status','--name','cli']).out).files == 2
        assert runCli(['errors','--name','cli']).out.empty
    }

    @Test void errorAndUsageExitCodesArePredictable() {
        assert runCli(['--help']).code == 0
        assert runCli(['scan']).code == 2
        assert runCli(['status', '--name', 'absent']).code == 1
        assert runCli(['scan', '--name', 'x', '--root', work.toString(), '--workers', '0']).code == 1
    }
    @Test void hashModesAreAvailableFromCli() {
        Path input = Files.createDirectory(work.resolve('hash-modes'))
        Files.writeString(input.resolve('pair-a'), 'same')
        Files.writeString(input.resolve('pair-b'), 'same')
        Files.writeString(input.resolve('unique'), 'unique-length')
        assert runCli(['scan','--name','modes','--root',input.toString(),'--discover-only','--quiet']).code == 0
        assert runCli(['hash','--name','modes','--quiet']).code == 0
        Map before = new JsonSlurper().parseText(runCli(['status','--name','modes']).out) as Map
        assert before.hashes_completed == 2
        assert runCli(['hash','--name','modes','--hash-complete','--quiet']).code == 0
        Map complete = new JsonSlurper().parseText(runCli(['status','--name','modes']).out) as Map
        assert complete.hashes_completed == 3
        assert runCli(['hash','--name','modes','--rehash','--quiet']).code == 0
        assert (new JsonSlurper().parseText(runCli(['status','--name','modes']).out) as Map).hashes_completed == 3
    }

}
