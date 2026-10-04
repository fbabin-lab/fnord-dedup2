/** Scenario checks reuse the Duplicate Explorer's generated, disposable inventory. */
import assert from 'node:assert/strict';
import path from 'node:path';
import fs from 'node:fs';
import { createHash } from 'node:crypto';

function csvRows(text) {
  const rows = []; let row = [], cell = '', quoted = false;
  for (let i = 0; i < text.length; i++) {
    const value = text[i];
    if (value === '"') {
      if (quoted && text[i + 1] === '"') { cell += '"'; i++; } else quoted = !quoted;
    } else if (!quoted && value === ',') { row.push(cell); cell = ''; }
    else if (!quoted && value === '\n') { row.push(cell); rows.push(row); row = []; cell = ''; }
    else if (!quoted && value === '\r') { /* CRLF separator */ }
    else cell += value;
  }
  assert(!quoted && row.length === 0 && cell === '');
  return rows;
}

function manifest(text, format) {
  if (format === 'JSON') return JSON.parse(text);
  if (format === 'JSONL') {
    const lines = text.trimEnd().split('\n'); const records = lines.map(line => JSON.parse(line));
    const completion = records.at(-1);
    assert.equal(completion.recordsSha256, createHash('sha256').update(lines.slice(1, -1).join('\n') + '\n').digest('hex'));
    return { metadata: records[0], items: records.slice(1, -1), completion };
  }
  const cells = csvRows(text), rows = cells.slice(1).map(row => Object.fromEntries(cells[0].map((key, i) => [key, row[i]])));
  assert.equal(rows[0].record_type, 'manifest'); assert.equal(rows.at(-1).record_type, 'completion');
  return { metadata: JSON.parse(rows[0].manifest_json), completion: JSON.parse(rows.at(-1).manifest_json),
    items: rows.slice(1, -1).map(row => ({ decision: row.decision, requires_revalidation: row.requires_revalidation === 'true',
      reference: { path: JSON.parse(row.path_json) }, keepReference: row.keep_reference_json ? JSON.parse(row.keep_reference_json) : null })) };
}

async function checkExport(base, target, revision, format, expectedRecords, expectedRemovals) {
  const response = await fetch(base + target + '/export', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ revision, format }) });
  assert.equal(response.status, 200);
  const bytes = Buffer.from(await response.arrayBuffer());
  assert.equal(Number(response.headers.get('content-length')), bytes.length);
  assert.equal(response.headers.get('x-manifest-sha256'), createHash('sha256').update(bytes).digest('hex'));
  assert.equal(response.headers.get('x-scenario-revision'), String(revision));
  assert.match(response.headers.get('content-disposition'), new RegExp(`attachment; filename="scenario-.*-r${revision}\\.${format.toLowerCase()}"`));
  assert.equal(response.headers.get('cache-control'), 'no-store'); assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
  assert(response.headers.get('content-type').startsWith({ JSON: 'application/json', JSONL: 'application/x-ndjson', CSV: 'text/csv' }[format]));
  const result = manifest(bytes.toString('utf8'), format);
  assert(result.completion.complete && result.metadata.planningOnly && result.metadata.requires_revalidation);
  assert.equal(result.metadata.exportId, response.headers.get('x-export-id'));
  assert.equal(result.completion.recordCount, String(expectedRecords));
  assert.equal(result.completion.removalCandidates, String(expectedRemovals));
  assert.equal(result.items.length, expectedRecords);
  assert(result.items.every(item => item.requires_revalidation));
  assert(result.items.filter(item => item.decision === 'REMOVE').every(item => item.keepReference?.reference.path !== item.reference.path));
  assert.equal((await api(base, target)).body.status, 'EXPORTED');
  return result;
}

async function api(base, endpoint, method = 'GET', body) {
  const response = await fetch(base + endpoint, { method,
    ...(body == null ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }) });
  return { status: response.status, body: response.status === 204 ? null : await response.json() };
}

