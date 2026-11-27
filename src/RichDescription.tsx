import { useId, useState, type ReactNode } from 'react';
import { Check, Download, ExternalLink, List, Paperclip } from 'lucide-react';
import { descriptionImageUrl, externalDescriptionUrl } from './itemDetails';
import type { DescriptionBlock, DescriptionImage, DescriptionRun } from './types';

type ListNavigation = {
  listIds: ReadonlySet<string>;
  openList: (id: string) => void;
};

function renderRun(run: DescriptionRun, index: number, navigation: ListNavigation) {
  let text: ReactNode = run.text;
  if (run.bold) text = <strong>{text}</strong>;
  if (run.italic) text = <em>{text}</em>;
  if (run.underline) text = <u>{text}</u>;
  if (run.strike) text = <s>{text}</s>;
  const link = run.link;
  if (link?.type === 'list' && navigation.listIds.has(link.listId)) return <a key={index}
    href={`?list=${encodeURIComponent(link.listId)}`} className="description-list-link" onClick={event => {
      if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      navigation.openList(link.listId);
    }}>{text}<List size={13} aria-hidden="true" /></a>;
  if (link?.type === 'list') return <span key={index} title="List unavailable">{text}</span>;
  const href = link?.type === 'external' && externalDescriptionUrl(link.url);
  return href ? <a key={index} href={href} target="_blank" rel="noopener noreferrer">{text}<ExternalLink size={11} aria-hidden="true" /></a> :
    <span key={index}>{text}</span>;
}

function imageFileName(image: DescriptionImage) {
  const type = /^data:image\/(\w+);/.exec(image.dataUrl)?.[1] || 'webp';
  const name = (image.alt || 'image').replace(/\.(?:jpe?g|png|webp)$/i, '').replace(/[\\/:*?"<>|\u0000-\u001f]/g, '-');
  return `${name || 'image'}.${type === 'jpeg' ? 'jpg' : type}`;
}

function DescriptionPicture({ block }: { block: DescriptionImage }) {
  const src = descriptionImageUrl(block.dataUrl);
  if (!src) return null;
  if (block.display === 'attachment') return <a className="description-attachment" href={src} download={imageFileName(block)}>
    <img src={src} alt="" loading="lazy" />
    <span><Paperclip size={14} aria-hidden="true" />{block.alt || 'Image attachment'}</span><Download size={16} aria-label="Download" />
  </a>;
  return <img className="description-picture" src={src} alt={block.alt || ''} loading="lazy" />;
}

function compactBlocks(blocks: DescriptionBlock[]) {
  const limit = 180;
  let remaining = limit;
  let clipped = blocks.length > 2;
  const preview = blocks.slice(0, 2).map(block => {
    if (block.type === 'image') return block;
    const runs: DescriptionRun[] = [];
    for (const run of block.runs) {
      const characters = Array.from(run.text);
      if (characters.length <= remaining) {
        runs.push(run);
        remaining -= characters.length;
      } else {
        runs.push({ ...run, text: characters.slice(0, remaining).join('') + '…' });
        remaining = 0;
        clipped = true;
        break;
      }
    }
    return { ...block, runs };
  });
  return { preview, clipped };
}

export default function RichDescription({ blocks, navigation, busy, toggle }: {
  blocks: DescriptionBlock[];
  navigation: ListNavigation;
  busy: boolean;
  toggle: (index: number, checked: boolean) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const id = useId();
  const { preview, clipped } = compactBlocks(blocks);
  const visible = expanded || !clipped ? blocks : preview;
  const elements: ReactNode[] = [];
  for (let index = 0; index < visible.length; index++) {
    const block = visible[index];
    if (block.type === 'image') {
      elements.push(<DescriptionPicture key={index} block={block} />);
    } else if (block.type === 'bullet' || block.type === 'numbered') {
      const first = index;
      const rows: ReactNode[] = [];
      while (index < visible.length) {
        const line = visible[index];
        if (line.type !== block.type) break;
        rows.push(<li key={index}>{line.runs.map((run, key) => renderRun(run, key, navigation))}</li>);
        index++;
      }
      index--;
      elements.push(block.type === 'bullet' ? <ul key={first}>{rows}</ul> : <ol key={first}>{rows}</ol>);
    } else if (block.type === 'checkbox') {
      const at = index;
      const label = block.runs.map(run => run.text).join('') || 'Description line';
      elements.push(<div key={index} className={`description-checkbox${block.checked ? ' is-checked' : ''}`}>
        <span className="check-target subcheck-target">
          <input type="checkbox" checked={block.checked} disabled={busy} aria-label={`${block.checked ? 'Uncheck' : 'Check'} ${label}`} onChange={event => toggle(at, event.target.checked)} />
          <span className="checkbox-visual" aria-hidden="true">{block.checked && <Check size={13} />}</span>
        </span>
        <div>{block.runs.map((run, key) => renderRun(run, key, navigation))}</div>
      </div>);
    } else {
      elements.push(<p key={index}>{block.runs.map((run, key) => renderRun(run, key, navigation))}</p>);
    }
  }
  return <div className="description-preview item-description rich-description-preview">
    <div id={id} className="rich-description-content">{elements}</div>
    {clipped && <button type="button" className="text-button" aria-expanded={expanded} aria-controls={id}
      onClick={() => setExpanded(!expanded)}>{expanded ? 'Show less' : 'Show more'}</button>}
  </div>;
}
