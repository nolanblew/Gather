import { descriptionImageUrl, externalDescriptionUrl } from './itemDetails';
import type { DescriptionBlock, DescriptionImage, DescriptionLink, DescriptionRun, DescriptionTextBlock } from './types';

const blockTypes = new Set(['paragraph', 'bullet', 'numbered', 'checkbox']);
const blockTags = new Set(['DIV', 'P', 'UL', 'OL', 'LI']);
const internalLinkPrefix = 'gather:list:';
const marks = { bold: 'strong', italic: 'em', underline: 'u', strike: 's' } as const;
const imageBlocks = new WeakMap<HTMLElement, DescriptionImage>();
type Mark = keyof typeof marks;

export function editorLink(link: DescriptionLink) {
  return link.type === 'list' ? internalLinkPrefix + encodeURIComponent(link.listId) : externalDescriptionUrl(link.url);
}

export function readEditorLink(href: string): DescriptionLink | undefined {
  if (href.startsWith(internalLinkPrefix)) {
    try {
      const listId = decodeURIComponent(href.slice(internalLinkPrefix.length));
      if (listId) return { type: 'list', listId };
    } catch { return; }
  }
  const url = externalDescriptionUrl(href);
  if (url) return { type: 'external', url };
}

function appendRun(parent: HTMLElement, run: DescriptionRun) {
  let node: Node = document.createTextNode(run.text);
  for (const [mark, tag] of Object.entries(marks)) {
    if (!run[mark as Mark]) continue;
    const wrapper = document.createElement(tag);
    wrapper.append(node);
    node = wrapper;
  }
  const href = run.link && editorLink(run.link);
  if (href) {
    const anchor = document.createElement('a');
    anchor.setAttribute('href', href);
    anchor.append(node);
    node = anchor;
  }
  parent.append(node);
}

function editorControl(action: string, title: string, label: string) {
  const button = document.createElement('button');
  button.type = 'button';
  button.contentEditable = 'false';
  button.dataset.descriptionAction = action;
  button.title = title;
  button.setAttribute('aria-label', title);
  button.textContent = label;
  return button;
}

export function setDescriptionLine(line: HTMLElement, type: DescriptionTextBlock['type'], checked = false) {
  line.querySelectorAll(':scope > [data-description-action="check"]').forEach(button => button.remove());
  line.dataset.descriptionType = type;
  delete line.dataset.checked;
  if (type === 'checkbox') {
    line.dataset.checked = String(checked);
    const toggle = editorControl('check', checked ? 'Uncheck description line' : 'Check description line', checked ? '✓' : '');
    toggle.setAttribute('aria-pressed', String(checked));
    line.prepend(toggle);
  }
}

export function createDescriptionLine(block?: DescriptionTextBlock) {
  const line = document.createElement('div');
  setDescriptionLine(line, block?.type ?? 'paragraph', block?.type === 'checkbox' && block.checked);
  for (const run of block?.runs ?? []) appendRun(line, run);
  if (!block?.runs.some(run => run.text)) line.append(document.createElement('br'));
  return line;
}

export function createDescriptionImage(block: DescriptionImage) {
  const wrapper = document.createElement('div');
  wrapper.dataset.descriptionType = 'image';
  wrapper.dataset.display = block.display;
  wrapper.contentEditable = 'false';
  imageBlocks.set(wrapper, block);

  const image = document.createElement('img');
  const src = descriptionImageUrl(block.dataUrl);
  if (src) image.setAttribute('src', src);
  image.alt = block.alt || '';
  image.draggable = false;
  wrapper.append(image);

  const menu = document.createElement('details');
  menu.className = 'context-menu description-inline-menu';
  const summary = document.createElement('summary');
  summary.setAttribute('aria-label', block.alt ? `Actions for ${block.alt}` : 'Image actions');
  summary.textContent = '⋮';
  const actions = document.createElement('div');
  actions.className = 'menu-panel';
  actions.append(
    editorControl('placement', 'Move to attachment', 'Move to attachment'),
    editorControl('remove', 'Remove image', 'Remove image'),
  );
  menu.append(summary, actions);
  wrapper.append(menu);
  return wrapper;
}

export function replaceDescription(root: HTMLElement, blocks: DescriptionBlock[]) {
  root.replaceChildren(...(blocks.length ? blocks.map(block => block.type === 'image' ? createDescriptionImage(block) : createDescriptionLine(block)) : [createDescriptionLine()]));
}

