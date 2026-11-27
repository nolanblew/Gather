import type { DescriptionBlock, DescriptionLink, DescriptionRun, Item, Subitem } from './types.ts';

export const descriptionLimits = { blocks: 100, text: 16000, images: 8, imageSize: 2 * 1024 * 1024, alt: 500, url: 2000 };
export const subitemLimits = { count: 100, text: 500 };

export function externalDescriptionUrl(value: string) {
  const text = value.trim();
  if (text.length > descriptionLimits.url || /[\u0000-\u0020\u007f]/.test(text) || !/^(https?:\/\/|mailto:)/i.test(text)) return null;
  try {
    const url = new URL(text);
    return url.protocol === 'https:' || url.protocol === 'http:' || url.protocol === 'mailto:' ? text : null;
  } catch { return null; }
}

export function descriptionImageUrl(value: string) {
  return value.length <= Math.ceil(descriptionLimits.imageSize / 3) * 4 + 30
    && /^data:image\/(?:jpeg|png|webp);base64,[A-Za-z0-9+/]+={0,2}$/.test(value) ? value : null;
}

export function legacyDescription(note: string): DescriptionBlock[] {
  return note ? [{ type: 'paragraph', runs: [{ text: note }] }] : [];
}

export function itemDescriptionText(item: Pick<Item, 'note' | 'description'>) {
  if (item.description === undefined) return item.note;
  return item.description.map(block => block.type === 'image' ? block.alt || '' : block.runs.map(run => run.text).join('')).join('\n');
}

export function descriptionError(blocks: DescriptionBlock[]) {
  if (blocks.length > descriptionLimits.blocks) return 'Use up to 100 lines and images in a description.';
  let text = 0;
  let images = 0;
  for (const block of blocks) {
    if (block.type === 'image') images++;
    else {
      if (block.runs.length > 500) return 'Use fewer changes in formatting on one description line.';
      for (const run of block.runs) text += run.text.length;
    }
  }
  if (images > descriptionLimits.images) return 'Add up to 8 images to a description.';
  if (text > descriptionLimits.text) return 'Use up to 16,000 characters in a description.';
  return '';
}

export function sameSubitems(left: Subitem[] = [], right: Subitem[] = []) {
  return left.length === right.length && left.every((row, index) =>
    row.id === right[index].id && row.text === right[index].text && row.checked === right[index].checked);
}

function sameLink(left?: DescriptionLink, right?: DescriptionLink) {
  if (left === right) return true;
  if (!left || !right || left.type !== right.type) return false;
  return left.type === 'list' && right.type === 'list' ? left.listId === right.listId :
    left.type === 'external' && right.type === 'external' && left.url === right.url;
}

function sameRuns(left: DescriptionRun[], right: DescriptionRun[]) {
  return left.length === right.length && left.every((run, index) => {
    const other = right[index];
    return run.text === other.text && Boolean(run.bold) === Boolean(other.bold) && Boolean(run.italic) === Boolean(other.italic)
      && Boolean(run.underline) === Boolean(other.underline) && Boolean(run.strike) === Boolean(other.strike) && sameLink(run.link, other.link);
  });
}

export function sameDescription(left: DescriptionBlock[] = [], right: DescriptionBlock[] = []) {
  if (left === right) return true;
  return left.length === right.length && left.every((block, index) => {
    const other = right[index];
    if (block.type === 'image') return other.type === 'image' && block.display === other.display && block.alt === other.alt && block.dataUrl === other.dataUrl;
    if (block.type !== other.type) return false;
    if (block.type === 'checkbox' && other.type === 'checkbox' && block.checked !== other.checked) return false;
    return sameRuns(block.runs, other.runs);
  });
}
