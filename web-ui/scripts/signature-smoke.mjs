/** Signature persistence and browse signals against the packaged application. */
import assert from 'node:assert/strict';
import path from 'node:path';

export async function checkSignatureHttp(base) {
  async function request(endpoint, method = 'GET', body) {
    const response = await fetch(base + endpoint, { method,
      ...(body ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}) });
    return { status: response.status, body: response.status === 204 ? null : await response.json() };
  }
  async function file(scanId, name) {
    const result = await request('/api/v1/search/files', 'POST', {
      scanIds: [scanId], name: { operator: 'EXACT', value: name } });
    assert.equal(result.status, 200); assert.equal(result.body.items.length, 1); return result.body.items[0];
  }
  const shared = await file(1, 'shared-one.txt');
  const singleton = await file(3, 'signature-singleton.txt');
  const unknown = await file(1, 'unresolved.bin');
  const source = item => ({ scanId: item.scanId, entryId: item.entryId });
  assert(shared.duplicateCandidate && !shared.signatureMatch);
  assert(!singleton.duplicateCandidate && !singleton.signatureMatch);
  assert.equal((await request('/api/v1/signatures', 'POST', source(unknown))).status, 422);
  const one = await request('/api/v1/signatures', 'POST', { ...source(singleton), tag: 'Single', memo: 'Remove this only copy' });
  assert.equal(one.status, 201);
  const many = await request('/api/v1/signatures', 'POST', { ...source(shared), tag: 'Junk', memo: 'All copies' });
  assert.equal(many.status, 201);
  const oneId = one.body.id, manyId = many.body.id;
  assert.equal((await request('/api/v1/signatures', 'POST', source(shared))).status, 409);
  const list = await request('/api/v1/signatures?limit=1');
  assert(list.body.page.hasMore);
  const next = await request('/api/v1/signatures?limit=1&cursor=' + encodeURIComponent(list.body.page.nextCursor));
  assert.equal(next.body.items.length, 1); assert(!next.body.page.hasMore);
  assert.notEqual(next.body.items[0].id, list.body.items[0].id);
  const singleMatch = await request('/api/v1/signatures/' + oneId + '/matches');
  assert.equal(singleMatch.body.total, 1);
  assert(singleMatch.body.items[0].removalCandidate && !singleMatch.body.items[0].duplicateCandidate);
  const first = await request('/api/v1/signatures/' + manyId + '/matches?limit=100');
  assert.equal(first.body.total, 116); assert.equal(first.body.items.length, 100); assert(first.body.page.hasMore);
  const last = await request('/api/v1/signatures/' + manyId + '/matches?limit=100&cursor=' + encodeURIComponent(first.body.page.nextCursor));
  assert.equal(last.body.items.length, 16); assert(!last.body.page.hasMore);
  assert([...first.body.items, ...last.body.items].every(x => x.signatureMatch && x.removalCandidate && x.duplicateCandidate));
  assert.equal((await request('/api/v1/signatures/' + oneId + '/matches?cursor=' + encodeURIComponent(first.body.page.nextCursor))).status, 400);
  const children = await request(`/api/v1/scans/1/entries/${shared.parentId}/children`);
  assert(children.body.items.every(x => x.removalCandidate && x.signature.tag === 'Junk'));
  const detail = await request(`/api/v1/scans/3/entries/${singleton.entryId}`);
  assert(detail.body.removalCandidate && detail.body.signature.memo === 'Remove this only copy');
  assert((await file(3, singleton.filename)).removalCandidate);
  const groups = await request('/api/v1/duplicates/groups', 'POST', { scanIds: [2] });
  assert(groups.body.items[0].signatureMatch);
  const occurrences = await request('/api/v1/duplicates/groups/' + encodeURIComponent(groups.body.items[0].groupId) + '/occurrences', 'POST', { scanIds: [2] });
  assert(occurrences.body.items.every(x => x.removalCandidate));
  const edited = await request('/api/v1/signatures/' + manyId, 'PUT', { tag: 'Discard', memo: '<b>λ</b>\nKeep as plain text' });
  assert.equal(edited.status, 200); assert.equal(edited.body.sha256, shared.sha256);
  assert.equal((await request('/api/v1/signatures/' + manyId)).body.memo, '<b>λ</b>\nKeep as plain text');
  assert.equal((await request('/api/v1/signatures/' + manyId, 'PUT', { size: '1' })).status, 400);
  assert.equal((await request('/api/v1/signatures/' + manyId, 'DELETE')).status, 204);
  const cleared = await file(1, 'shared-one.txt');
  assert(cleared.duplicateCandidate && !cleared.signatureMatch && !cleared.removalCandidate);
  assert.equal((await request('/api/v1/signatures/' + oneId, 'DELETE')).status, 204);
  console.log('PASS: packaged signatures, singleton and all-copy matches, notes, signals, bound pagination, and delete');
}