export async function checkScenarioHttp(base) {
  const endpoint = '/api/v1/scenarios';
  const request = { scanIds: [1, 2], mode: 'ACROSS_SCANS', name: { value: 'shared-one.txt', operator: 'EXACT' } };
  const created = await api(base, endpoint, 'POST', { name: 'HTTP scoped plan', config: { request } });
  assert.equal(created.status, 201); assert.equal(created.body.status, 'DRAFT');
  const target = endpoint + '/' + created.body.id;
  assert.equal((await api(base, target + '/export', 'POST', { revision: 1, format: 'JSON' })).body.code, 'SCENARIO_NOT_GENERATED');
  assert((await api(base, endpoint)).body.items.some(x => x.id === created.body.id));
  const generated = await api(base, target + '/generate', 'POST', { revision: 1 });
  assert.equal(generated.status, 200); assert.equal(generated.body.status, 'READY');
  assert.equal(generated.body.snapshot.summary.observations, '114');
  assert.equal(generated.body.snapshot.summary.remove, '1');
  assert.equal(generated.body.snapshot.summary.candidateBytes, '14');
  assert(generated.body.snapshot.planningOnly && generated.body.snapshot.liveRevalidationRequired);
  const exported = [];
  for (const format of ['JSON', 'JSONL', 'CSV']) exported.push(await checkExport(base, target, 1, format, 114, 1));
  assert.equal(new Set(exported.map(result => result.completion.recordsSha256)).size, 1);
  const cap = await fetch(base + target + '/export', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ revision: 1, format: 'JSON', maxBytes: 1 }) });
  assert.equal(cap.status, 409); assert.equal(cap.headers.get('content-disposition'), null);
  assert.equal((await cap.json()).code, 'SCENARIO_EXPORT_LIMIT_EXCEEDED');
  assert.equal((await api(base, target + '/export', 'POST', { revision: 1, format: 'PATHS' })).status, 400);
  assert.equal((await api(base, target + '/export', 'POST', { format: 'JSON' })).status, 400);
  const group = (await api(base, target + '/groups')).body.items[0];
  const decisions = target + '/groups/' + encodeURIComponent(group.groupId) + '/decisions';
  const first = await api(base, decisions + '?limit=100');
  const second = await api(base, decisions + '?limit=100&cursor=' + encodeURIComponent(first.body.page.nextCursor));
  assert.equal(first.body.items.length, 100); assert.equal(second.body.items.length, 14);
  const all = [...first.body.items, ...second.body.items];
  assert.equal(new Set(all.map(x => x.scanId + ':' + x.entryId)).size, 114);
  const inside = all.find(x => x.matchesFilters), outside = all.find(x => !x.matchesFilters);
  assert.equal(inside.decision, 'REMOVE'); assert(all.filter(x => !x.matchesFilters).every(x => x.decision === 'KEEP'));
  assert.equal((await api(base, target + '/overrides', 'POST', { revision: 1,
    decisions: [{ scanId: outside.scanId, entryId: outside.entryId, decision: 'REMOVE' }] })).status, 400);
  const edited = await api(base, target + '/overrides', 'POST', { revision: 1,
    decisions: [{ scanId: inside.scanId, entryId: inside.entryId, decision: 'KEEP' }] });
  assert.equal(edited.body.revision, 2); assert(edited.body.stale && !edited.body.definitionStale);
  assert.equal((await api(base, decisions + '?limit=100&cursor=' + encodeURIComponent(first.body.page.nextCursor))).status, 400);
  assert.equal((await api(base, target + '/generate', 'POST', { revision: 1 })).status, 409);
  assert.equal((await api(base, target + '/validate', 'POST', { revision: 2 })).status, 409);
  assert.equal((await api(base, target + '/export', 'POST', { revision: 2, format: 'CSV' })).body.code, 'STALE_SCENARIO');
  const regenerated = await api(base, target + '/generate', 'POST', { revision: 2 });
  assert.equal(regenerated.body.status, 'READY'); assert.equal(regenerated.body.snapshot.summary.remove, '0');
  assert.equal((await api(base, target + '/validate', 'POST', { revision: 2 })).body.status, 'READY');

  const protectedPlan = await api(base, endpoint, 'POST', { name: 'HTTP protected plan',
    config: { request, protections: [{ scanId: 1, path: 'A/shared-one.txt' }] } });
  const protectedTarget = endpoint + '/' + protectedPlan.body.id;
  const protectedGenerated = await api(base, protectedTarget + '/generate', 'POST', { revision: 1 });
  assert.equal(protectedGenerated.body.snapshot.summary.remove, '0');

  const scoped = await api(base, endpoint, 'POST', { name: 'HTTP last keeper',
    config: { request: { scanIds: [2], entry: { scanId: 2, entryId: all.find(x => x.scanId === 2).entryId } } } });
  const scopedTarget = endpoint + '/' + scoped.body.id;
  await api(base, scopedTarget + '/generate', 'POST', { revision: 1 });
  const scopedGroup = (await api(base, scopedTarget + '/groups')).body.items[0];
  const scopedRows = (await api(base, scopedTarget + '/groups/' + encodeURIComponent(scopedGroup.groupId) + '/decisions')).body.items;
  assert.equal(scopedRows.length, 2);
  await api(base, scopedTarget + '/overrides', 'POST', { revision: 1,
    decisions: scopedRows.map(x => ({ scanId: x.scanId, entryId: x.entryId, decision: 'REMOVE' })) });
  const unsafe = await api(base, scopedTarget + '/generate', 'POST', { revision: 2 });
  assert.equal(unsafe.body.status, 'DRAFT');
  assert.equal(unsafe.body.snapshot.validation.errors[0].code, 'NO_RETAINED_CANDIDATE');
  assert.equal((await api(base, scopedTarget + '/export', 'POST', { revision: 2, format: 'JSONL' })).body.code, 'SCENARIO_NOT_READY');

  const capped = await api(base, endpoint, 'POST', { name: 'HTTP capped plan', config: { request, maxOccurrences: 2 } });
  const cappedTarget = endpoint + '/' + capped.body.id;
  assert.equal((await api(base, cappedTarget + '/generate', 'POST', { revision: 1 })).body.code, 'SCENARIO_LIMIT_EXCEEDED');
  assert.equal((await api(base, cappedTarget)).body.generationId, null);
  assert.equal((await api(base, endpoint, 'POST', { name: 'No scope', config: { request: {} } })).status, 400);
  assert.equal((await api(base, target, 'DELETE')).status, 400);
  assert.equal((await api(base, target + '?revision=invalid', 'DELETE')).status, 400);
  assert.equal((await api(base, endpoint + '?limit=invalid')).status, 400);
  assert.equal((await api(base, cappedTarget + '?revision=1', 'DELETE')).status, 204);
  assert.equal((await api(base, '/api/v1/saved-searches', 'POST', { name: 'HTTP shared search',
    request: { name: { value: 'shared', operator: 'CONTAINS' } } })).status, 201);
  console.log('PASS: scenario HTTP CRUD, scope, protections, revisions, pages, last keeper validation, JSON/JSONL/CSV exports, checksums, status, and atomic limits');
}

