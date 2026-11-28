import { createHash, randomUUID } from 'node:crypto';
import sharp from 'sharp';
import { InputError, bool, fail, id, object, string } from './input.mjs';

const descriptionTypes = new Set(['paragraph', 'bullet', 'numbered', 'checkbox']);
const marks = ['bold', 'italic', 'underline', 'strike'];
const imageFormats = { jpeg: 'jpeg', png: 'png', webp: 'webp' };
const imageLimit = 2 * 1024 * 1024;
const imagePattern = /^data:image\/(jpeg|png|webp);base64,([A-Za-z0-9+/]+={0,2})$/;
const imageDimension = 1440;
const webpImageChunks = new Set(['VP8 ', 'VP8L', 'VP8X', 'ALPH']);
const maxSubitems = 100;

function imageData(value) {
  if (typeof value !== 'string') fail('Choose a JPEG, PNG or WebP image.');
  if (value.length > Math.ceil(imageLimit / 3) * 4 + 32) throw new InputError('Images must be 2 MiB or smaller.', 413);
  const match = imagePattern.exec(value);
  if (!match) fail('Choose a JPEG, PNG or WebP image.');
  const bytes = Buffer.from(match[2], 'base64');
  if (!bytes.length || bytes.toString('base64') !== match[2]) fail('Image data is invalid.');
  if (bytes.length > imageLimit) throw new InputError('Images must be 2 MiB or smaller.', 413);
  const format = imageFormats[match[1]];
  const signature = format === 'jpeg' ? bytes.subarray(0, 3).equals(Buffer.from([0xff, 0xd8, 0xff]))
    : format === 'png' ? bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
    : bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 12) === 'WEBP';
  if (!signature) fail('Image data does not match its format.');
  return { bytes, format };
}

function readLink(link) {
  object(link);
  if (link.type === 'list') return { type: 'list', listId: id(link.listId) };
  if (link.type !== 'external') fail('Choose a list or an external URL.');
  const url = string(link.url, 'Link URL', 2000, true).trim();
  if (/[\u0000-\u0020\u007f]/.test(url) || !/^(https?:\/\/|mailto:)/i.test(url)) fail('Links must use HTTP, HTTPS or mailto.');
  try { new URL(url); } catch { fail('Enter a valid link URL.'); }
  return { type: 'external', url };
}

function readDescription(input) {
  if (!Array.isArray(input) || input.length > 100) fail('Descriptions can have up to 100 blocks.');
  let textLength = 0;
  let images = 0;
  return input.map(block => {
    object(block);
    const { type } = block;
    if (type === 'image') {
      if (++images > 8) fail('Descriptions can have up to eight images.');
      const { dataUrl, display } = block;
      if (display !== 'inline' && display !== 'attachment') fail('Choose an image display mode.');
      imageData(dataUrl);
      const alt = Object.hasOwn(block, 'alt') ? string(block.alt, 'Image description', 500) : '';
      return { type, dataUrl, display, ...(alt ? { alt } : {}) };
    }
    if (!descriptionTypes.has(type) || !Array.isArray(block.runs) || block.runs.length > 500) fail('Invalid description block.');
    const runs = block.runs.map(run => {
      object(run);
      const text = string(run.text, 'Description text', 16000);
      if ((textLength += text.length) > 16000) fail('Descriptions can have up to 16,000 characters.');
      const result = { text };
      for (const mark of marks) if (Object.hasOwn(run, mark) && bool(run[mark], mark)) result[mark] = true;
      if (Object.hasOwn(run, 'link')) result.link = readLink(run.link);
      return result;
    });
    return { type, runs, ...(type === 'checkbox' ? { checked: bool(block.checked, 'Checked') } : {}) };
  });
}

function isPlainWebp(bytes) {
  if (bytes.readUInt32LE(4) + 8 !== bytes.length) return false;
  let offset = 12;
  while (offset + 8 <= bytes.length) {
    if (!webpImageChunks.has(bytes.toString('ascii', offset, offset + 4))) return false;
    const size = bytes.readUInt32LE(offset + 4);
    offset += 8 + size + (size % 2);
  }
  return offset === bytes.length;
}

async function prepareDescription(value, existing = []) {
  const preparedImages = new Map(existing.filter(block => block.type === 'image').map(block => [block.dataUrl, block.dataUrl]));
  const blocks = readDescription(value);
  for (const block of blocks) {
    if (block.type !== 'image') continue;
    const source = block.dataUrl;
    if (preparedImages.has(source)) {
      block.dataUrl = preparedImages.get(source);
      continue;
    }
    const { bytes, format } = imageData(source);
    try {
      const image = sharp(bytes, { limitInputPixels: 16_000_000, failOn: 'warning' });
      const metadata = await image.metadata();
      if (metadata.format !== format || (metadata.pages ?? 1) > 1) throw new Error('Unsupported image format');
      // Decode an already-sanitized backup image without changing its quality or size.
      if (format === 'webp' && metadata.width <= imageDimension && metadata.height <= imageDimension && isPlainWebp(bytes)) {
        await image.raw().toBuffer();
        preparedImages.set(source, source);
        continue;
      }
      const output = await image.rotate()
        .resize({ width: imageDimension, height: imageDimension, fit: 'inside', withoutEnlargement: true })
        .webp({ quality: 82 }).toBuffer();
      if (output.length > imageLimit) throw new Error('Image too large');
      block.dataUrl = 'data:image/webp;base64,' + output.toString('base64');
      preparedImages.set(source, block.dataUrl);
    } catch {
      fail('Choose a readable, still image up to 16 megapixels.');
    }
  }
  return blocks;
}

export const itemDetailActions = new Set(['item.create', 'item.createWithPeople', 'item.update', 'item.updateWithPeople', 'item.updateDescription']);