function readRuns(nodes: Node[]) {
  const runs: DescriptionRun[] = [];
  const append = (text: string, format: Omit<DescriptionRun, 'text'>) => {
    if (!text) return;
    const previous = runs.at(-1);
    if (previous && previous.bold === format.bold && previous.italic === format.italic && previous.underline === format.underline
      && previous.strike === format.strike && JSON.stringify(previous.link) === JSON.stringify(format.link)) previous.text += text;
    else runs.push({ text, ...format });
  };
  const read = (node: Node, format: Omit<DescriptionRun, 'text'>) => {
    if (node.nodeType === Node.TEXT_NODE) {
      append(node.textContent?.replaceAll('\u00a0', ' ') ?? '', format);
      return;
    }
    if (!(node instanceof HTMLElement) || node.dataset.descriptionAction || node.contentEditable === 'false') return;
    if (node.tagName === 'BR') {
      if (node.nextSibling) append('\n', format);
      return;
    }
    const link = node.tagName === 'A' ? readEditorLink(node.getAttribute('href') || '') : undefined;
    const decoration = node.style.textDecorationLine || node.style.textDecoration;
    const next = {
      ...format,
      ...((node.matches('b,strong') || /bold|^[6-9]00$/.test(node.style.fontWeight)) && { bold: true }),
      ...((node.matches('i,em') || node.style.fontStyle === 'italic') && { italic: true }),
      ...((node.tagName === 'U' || decoration.includes('underline')) && { underline: true }),
      ...((node.matches('s,strike,del') || decoration.includes('line-through')) && { strike: true }),
      ...(link && { link }),
    };
    node.childNodes.forEach(child => read(child, next));
  };
  nodes.forEach(node => read(node, {}));
  return runs;
}

export function readDescriptionImage(node: HTMLElement): DescriptionImage | null {
  const cached = imageBlocks.get(node);
  if (cached) return cached;
  const image = node.querySelector('img');
  const dataUrl = image && descriptionImageUrl(image.getAttribute('src') || '');
  if (!image || !dataUrl) return null;
  const block: DescriptionImage = { type: 'image', dataUrl, ...(image.alt && { alt: image.alt }), display: node.dataset.display === 'attachment' ? 'attachment' : 'inline' };
  imageBlocks.set(node, block);
  return block;
}

export function readDescription(root: HTMLElement) {
  const blocks: DescriptionBlock[] = [];
  const readChildren = (parent: HTMLElement, type: DescriptionTextBlock['type'], checked: boolean, isLine: boolean) => {
    let inline: Node[] = [];
    let firstLine = true;
    const flush = (allowEmpty = false) => {
      if (!inline.length && !allowEmpty) return;
      const runs = readRuns(inline);
      const lineType = firstLine ? type : 'paragraph';
      blocks.push(lineType === 'checkbox' ? { type: lineType, runs, checked } : { type: lineType, runs });
      firstLine = false;
      inline = [];
    };
    for (const child of parent.childNodes) {
      if (!(child instanceof HTMLElement) || !blockTags.has(child.tagName)) {
        inline.push(child);
        continue;
      }
      if (child.dataset.descriptionAction) continue;
      flush();
      if (child.dataset.descriptionType === 'image') {
        const image = readDescriptionImage(child);
        if (image) blocks.push(image);
      } else if (child.matches('ul,ol')) {
        const listType = child.tagName === 'UL' ? 'bullet' : 'numbered';
        for (const line of child.children) if (line instanceof HTMLElement) readChildren(line, listType, false, true);
      } else {
        const typeHint = child.dataset.descriptionType as DescriptionTextBlock['type'];
        readChildren(child, blockTypes.has(typeHint) ? typeHint : 'paragraph', child.dataset.checked === 'true', true);
      }
      firstLine = false;
    }
    flush(isLine && firstLine);
  };
  readChildren(root, 'paragraph', false, false);
  while (blocks.at(-1)?.type === 'paragraph' && !(blocks.at(-1) as DescriptionTextBlock).runs.length) blocks.pop();
  return blocks;
}

export function editorLine(root: HTMLElement, node: Node | null): HTMLElement | null {
  if (!node || node === root || !root.contains(node)) return null;
  while (node.parentNode !== root) node = node.parentNode!;
  return node instanceof HTMLElement && blockTags.has(node.tagName) ? node : null;
}

export function focusDescriptionLine(line: HTMLElement, toEnd = false) {
  const root = line.parentElement;
  root?.focus({ preventScroll: true });
  const selection = document.getSelection();
  if (!selection) return;
  const range = document.createRange();
  range.selectNodeContents(line);
  range.collapse(!toEnd);
  if (!toEnd && line.firstChild instanceof HTMLElement && line.firstChild.dataset.descriptionAction) range.setStartAfter(line.firstChild);
  selection.removeAllRanges();
  selection.addRange(range);
}