export async function checkSignatureBrowser(page, base, output) {
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.goto(base + '/#/scans/3/explore/1');
  const singleRow = page.locator('.listing-panel tbody tr').filter({ hasText: 'signature-singleton.txt' });
  await singleRow.getByRole('button', { name: 'Add signature', exact: true }).click();
  let dialog = page.getByRole('dialog', { name: 'Add to signature store', exact: true });
  await dialog.getByLabel('Tag (optional)').fill('Single');
  await dialog.getByLabel('Memo (optional)').fill('Unwanted unique content');
  await dialog.getByRole('button', { name: 'Save signature', exact: true }).click();
  await singleRow.locator('.signature-badge').waitFor();
  assert.equal(await singleRow.locator('.duplicate-badge').count(), 0);
  assert.equal(await singleRow.locator('td').first().evaluate(node => getComputedStyle(node).backgroundColor), 'rgb(255, 240, 241)');
  await singleRow.getByRole('button', { name: /signature-singleton.txt/ }).click();
  const drawer = page.getByRole('dialog', { name: 'File details', exact: true });
  await drawer.getByText('Unwanted unique content', { exact: true }).waitFor();
  await drawer.getByRole('button', { name: 'Edit signature', exact: true }).click();
  dialog = page.getByRole('dialog', { name: 'Edit signature', exact: true });
  await dialog.getByLabel('Memo (optional)').fill('Edited single memo');
  await dialog.getByRole('button', { name: 'Save signature', exact: true }).click();
  await drawer.getByText('Edited single memo', { exact: true }).waitFor();
  await drawer.getByRole('button', { name: 'Close', exact: true }).click();
  await page.reload(); await singleRow.locator('.signature-badge').waitFor();

  const search = await fetch(base + '/api/v1/search/files', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ scanIds: [2], limit: 100 }) }).then(response => response.json());
  await page.goto(base + '/#/scans/2/explore/' + search.items[0].parentId);
  const rows = page.locator('.listing-panel tbody tr');
  await rows.first().locator('.duplicate-badge').waitFor();
  assert.equal(await rows.locator('.signature-badge').count(), 0);
  await rows.first().getByRole('button', { name: /shared-000.txt/ }).click();
  await drawer.getByRole('button', { name: 'Add signature', exact: true }).click();
  dialog = page.getByRole('dialog', { name: 'Add to signature store', exact: true });
  await dialog.getByLabel('Tag (optional)').fill('All copies');
  await dialog.getByLabel('Memo (optional)').fill('Delete every match');
  await dialog.getByRole('button', { name: 'Save signature', exact: true }).click();
  await drawer.locator('.signature-badge').waitFor();
  await drawer.getByRole('button', { name: 'Close', exact: true }).click();
  assert.equal(await rows.locator('.signature-badge').count(), 2);
  assert.equal(await rows.locator('.duplicate-badge').count(), 2);
  await page.screenshot({ path: path.join(output, 'signatures-explorer-desktop.png'), fullPage: true });

  await page.goto(base + '/#/signatures');
  await page.getByRole('heading', { name: 'Signature store', exact: true }).waitFor();
  const storeRow = page.locator('app-signature-store .results-panel tbody tr').filter({ hasText: 'All copies' });
  await storeRow.getByRole('button', { name: 'Find matches', exact: true }).click();
  const matchPanel = page.getByRole('region', { name: 'Signature matches', exact: true });
  await matchPanel.locator('tbody tr').first().waitFor();
  assert.equal(await matchPanel.locator('tbody tr').count(), 100);
  await matchPanel.getByRole('button', { name: 'Next', exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll('.signature-matches tbody tr').length === 16);
  await page.screenshot({ path: path.join(output, 'signatures-store-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: path.join(output, 'signatures-store-mobile.png'), fullPage: true });
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'signature store mobile width');
  await storeRow.getByRole('button', { name: 'Delete signature', exact: true }).click();
  await page.getByRole('button', { name: 'Remove signature', exact: true }).click();
  await storeRow.waitFor({ state: 'detached' });
  await page.goto(base + '/#/scans/2/explore/' + search.items[0].parentId);
  await rows.first().locator('.duplicate-badge').waitFor();
  assert.equal(await rows.locator('.signature-badge').count(), 0);
  assert.equal(await rows.locator('td').first().evaluate(node => getComputedStyle(node).backgroundColor), 'rgb(255, 248, 229)');
  console.log('PASS: browser explorer/detail signature creation, editing, persistence, both-copy colors, match pages, delete, and mobile width');
}
