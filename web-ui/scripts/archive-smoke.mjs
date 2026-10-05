/** End-to-end archive metadata browsing; all source files are generated and then removed before HTTP reads. */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { chromium } from 'playwright';

export async function checkArchiveFlow({ project, output, httpOnly }) {
  const work = fs.mkdtempSync(path.join(output, 'archive-fixture-'));
  const root = path.join(work, 'source'); fs.mkdirSync(root);
  const db = path.join(work, 'archive-scanner.duckdb');
  const cli = path.join(project, 'build/install/fnord-dedup2/bin/fnord-dedup2');
  const fixture = spawnSync('python3', ['-c', `
import io,pathlib,sys,zipfile
root=pathlib.Path(sys.argv[1])
def archive(items):
    buf=io.BytesIO()
    with zipfile.ZipFile(buf,'w',compression=zipfile.ZIP_DEFLATED) as z:
        for name,data in items:
            info=zipfile.ZipInfo(name,date_time=(2024,1,1,0,0,0))
            z.writestr(info,data)
    return buf.getvalue()
inner=archive([('inside.txt',b'hello'),('empty.bin',b'')])
outer=archive([('docs/shared.txt',b'hello'),('docs/repeated.txt',b'hello'),('docs/nested.zip',inner)]+[(f'many/f{i:03}.txt',b'pagination') for i in range(120)]+[('literal#?.txt',b'<script>never execute</script>')])
(root/'a.zip').write_bytes(outer)
(root/'b.zip').write_bytes(outer)
(root/'plain.txt').write_bytes(b'hello')
`, root], { encoding: 'utf8', timeout: 30_000 });
  assert.equal(fixture.status, 0, fixture.stderr);
  const run = args => {
    const result = spawnSync(cli, ['--db', db, ...args], { encoding: 'utf8', timeout: 120_000 });
    assert.equal(result.status, 0, result.stderr + result.stdout);
    return JSON.parse(result.stdout);
  };
  run(['scan', '--name', 'Archive scan', '--root', root, '--quiet']);
  run(['hash', '--name', 'Archive scan', '--hash-complete', '--quiet']);
  assert.equal(run(['archives', '--name', 'Archive scan', '--archive-temp-min-free', '0', '--quiet']).duplicate_roots, 1);
  fs.rmSync(root, { recursive: true }); // Browsing must work without any accessible source archive.
  const digest = () => crypto.createHash('sha256').update(fs.readFileSync(db)).digest('hex');
  const before = digest();
  const log = fs.openSync(path.join(work, 'server.log'), 'w');
  const server = spawn('java', ['-jar', path.join(project, 'web-api/build/libs/fnord-dedup2-web.jar'),
    '--db', db, '--state-db', path.join(work, 'web-state.duckdb'), '--port', '18769'], { stdio: ['ignore', log, log] });
  const base = 'http://127.0.0.1:18769';
  let browser;
  const request = async (endpoint, body, method = body === undefined ? 'GET' : 'POST') => {
    const response = await fetch(base + endpoint, { method,
      ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }) });
    return { status: response.status, body: response.status === 204 ? null : await response.json() };
  };
  const ok = async (endpoint, body, method) => {
    const result = await request(endpoint, body, method);
    assert(result.status >= 200 && result.status < 300, JSON.stringify(result));
    return result.body;
  };
  try {
    let ready = false;
    for (let i = 0; i < 120; i++) {
      try { if ((await fetch(base + '/api/v1/database/status')).ok) { ready = true; break; } } catch {}
      if (server.exitCode !== null) throw new Error(fs.readFileSync(path.join(work, 'server.log'), 'utf8'));
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    assert(ready, 'archive server readiness');
    const inventory = await ok('/api/v1/scans/1/entries/1/children');
    const a = inventory.items.find(item => item.filename === 'a.zip');
    const b = inventory.items.find(item => item.filename === 'b.zip');
    const plain = inventory.items.find(item => item.filename === 'plain.txt');
    assert(a.archive.browsable && b.archive.browsable);
    assert(plain.duplicateCandidate); assert.equal(plain.filesystemOccurrenceCount, 1); assert.equal(plain.archiveOccurrenceCount, 6);
    const endpoint = '/api/v1/scans/1/entries/' + a.entryId + '/archive';
    const docs = await ok(endpoint + '?path=docs');
    assert.equal(docs.items.length, 3);
    const nested = docs.items.find(item => item.filename === 'nested.zip');
    const inner = await ok(endpoint + '?chain=' + nested.nestedArchive.ordinal);
    const member = inner.items.find(item => item.filename === 'inside.txt');
    assert.equal(member.archiveOccurrenceCount, 6); assert.equal(member.filesystemOccurrenceCount, 1);
    assert.equal(member.directCleanupEligible, false);
    const groupPage = await ok('/api/v1/archives/duplicates', { scanIds: [1] });
    const group = groupPage.items.find(item => item.sha256 === plain.sha256);
    assert.equal(group.archiveOccurrences, 6); assert.equal(group.archiveLogicalBytes, '30');
    assert.equal(group.filesystemOccurrences, 1); assert.equal(group.filesystemObservedBytes, '5');
    assert.equal(groupPage.summary.archive.directCleanupBytes, '0');
    assert.equal(groupPage.summary.filesystem.candidateFiles, '3');
    const many = await ok(endpoint + '?path=many&limit=100');
    assert.equal(many.items.length, 100); assert(many.page.hasMore);
    const tail = await ok(endpoint + '?path=many&limit=100&cursor=' + encodeURIComponent(many.page.nextCursor));
    assert.equal(tail.items.length, 20);
    assert.equal((await request(endpoint + '?path=docs&cursor=' + encodeURIComponent(many.page.nextCursor))).status, 400);
    assert.equal((await request('/api/v1/archives/duplicates', {})).status, 400);
    const memberEndpoint = endpoint + '/members/' + member.ordinal;
    const chain = '?chain=' + nested.nestedArchive.ordinal;
    assert.equal((await request(memberEndpoint + '/signature' + chain, { size: '5' })).status, 400);
    const signature = await ok(memberEndpoint + '/signature' + chain, { tag: 'Archive unwanted', memo: 'Every matching copy' });
    assert((await ok('/api/v1/scans/1/entries/' + plain.entryId)).signatureMatch);
    const matches = await ok(memberEndpoint + '/occurrences' + chain + '&storageKind=ARCHIVE_MEMBER&limit=2');
    assert.equal(matches.items.length, 2); assert(matches.items.every(item => !item.directCleanupEligible && item.signatureMatch));
    assert.equal((await ok('/api/v1/scans/1/entries/' + plain.entryId + '/archive-occurrences')).items.length, 6);
    await ok('/api/v1/signatures/' + signature.id, undefined, 'DELETE');
    assert(!(await ok(memberEndpoint + chain)).signatureMatch);
    const ordinary = await ok('/api/v1/duplicates/groups', { scanIds: [1] });
    assert.equal(ordinary.items.length, 1); assert.equal(ordinary.items[0].occurrences, 2);
    console.log('PASS: archive HTTP virtual folders, nested aliases, split counts/bytes, pagination, signature lifecycle, and filesystem cleanup isolation');
    if (httpOnly) return;
    browser = await chromium.launch({ headless: true,
      ...(process.env.FNORD_SMOKE_BROWSER_EXECUTABLE ? { executablePath: process.env.FNORD_SMOKE_BROWSER_EXECUTABLE } : {}) });
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    page.setDefaultTimeout(20_000);
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.goto(base + '/#/scans/1/explore/1');
    await page.getByRole('button', { name: 'Browse archive', exact: true }).first().click();
    const panel = page.getByRole('region', { name: 'Scanned archive browser' });
    await panel.getByRole('link', { name: '▸ docs', exact: true }).click();
    await panel.getByRole('link', { name: 'Browse nested archive', exact: true }).click();
    await panel.getByRole('link', { name: 'inside.txt', exact: true }).click();
    const detail = page.getByRole('region', { name: 'Archive member details' });
    await detail.getByRole('heading', { name: 'Archive member details' }).waitFor();
    await detail.getByRole('button', { name: 'Archive occurrences', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('[aria-label="Archive member details"] tbody')?.children.length === 6);
    await detail.getByRole('button', { name: 'Add signature', exact: true }).click();
    await page.getByRole('dialog').getByLabel('Tag (optional)').fill('Archive junk');
    await page.getByRole('dialog').getByLabel('Memo (optional)').fill('Keep all matches separate from direct deletion');
    await page.getByRole('button', { name: 'Save signature', exact: true }).click();
    await page.getByRole('dialog').waitFor({ state: 'detached' });
    await detail.locator('.signature-badge').filter({ hasText: 'Archive junk' }).waitFor();
    await page.screenshot({ path: path.join(output, 'archive-member-desktop.png'), fullPage: true });
    await page.reload(); await detail.locator('.signature-badge').filter({ hasText: 'Archive junk' }).waitFor();
    await page.goto(base + '/#/scans/1/archive/' + a.entryId + '?path=many');
    await page.waitForFunction(() => document.querySelector('[aria-label="Scanned archive browser"] tbody')?.children.length === 100);
    await page.getByRole('button', { name: 'Next members', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('[aria-label="Scanned archive browser"] tbody')?.children.length === 20);
    await page.getByRole('button', { name: 'Previous members', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('[aria-label="Scanned archive browser"] tbody')?.children.length === 100);
    await page.getByLabel('Search this archive', { exact: true }).fill('f119');
    await page.getByRole('button', { name: 'Apply archive search', exact: true }).click();
    await panel.getByRole('link', { name: 'many/f119.txt', exact: true }).waitFor();
    await page.goto(base + '/#/scans/1/explore/1');
    await page.getByRole('button', { name: 'plain.txt', exact: false }).click();
    await page.getByRole('button', { name: 'Show archive matches', exact: true }).click();
    await page.getByRole('region', { name: 'Archive matches for this file' }).getByRole('link').first().click();
    await page.getByRole('heading', { name: 'Archive member details' }).waitFor();
    await page.goto(base + '/#/duplicates/1');
    const analysis = page.getByRole('region', { name: 'Archive duplicate analysis' });
    const contentRow = analysis.locator('tbody tr').filter({ hasText: '5 B' }).first();
    await contentRow.getByRole('button', { name: 'Review archive group', exact: true }).click();
    await analysis.getByRole('heading', { name: 'Archive member occurrences', exact: true }).waitFor();
    assert.equal(await analysis.getByRole('button', { name: /REMOVE|Plan group/ }).count(), 0);
    await page.screenshot({ path: path.join(output, 'archive-accounting-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'archive accounting mobile width');
    await page.screenshot({ path: path.join(output, 'archive-accounting-mobile.png'), fullPage: true });
    await page.goto(base + '/#/scans/1/archive/' + a.entryId + '?path=docs');
    await panel.getByRole('link', { name: 'shared.txt', exact: true }).waitFor();
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'archive browser mobile width');
    await page.screenshot({ path: path.join(output, 'archive-browser-mobile.png'), fullPage: true });
    assert.deepEqual(errors, []);
    console.log('PASS: archive browser navigation, nested/reload links, file-to-archive links, signature signals, member paging/search, separate group review, and mobile width');
  } catch (error) {
    const page = browser?.contexts()[0]?.pages()[0];
    if (page) await page.screenshot({ path: path.join(output, 'archive-failure.png'), fullPage: true });
    console.error(fs.readFileSync(path.join(work, 'server.log'), 'utf8'));
    throw error;
  } finally {
    try { if (browser) await browser.close(); }
    finally {
      if (server.exitCode === null) {
        const ended = new Promise(resolve => server.once('exit', resolve));server.kill('SIGTERM');
        const force = setTimeout(() => server.kill('SIGKILL'), 10_000);await ended;clearTimeout(force);
      }
      fs.closeSync(log);assert.equal(digest(), before);
      console.log('PASS: scanner bytes unchanged and source archives absent throughout all web reads');
    }
  }
}
