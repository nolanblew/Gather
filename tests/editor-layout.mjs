import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import sharp from 'sharp';
import { preview } from 'vite';
import { seedWorkspace } from '../server/seed.mjs';

const workspace = seedWorkspace();
const list = workspace.lists[0];
const item = list.items.find(entry => !entry.checked);
item.text = 'Pack the picnic basket';
const photo = await sharp({ create: { width: 720, height: 300, channels: 3, background: '#689165' } }).png().toBuffer();
const image = { type: 'image', dataUrl: 'data:image/png;base64,' + photo.toString('base64') };
item.description = [
  { type: 'paragraph', runs: [{ text: 'Lunch for four. ', bold: true }, { text: 'Bring something to share.' }] },
  { type: 'bullet', runs: [{ text: 'Pick up bread and berries from the market list.' }] },
  { ...image, alt: 'Picnic basket', display: 'inline' },
  { ...image, alt: 'picnic-map.png', display: 'attachment' },
];
item.subitems = ['Wash the berries', 'Wrap the sandwiches', 'Fill the thermos'].map((text, index) => ({
  id: 'step-' + index, text, checked: index === 0,
}));

// Hit-testing catches controls hidden behind a sticky header or a clipped dialog.
async function canTap(locator) {
  return locator.evaluate(element => {
    const { x, y, width, height } = element.getBoundingClientRect();
    return width > 0 && height > 0 && element.contains(document.elementFromPoint(x + width / 2, y + height / 2));
  });
}

const server = await preview({ preview: { host: '127.0.0.1', port: 0 }, logLevel: 'silent' });
let browser;
try {
  const base = 'http://127.0.0.1:' + server.httpServer.address().port;
  browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH });
  for (const [width, height] of [[360, 740], [1440, 960]]) {
    const page = await browser.newPage({ viewport: { width, height }, hasTouch: width < 500, serviceWorkers: 'block' });
    await page.route('**/api/**', route => {
      const path = new URL(route.request().url()).pathname;
      const responses = {
        '/api/auth/session': { authenticated: true, userId: 'tester', username: 'Tester' },
        '/api/capabilities': { features: ['checklist-subitems', 'item-descriptions'] },
        '/api/workspace': workspace,
      };
      return route.fulfill({ json: responses[path] ?? {} });
    });
    await page.goto(base + '/?list=' + list.id);
    await page.getByRole('button', { name: 'Edit ' + item.text, exact: true }).click();
    const editor = page.getByRole('dialog');
    const toolbar = editor.getByLabel('Description formatting', { exact: true });
    await toolbar.waitFor();
    await editor.locator('img').first().evaluate(img => img.decode());
    assert.equal(await editor.locator('img').count(), 2);
    assert.equal(await editor.getByRole('textbox', { name: /^Step \d+$/ }).count(), 3);
    const bounds = await toolbar.boundingBox();
    assert.ok(bounds.height <= 52, 'The formatting tools should fit on one row at width ' + width);
    assert.ok(await canTap(editor.getByRole('button', { name: 'Add step', exact: true })), 'Add step should be visible on open at width ' + width);
    const save = editor.getByRole('button', { name: 'Save', exact: true });
    assert.ok(await canTap(save), 'Save should be visible on open');
    assert.ok(await editor.evaluate(node => node.scrollWidth <= node.clientWidth), 'Editor should fit the screen horizontally');
    if (width === 360) {
      assert.equal(await page.locator('input:focus, [contenteditable=true]:focus').count(), 0, 'Opening the editor should leave the keyboard closed');
      await page.setViewportSize({ width, height: 420 });
      await editor.getByRole('button', { name: 'Add step', exact: true }).click();
      await editor.getByRole('textbox', { name: 'Step 4' }).fill('Bring water');
      assert.ok(await canTap(save), 'Save should stay visible while editing the last step in a short viewport');
    }
    await page.close();
  }
  console.log('Passed the item editor layout checks.');
} finally {
  await browser?.close();
  await new Promise(resolve => server.httpServer.close(resolve));
}