export async function checkScenarioBrowser(page, base, output) {
  let generationRequests = 0;
  page.on('request', request => { if (request.method() === 'POST' && /\/api\/v1\/scenarios\/[^/]+\/generate$/.test(request.url())) generationRequests++; });
  await page.setViewportSize({ width: 1440, height: 1100 });
  // The preceding Duplicate Explorer smoke finishes at a reference file in scan 2.
  await page.getByRole('button', { name: 'Create scenario from filters', exact: true }).click();
  await page.getByRole('heading', { name: 'Scenario Builder', exact: true }).waitFor();
  await page.getByLabel('Scenario name', { exact: true }).fill('Browser scenario');
  await page.getByLabel('Selected scans', { exact: true }).locator('option[value="2"]').waitFor();
  assert.deepEqual(await page.getByLabel('Selected scans', { exact: true }).locator('option:checked').evaluateAll(options => options.map(x => x.value)), ['2']);

  async function click(button, endpoint, status = 200, method = 'POST') {
    const response = page.waitForResponse(r => r.url().endsWith(endpoint) && r.request().method() === method);
    await button.click();
    const value = await response;
    assert.equal(value.status(), status);
    return await value.json();
  }
  async function ready(status, revision) {
    await page.waitForFunction(({ status, revision }) => document.querySelector('.definition-panel .badge')?.textContent?.trim() === `${status} · ${revision}`,
      { status, revision });
  }
  const created = await click(page.getByRole('button', { name: 'Save scenario', exact: true }), '/api/v1/scenarios', 201);
  await page.waitForURL(new RegExp('#/scenarios/' + created.id + '$'));
  await ready('DRAFT', 1);
  assert(await page.getByRole('button', { name: 'Export manifest', exact: true }).count() === 0);
  const target = '/api/v1/scenarios/' + created.id;
  assert.equal(generationRequests, 0, 'saving a definition does not generate decisions');
  const first = await click(page.getByRole('button', { name: 'Generate decisions', exact: true }), target + '/generate');
  assert.equal(first.snapshot.summary.remove, '1');
  await ready('READY', 1);
  await page.getByRole('button', { name: 'Review decisions', exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll('#scenario-decisions tbody tr').length === 2);
  const panel = page.locator('#scenario-decisions');
  const oldKeeper = panel.locator('tbody tr').filter({ hasText: 'A/shared-000.txt' });
  const newKeeper = panel.locator('tbody tr').filter({ hasText: 'A/shared-001.txt' });
  async function choose(row, value, revision) {
    const saved = page.waitForResponse(r => r.url().endsWith(target + '/overrides') && r.request().method() === 'POST');
    const reloaded = page.waitForResponse(r => r.url().includes(target + '/groups/') && r.url().includes('/decisions') && r.request().method() === 'GET');
    await row.getByRole('combobox').selectOption(value);
    const response = await saved; assert.equal(response.status(), 200); assert.equal((await response.json()).revision, revision);
    assert.equal((await reloaded).status(), 200);
    await ready('DRAFT', revision);
    await page.waitForFunction(() => document.querySelectorAll('#scenario-decisions tbody tr').length === 2);
  }
  await choose(newKeeper, 'KEEP', 2);
  await choose(oldKeeper, 'REMOVE', 3);
  const swapped = await click(page.getByRole('button', { name: 'Generate decisions', exact: true }), target + '/generate');
  assert.equal(swapped.status, 'READY'); assert.equal(swapped.snapshot.summary.remove, '1');
  await ready('READY', 3);
  await page.getByRole('button', { name: 'Review decisions', exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll('#scenario-decisions tbody tr').length === 2);
  assert.equal(await newKeeper.locator('td').nth(2).locator('strong').textContent(), 'KEEP');

  await click(page.getByRole('button', { name: 'Reset manual choices', exact: true }), target + '/overrides/reset');
  await ready('DRAFT', 4);
  await click(page.getByRole('button', { name: 'Generate decisions', exact: true }), target + '/generate');
  await ready('READY', 4);
  await page.getByRole('button', { name: 'Review decisions', exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll('#scenario-decisions tbody tr').length === 2);
  await choose(oldKeeper, 'REMOVE', 5);
  await choose(newKeeper, 'REMOVE', 6);
  const unsafe = await click(page.getByRole('button', { name: 'Generate decisions', exact: true }), target + '/generate');
  assert.equal(unsafe.status, 'DRAFT'); assert.equal(unsafe.snapshot.validation.errors[0].code, 'NO_RETAINED_CANDIDATE');
  assert(await page.getByRole('button', { name: 'Export manifest', exact: true }).isDisabled());
  await page.getByRole('alert').filter({ hasText: 'Every group with removal candidates must retain' }).waitFor();
  await click(page.getByRole('button', { name: 'Reset manual choices', exact: true }), target + '/overrides/reset');
  await ready('DRAFT', 7);
  await click(page.getByRole('button', { name: 'Generate decisions', exact: true }), target + '/generate');
  await ready('READY', 7);

  await page.getByLabel(/^Retention rule/).selectOption('PREFER_PATH');
  await page.getByLabel('Preferred relative path', { exact: true }).fill('A/shared-001.txt');
  await click(page.getByRole('button', { name: 'Save changes', exact: true }), target, 200, 'PUT');
  await ready('DRAFT', 8);
  await click(page.getByRole('button', { name: 'Generate decisions', exact: true }), target + '/generate');
  await ready('READY', 8);
  const count = generationRequests;
  await page.reload(); await ready('READY', 8);
  assert.equal(generationRequests, count, 'loading restores the snapshot without automatically generating');
  await click(page.getByRole('button', { name: 'Validate snapshot', exact: true }), target + '/validate');
  await page.getByRole('button', { name: 'Review decisions', exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll('#scenario-decisions tbody tr').length === 2);
  assert.equal(await newKeeper.locator('td').nth(2).locator('strong').textContent(), 'KEEP');
  for (const format of ['JSONL', 'JSON', 'CSV']) {
    await page.getByLabel('Export format', { exact: true }).selectOption(format);
    const pending = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Export manifest', exact: true }).click();
    const download = await pending;
    assert.equal(download.suggestedFilename(), `scenario-${created.id}-r8.${format.toLowerCase()}`);
    const saved = path.join(output, `browser-manifest.${format.toLowerCase()}`);
    await download.saveAs(saved); assert.equal(await download.failure(), null);
    const result = manifest(fs.readFileSync(saved, 'utf8'), format);
    assert.equal(result.metadata.scenario.revision, '8'); assert.equal(result.completion.recordCount, '2');
    assert.equal(result.completion.removalCandidates, '1');
    await ready('EXPORTED', 8);
    await page.getByText('Manifest downloaded.', { exact: true }).waitFor();
    await page.waitForFunction(() => [...document.querySelectorAll('button')].some(button => button.textContent.trim() === 'Export manifest' && !button.disabled));
  }
  await page.reload(); await ready('EXPORTED', 8);
  await page.getByText(/^Last export: CSV/).waitFor();
  await page.getByRole('button', { name: 'Review decisions', exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll('#scenario-decisions tbody tr').length === 2);
  await page.screenshot({ path: path.join(output, 'scenarios-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: path.join(output, 'scenarios-mobile.png'), fullPage: true });
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'scenario mobile page width');
  await panel.getByRole('button', { name: 'Containing directory', exact: true }).first().click();
  await page.waitForURL(/#\/scans\/2\/explore\/[0-9]+$/);
  await page.getByRole('button', { name: 'Scenario for this directory', exact: true }).click();
  await page.waitForURL(/#\/scenarios\/new$/);
  await page.getByLabel('Scenario name', { exact: true }).fill('Browser directory scenario');
  const directory = await click(page.getByRole('button', { name: 'Save scenario', exact: true }), '/api/v1/scenarios', 201);
  assert.equal(directory.config.request.directory.scanId, 2);
  assert(directory.config.request.directory.entryId > 1);
  await page.waitForURL(new RegExp('#/scenarios/' + directory.id + '$'));
  await ready('DRAFT', 1);
  await click(page.getByRole('button', { name: 'Generate decisions', exact: true }), '/api/v1/scenarios/' + directory.id + '/generate');
  await ready('READY', 1);
  await page.getByRole('button', { name: 'New scenario', exact: true }).click();
  await page.waitForURL(/#\/scenarios\/new$/);
  await page.waitForFunction(() => !document.querySelector('.definition-panel .badge'));
  await page.getByLabel('Selected scans', { exact: true }).selectOption(['2']);
  await page.waitForFunction(() => document.querySelector('.selected-scans')?.textContent?.includes('scan-2'));
  await page.getByLabel('Start from a saved search', { exact: true }).selectOption({ label: 'HTTP shared search' });
  await page.getByRole('button', { name: 'Use saved search', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('.selected-scans')?.textContent?.includes('scan-2'));
  await page.getByLabel('Scenario name', { exact: true }).fill('Browser saved search scenario');
  const seeded = await click(page.getByRole('button', { name: 'Save scenario', exact: true }), '/api/v1/scenarios', 201);
  assert.deepEqual(seeded.config.request.scanIds, [2]);
  assert.equal(seeded.config.request.name.value, 'shared');
  await page.waitForURL(new RegExp('#/scenarios/' + seeded.id + '$'));
  await ready('DRAFT', 1);
  await click(page.getByRole('button', { name: 'Generate decisions', exact: true }), '/api/v1/scenarios/' + seeded.id + '/generate');
  await ready('READY', 1);
  console.log('PASS: browser scenario workflow, JSON/JSONL/CSV downloads, exported reload, manual guards, directory/saved search scope, and mobile layout');
}
