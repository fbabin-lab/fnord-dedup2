/** Packaged HTTP/browser regression. Only generated fixtures are read or changed. */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';
import { chromium } from 'playwright';

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const output = path.join(project, 'build/web-ui-smoke');
fs.mkdirSync(output, { recursive: true });
const work = fs.mkdtempSync(path.join(output, 'fixtures-'));
const db = path.join(work, 'inventory.duckdb');
const cli = path.join(project, 'build/install/fnord-dedup2/bin/fnord-dedup2');
const httpOnly = process.argv.includes('--http-only');

async function main() {
  for (let scan = 1; scan <= 3; scan++) {
    const root = path.join(work, 'source-' + scan);
    fs.mkdirSync(root);
    const folder = path.join(root, 'A'); fs.mkdirSync(folder);
    const count = scan === 1 ? 112 : 2;
    for (let i = 0; i < count; i++) {
      const name = scan === 1 && i === 0 ? 'shared-one.txt' : scan === 1 && i === 1 ? 'shared-λ\nline.txt' : `shared-${String(i).padStart(3, '0')}.txt`;
      fs.writeFileSync(path.join(folder, name), 'shared content');
    }
    if (scan === 1) {
      for (let group = 0; group < 51; group++) {
        for (let copy = 0; copy < 2; copy++) fs.writeFileSync(path.join(root, `local-${group}-${copy}.bin`), 'local group ' + group);
      }
      fs.writeFileSync(path.join(root, 'unresolved.bin'), 'an intentionally unique-size unresolved payload whose hash is absent');
    }
    const result = spawnSync(cli, ['--db', db, 'scan', '--name', `scan-${scan}`, '--root', root, '--quiet'], { encoding: 'utf8', timeout: 60_000 });
    assert.equal(result.status, 0, result.stderr);
  }
  const digest = () => crypto.createHash('sha256').update(fs.readFileSync(db)).digest('hex');
  const before = digest();
  const log = fs.openSync(path.join(work, 'server.log'), 'w');
  const server = spawn('java', ['-jar', path.join(project, 'web-api/build/libs/fnord-dedup2-web.jar'),
    '--db', db, '--state-db', path.join(work, 'state.duckdb'), '--port', '18766'], { stdio: ['ignore', log, log] });
  let browser;
  const base = 'http://127.0.0.1:18766';
  async function post(endpoint, body) {
    const response = await fetch(base + endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    return { status: response.status, body: await response.json() };
  }
  try {
    let ready = false;
    for (let i = 0; i < 120; i++) {
      try { if ((await fetch(base + '/api/v1/database/status')).ok) { ready = true; break; } } catch {}
      if (server.exitCode !== null) throw new Error(fs.readFileSync(path.join(work, 'server.log'), 'utf8'));
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    assert(ready, 'server readiness');
    assert((await fetch(base + '/')).ok);
    const request = { scanIds: [1, 2], mode: 'ACROSS_SCANS', name: { value: 'shared-one.txt', operator: 'EXACT' }, limit: 100 };
    const groups = await post('/api/v1/duplicates/groups', request);
    assert.equal(groups.status, 200);
    assert.equal(groups.body.items.length, 1);
    assert.equal(groups.body.items[0].occurrences, 114);
    assert.equal(groups.body.items[0].matchingOccurrences, 1);
    assert.equal(groups.body.coverage.unhashedFiles, 1);
    const endpoint = '/api/v1/duplicates/groups/' + encodeURIComponent(groups.body.items[0].groupId) + '/occurrences';
    const first = await post(endpoint, request);
    assert.equal(first.status, 200); assert.equal(first.body.items.length, 100); assert(first.body.page.hasMore);
    const second = await post(endpoint, { ...request, cursor: first.body.page.nextCursor });
    assert.equal(second.body.items.length, 14);
    assert.equal(new Set([...first.body.items, ...second.body.items].map(x => x.scanId + ':' + x.entryId)).size, 114);
    assert.equal((await post(endpoint, { ...request, scanIds: [1, 2, 3], cursor: first.body.page.nextCursor })).status, 400);
    assert.equal((await post('/api/v1/duplicates/groups', {})).status, 400);
    assert.equal((await post('/api/v1/duplicates/groups', [])).status, 400);
    assert.equal((await post('/api/v1/duplicates/groups', { scanIds: [1], name: { operator: 'REGEX', value: '[' } })).status, 400);
    console.log('PASS: packaged HTTP groups, occurrence pagination, scope/filter binding, malformed JSON, and static UI');
    if (httpOnly) return;

    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    page.setDefaultTimeout(20_000);
    const runtimeErrors = [];
    page.on('pageerror', error => runtimeErrors.push(error.message));
    await page.goto(base + '/#/duplicates');
    await page.getByRole('heading', { name: 'Duplicate Explorer', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Apply duplicate filters', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: 'Select at least one scan.' }).waitFor();
    await page.getByLabel('Selected scans', { exact: true }).selectOption(['1', '2']);
    // Nested select options are part of a wrapping label's text for getByLabel.
    await page.getByLabel(/^Page size/).selectOption({ label: '50' });
    await page.getByRole('button', { name: 'Apply duplicate filters', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('.results-panel tbody tr').length === 50);
    const groupsPanel = page.locator('.results-panel');
    await groupsPanel.getByRole('button', { name: 'Next', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('.results-panel tbody tr').length === 2);
    await groupsPanel.getByRole('button', { name: 'Previous', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('.results-panel tbody tr').length === 50);
    await page.getByLabel(/^Page size/).selectOption({ label: '100' });
    await page.getByLabel(/^Group scope/).selectOption('ACROSS_SCANS');
    await page.locator('summary').filter({ hasText: 'Names and paths' }).click();
    await page.getByLabel('Filename value', { exact: true }).fill('shared-one.txt');
    await page.getByLabel(/^Name match/).selectOption('EXACT');
    const filteredResponse = page.waitForResponse(response => response.url().endsWith('/api/v1/duplicates/groups') && response.request().method() === 'POST');
    await page.getByRole('button', { name: 'Apply duplicate filters', exact: true }).click();
    assert.equal((await filteredResponse).status(), 200);
    await page.waitForFunction(() => document.querySelectorAll('.results-panel tbody tr').length === 1);
    await page.getByRole('button', { name: 'View occurrences', exact: true }).waitFor();
    const row = page.locator('.results-panel tbody tr').first();
    assert.equal(await row.locator('td').nth(2).textContent(), '114');
    assert.equal(await row.locator('td').nth(4).textContent(), '1');
    await page.screenshot({ path: path.join(output, 'duplicates-desktop.png'), fullPage: true });
    await page.getByRole('button', { name: 'View occurrences', exact: true }).click();
    const occurrencePanel = page.locator('#duplicate-occurrences');
    await occurrencePanel.locator('tbody tr').first().waitFor();
    assert.equal(await occurrencePanel.locator('tbody tr').count(), 100);
    await occurrencePanel.getByRole('button', { name: 'Next', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('#duplicate-occurrences tbody tr').length === 14);
    await occurrencePanel.getByRole('button', { name: 'Details', exact: true }).last().click();
    await page.getByRole('button', { name: 'Find duplicates', exact: true }).click();
    await page.waitForURL(/#\/duplicates\/2\/[0-9]+$/);
    await page.getByRole('button', { name: 'Show all groups', exact: true }).waitFor();
    await page.waitForFunction(() => document.querySelector('.results-panel tbody tr td:nth-child(3)')?.textContent === '2');
    assert.equal(await page.locator('.results-panel tbody tr td').nth(2).textContent(), '2');
    const clearedResponse = page.waitForResponse(response => response.url().endsWith('/api/v1/duplicates/groups') && response.request().method() === 'POST');
    await page.getByRole('button', { name: 'Show all groups', exact: true }).click();
    assert.equal((await clearedResponse).status(), 200);
    await page.waitForURL(/#\/duplicates\/2$/);
    await page.getByRole('button', { name: 'View occurrences', exact: true }).waitFor();
    await page.getByRole('button', { name: 'View occurrences', exact: true }).click();
    await occurrencePanel.getByRole('button', { name: 'Containing directory', exact: true }).first().click();
    await page.waitForURL(/#\/scans\/2\/explore\/[0-9]+$/);
    await page.getByRole('button', { name: 'shared-000.txt', exact: false }).click();
    await page.getByRole('button', { name: 'Find duplicates', exact: true }).click();
    await page.waitForURL(/#\/duplicates\/2\/[0-9]+$/);
    await page.getByRole('button', { name: 'View occurrences', exact: true }).waitFor();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(output, 'duplicates-mobile.png'), fullPage: true });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'mobile page width');
    assert.deepEqual(runtimeErrors, []);
    console.log('PASS: browser scope selection, filters, group/occurrence paging, cross-scan file links, route changes, and mobile layout');
  } catch (error) {
    const page = browser?.contexts()[0]?.pages()[0];
    if (page) {
      console.error('Page labels at failure:', await page.locator('label').allTextContents());
      await page.screenshot({ path: path.join(output, 'duplicates-failure.png'), fullPage: true });
    }
    throw error;
  } finally {
    try { if (browser) await browser.close(); }
    finally {
      if (server.exitCode === null) {
        const ended = new Promise(resolve => server.once('exit', resolve));
        server.kill('SIGTERM');
        const force = setTimeout(() => server.kill('SIGKILL'), 10_000);
        await ended; clearTimeout(force);
      }
      fs.closeSync(log);
      assert.equal(digest(), before);
      console.log('PASS: scanner database bytes unchanged');
    }
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