const imageDigest = dataUrl => createHash('sha256').update(dataUrl).digest('hex');

function collectAliases(source, description, aliases) {
  for (const [index, block] of description.entries()) {
    if (block.type !== 'image') continue;
    const previous = source[index].dataUrl;
    if (previous !== block.dataUrl && !aliases.has(previous)) aliases.set(previous, imageDigest(block.dataUrl));
  }
}

function imageReceipt(aliases) {
  if (aliases.size) return { resolvedImages: Object.fromEntries(Array.from(aliases, ([source, target]) => [imageDigest(source), target])) };
}

export async function prepareActionImages(input) {
  const payload = input.payload;
  const update = input.type.startsWith('item.update');
  const expected = update && Object.hasOwn(payload, 'expectedDescription') ? readDescription(payload.expectedDescription) : [];
  const description = await prepareDescription(payload.description, expected);
  const aliases = new Map();
  collectAliases(payload.description, description, aliases);
  return {
    prepared: { ...input, payload: { ...payload, description } },
    ...imageReceipt(aliases),
  };
}

export async function prepareImportImages(workspace) {
  const aliases = new Map();
  for (const list of workspace.lists) {
    for (const item of list.items) {
      if (!item.description?.some(block => block.type === 'image')) continue;
      const source = item.description;
      item.description = await prepareDescription(source);
      collectAliases(source, item.description, aliases);
    }
  }
  return { workspace, ...imageReceipt(aliases) };
}

function subitemText(value) {
  return string(value, 'Subitem text', 500, true);
}

function readSubitems(input, unique = id) {
  if (!Array.isArray(input) || input.length > maxSubitems) fail('An item can have up to 100 subitems.');
  const ids = new Set();
  return input.map(subitem => {
    object(subitem);
    const subitemId = unique(subitem.id);
    if (ids.has(subitemId)) fail('Subitem IDs must be unique.');
    ids.add(subitemId);
    return { id: subitemId, text: subitemText(subitem.text), checked: bool(subitem.checked, 'Checked') };
  });
}

const sparse = (item, key, value) => {
  if (value.length) item[key] = value;
  else delete item[key];
};

export function readItemDetails(source, item, unique) {
  if (Object.hasOwn(source, 'description')) sparse(item, 'description', readDescription(source.description));
  if (Object.hasOwn(source, 'subitems')) sparse(item, 'subitems', readSubitems(source.subitems, unique));
}

const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
function subitemConflict() {
  throw new InputError('Subitems changed. Review the item and try again.', 409, 'SUBITEMS_CHANGED');
}

export function editItemDetails(list, item, payload, update = false) {
  if (Object.hasOwn(payload, 'description')) {
    if (update && (!Object.hasOwn(payload, 'expectedDescription') ||
        !equal(readDescription(payload.expectedDescription), item.description ?? []))) {
      throw new InputError('Description changed. Review the item and try again.', 409, 'DESCRIPTION_CHANGED');
    }
    sparse(item, 'description', readDescription(payload.description));
  }
  if (Object.hasOwn(payload, 'subitems')) {
    if (list.kind !== 'checklist') fail('Subitems are available on checklists.');
    if (update && (!Object.hasOwn(payload, 'expectedSubitems') ||
        !equal(readSubitems(payload.expectedSubitems), item.subitems ?? []))) subitemConflict();
    sparse(item, 'subitems', readSubitems(payload.subitems));
  }
}

export function editSubitem(list, item, command, payload) {
  if (list.kind !== 'checklist') fail('Subitems are available on checklists.');
  const subitems = item.subitems ?? [];
  if (command === 'create') {
    if (subitems.length >= maxSubitems) fail('An item can have up to 100 subitems.');
    const subitemId = Object.hasOwn(payload, 'subitemId') ? id(payload.subitemId) : randomUUID();
    subitems.push({ id: subitemId, text: subitemText(payload.text), checked: Object.hasOwn(payload, 'checked') ? bool(payload.checked, 'Checked') : false });
    item.subitems = subitems;
    return { createdSubitemId: subitemId };
  }
  const subitemId = id(payload.subitemId);
  const index = subitems.findIndex(value => value.id === subitemId);
  if (index === -1) throw new InputError('Subitem not found.', 404);
  if (command === 'update') {
    const subitem = subitems[index];
    if (Object.hasOwn(payload, 'text')) subitem.text = subitemText(payload.text);
    if (Object.hasOwn(payload, 'checked')) subitem.checked = bool(payload.checked, 'Checked');
  } else if (command === 'delete') {
    subitems.splice(index, 1);
    sparse(item, 'subitems', subitems);
  } else if (command === 'move') {
    if (!Number.isSafeInteger(payload.index) || payload.index < 0 || payload.index >= subitems.length) fail('Index out of range.');
    if (Object.hasOwn(payload, 'expectedSubitemIds') && !equal(payload.expectedSubitemIds, subitems.map(value => value.id))) subitemConflict();
    const [subitem] = subitems.splice(index, 1);
    subitems.splice(payload.index, 0, subitem);
  } else fail('Unknown action.');
}

export function resetItemCompletion(item) {
  item.checked = false;
  for (const subitem of item.subitems ?? []) subitem.checked = false;
}

export function remapItemDetails(item, mapping) {
  if (item.subitems) item.subitems = item.subitems.map(subitem => ({ ...subitem, id: mapping.get(subitem.id) }));
  if (item.description) {
    item.description = item.description.map(block => {
      if (block.type === 'image') return { ...block };
      const runs = block.runs.map(run => {
        const link = run.link;
        if (link?.type !== 'list') return { ...run };
        return { ...run, link: { ...link, listId: mapping.get(link.listId) ?? link.listId } };
      });
      return { ...block, runs };
    });
  }
}
