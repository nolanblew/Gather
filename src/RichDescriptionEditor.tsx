import { useEffect, useId, useRef, useState, type ChangeEvent, type KeyboardEvent } from 'react';
import { Bold, ChevronDown, ImagePlus, Italic, Link2, List, ListChecks, ListOrdered, MoreVertical, Paperclip, Strikethrough, Underline } from 'lucide-react';
import { prepareDescriptionImage } from './descriptionImage';
import { descriptionImageUrl, descriptionLimits, externalDescriptionUrl } from './itemDetails';
import { createDescriptionImage, createDescriptionLine, editorLine, editorLink, focusDescriptionLine, readDescription, readDescriptionImage, readEditorLink, replaceDescription, setDescriptionLine } from './richDescriptionDom';
import type { DescriptionBlock, DescriptionImage, DescriptionLink, DescriptionTextBlock, GatherList } from './types';

const formatButtons = [
  { command: 'bold', icon: Bold, label: 'Bold' },
  { command: 'italic', icon: Italic, label: 'Italic' },
  { command: 'underline', icon: Underline, label: 'Underline' },
  { command: 'strikeThrough', icon: Strikethrough, label: 'Strikethrough' },
];

const lineButtons = [
  { type: 'bullet', icon: List, label: 'Bulleted list' },
  { type: 'numbered', icon: ListOrdered, label: 'Numbered list' },
  { type: 'checkbox', icon: ListChecks, label: 'Checkbox' },
] as const;

type LinkDraft = { type: DescriptionLink['type']; target: string; label: string; selected: string; existing: boolean };
const isAttachment = (block: DescriptionBlock): block is DescriptionImage => block.type === 'image' && block.display === 'attachment';

function rejoinAttachments(content: DescriptionBlock[], source: DescriptionBlock[]) {
  const blocks = content.slice();
  source.forEach((block, index) => { if (isAttachment(block)) blocks.splice(index, 0, block); });
  return blocks;
}

export default function RichDescriptionEditor({ value, lists, disabled, onChange, onPreparing }: {
  value: DescriptionBlock[];
  lists: Pick<GatherList, 'id' | 'title'>[];
  disabled: boolean;
  onChange: (description: DescriptionBlock[]) => void;
  onPreparing: (preparing: boolean) => void;
}) {
  const root = useRef<HTMLDivElement>(null);
  const selectionRange = useRef<Range | null>(null);
  const lastWritten = useRef<DescriptionBlock[] | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const attachButton = useRef<HTMLButtonElement>(null);
  const lineMenu = useRef<HTMLDetailsElement>(null);
  const display = useRef<DescriptionImage['display']>('inline');
  const linkButton = useRef<HTMLButtonElement>(null);
  const linkTarget = useRef<HTMLInputElement | HTMLSelectElement | null>(null);
  const [lineType, setLineType] = useState('');
  const [activeFormats, setActiveFormats] = useState<string[]>([]);
  const [link, setLink] = useState<LinkDraft | null>(null);
  const [error, setError] = useState('');
  const [preparing, setPreparing] = useState(false);
  const labelId = useId();

  useEffect(() => {
    if (!root.current || value === lastWritten.current) return;
    replaceDescription(root.current, value.filter(block => !isAttachment(block)));
    selectionRange.current = null;
    lastWritten.current = value;
  }, [value]);

  useEffect(() => {
    function rememberSelection() {
      const editor = root.current;
      const current = document.getSelection();
      if (!editor || !current?.rangeCount || !editor.contains(current.anchorNode) || !editor.contains(current.focusNode)) return;
      selectionRange.current = current.getRangeAt(0).cloneRange();
      setLineType(editorLine(editor, current.anchorNode)?.dataset.descriptionType || '');
      setActiveFormats(formatButtons.filter(button => document.queryCommandState(button.command)).map(button => button.command));
    }
    document.addEventListener('selectionchange', rememberSelection);
    return () => document.removeEventListener('selectionchange', rememberSelection);
  }, []);

  const commit = (blocks: DescriptionBlock[]) => {
    lastWritten.current = blocks;
    onChange(blocks);
  };
  const currentBlocks = () => {
    const source = lastWritten.current ?? value;
    return root.current ? rejoinAttachments(readDescription(root.current), source) : source;
  };
  const sync = () => commit(currentBlocks());

  const editImage = (index: number, action: 'placement' | 'remove', restoreFocus: HTMLElement | null) => {
    const blocks = currentBlocks();
    const image = blocks[index];
    if (image?.type !== 'image' || !root.current) return;
    if (action === 'remove') {
      blocks.splice(index, 1);
    } else {
      blocks[index] = { ...image, display: image.display === 'inline' ? 'attachment' : 'inline' };
    }
    replaceDescription(root.current, blocks.filter(block => !isAttachment(block)));
    selectionRange.current = null;
    commit(blocks);
    restoreFocus?.focus({ preventScroll: true });
  };

  const restoreSelection = () => {
    const editor = root.current;
    const selection = document.getSelection();
    if (!editor || !selection) return null;
    const range = selectionRange.current;
    if (!range || !editor.contains(range.startContainer) || !editor.contains(range.endContainer)) {
      const last = editor.lastElementChild;
      if (last instanceof HTMLElement && last.dataset.descriptionType !== 'image') {
        focusDescriptionLine(last, true);
      } else {
        const line = createDescriptionLine();
        editor.append(line);
        focusDescriptionLine(line);
      }
      return selection;
    }
    editor.focus({ preventScroll: true });
    selection.removeAllRanges();
    selection.addRange(range);
    return selection;
  };

  const format = (command: string) => {
    if (!restoreSelection()) return;
    document.execCommand(command);
    setActiveFormats(formatButtons.filter(button => document.queryCommandState(button.command)).map(button => button.command));
    sync();
  };

  const changeLine = (type: DescriptionTextBlock['type']) => {
    if (lineMenu.current) lineMenu.current.open = false;
    const editor = root.current;
    const selection = restoreSelection();
    if (!editor || !selection?.rangeCount) return;
    if (!editorLine(editor, selection.anchorNode) || !editorLine(editor, selection.focusNode)) {
      document.execCommand('formatBlock', false, 'div');
    }
    if (!selection.rangeCount) return;
    const range = selection.getRangeAt(0);
    const anchor = editorLine(editor, selection.anchorNode);
    const lines = Array.from(editor.children).filter((node): node is HTMLElement => node instanceof HTMLElement
      && node.matches('div,p') && node.dataset.descriptionType !== 'image'
      && (range.collapsed ? node === anchor : range.intersectsNode(node)));
    if (!lines.length && range.collapsed && selection.anchorNode === editor) {
      const line = createDescriptionLine();
      range.insertNode(line);
      focusDescriptionLine(line);
      lines.push(line);
    }
    const nextType = lines.every(line => line.dataset.descriptionType === type) ? 'paragraph' : type;
    for (const line of lines) setDescriptionLine(line, nextType, line.dataset.checked === 'true');
    if (lines.length === 1 && !readDescription(lines[0]).some(block => block.type === 'image' || block.runs.some(run => run.text))) {
      focusDescriptionLine(lines[0]);
    }
    setLineType(nextType);
    sync();
  };

  const openLink = () => {
    const selection = restoreSelection();
    if (!selection) return;
    const node = selection.anchorNode;
    const active = (node instanceof Element ? node : node?.parentElement)?.closest('a');
    const existing = readEditorLink(active?.getAttribute('href') || '');
    if (active && selection.isCollapsed) {
      const range = document.createRange();
      range.selectNodeContents(active);
      selectionRange.current = range;
    }
    const selected = selectionRange.current?.toString() || selection.toString();
    setLink({ type: existing?.type ?? 'external', target: existing?.type === 'list' ? existing.listId : existing?.url || '', label: selected, selected, existing: Boolean(existing) });
    setError('');
    requestAnimationFrame(() => linkTarget.current?.focus());
  };

  const insertLink = () => {
    if (!link) return;
    let target: DescriptionLink;
    if (link.type === 'external') {
      const url = externalDescriptionUrl(link.target);
      if (!url) { setError('Enter a full web address or a mailto link.'); return; }
      target = { type: 'external', url };
    } else {
      if (!lists.some(list => list.id === link.target)) { setError('Choose a Gather list.'); return; }
      target = { type: 'list', listId: link.target };
    }
    const href = editorLink(target);
    const selection = restoreSelection();
    if (!href || !selection) return;
    const list = target.type === 'list' ? lists.find(entry => entry.id === target.listId) : null;
    const label = link.label.trim() || list?.title || link.target.trim();
    if (!selection.isCollapsed && label === link.selected) document.execCommand('createLink', false, href);
    else {
      const anchor = document.createElement('a');
      anchor.setAttribute('href', href);
      anchor.textContent = label;
      document.execCommand('insertHTML', false, anchor.outerHTML);
    }
    setLink(null);
    setError('');
    sync();
  };

  const removeLink = () => {
    if (restoreSelection()) document.execCommand('unlink');
    setLink(null);
    setError('');
    sync();
  };

  const insertImage = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = '';
    const editor = root.current;
    if (!file || !editor || preparing) return;
    setError('');
    if (currentBlocks().filter(block => block.type === 'image').length >= descriptionLimits.images) {
      setError('Add up to 8 images to a description.');
      return;
    }
    setPreparing(true);
    onPreparing(true);
    const imageDisplay = display.current;
    try {
      const dataUrl = await prepareDescriptionImage(file);
      if (!editor.isConnected) return;
      const block: DescriptionImage = { type: 'image', dataUrl, alt: file.name.slice(0, descriptionLimits.alt), display: imageDisplay };
      if (imageDisplay === 'attachment') {
        commit([...currentBlocks(), block]);
        requestAnimationFrame(() => attachButton.current?.focus({ preventScroll: true }));
        return;
      }
      const selection = restoreSelection();
      const selected = editorLine(editor, selection?.anchorNode ?? null);
      const image = createDescriptionImage(block);
      if (selected) selected.after(image);
      else editor.append(image);
      const next = image.nextElementSibling;
      if (next instanceof HTMLElement && next.dataset.descriptionType !== 'image') focusDescriptionLine(next);
      else {
        const line = createDescriptionLine();
        image.after(line);
        focusDescriptionLine(line);
      }
      sync();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'This image couldn’t be opened.');
    } finally {
      setPreparing(false);
      onPreparing(false);
    }
  };

  const openImagePicker = (placement: DescriptionImage['display']) => {
    display.current = placement;
    fileInput.current?.click();
  };

  const handleKey = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.target instanceof Element && event.target.closest('[contenteditable="false"]')) {
      const menu = event.target.closest('details');
      if (event.key === 'Escape' && menu?.open) {
        event.preventDefault();
        event.stopPropagation();
        menu.open = false;
        menu.querySelector('summary')?.focus();
      }
      return;
    }
    if (event.nativeEvent.isComposing || event.key !== 'Enter' || event.shiftKey || event.altKey || event.metaKey || event.ctrlKey) return;
    const editor = event.currentTarget;
    const selection = document.getSelection();
    const line = editorLine(editor, selection?.anchorNode ?? null);
    const type = line?.dataset.descriptionType;
    if (!line || !selection?.isCollapsed || (type !== 'bullet' && type !== 'numbered' && type !== 'checkbox')) return;
    event.preventDefault();
    const text = Array.from(line.childNodes).filter(node => !(node instanceof HTMLElement && node.dataset.descriptionAction)).map(node => node.textContent).join('');
    if (!text.trim()) {
      setDescriptionLine(line, 'paragraph');
      setLineType('paragraph');
      sync();
      return;
    }
    document.execCommand('insertParagraph');
    const next = editorLine(editor, selection.anchorNode);
    if (next && next !== line) {
      setDescriptionLine(next, type);
      setLineType(type);
    }
    sync();
  };

  return <div className="rich-description-field">
    <div className="item-field-label" id={labelId}>Description</div>
    <div className="description-editor-shell">
      <div className="description-toolbar" role="group" aria-label="Description formatting">
        <div className="description-toolbar-group">{formatButtons.map(({ command, icon: Icon, label }) => <button
          key={command} type="button" aria-label={label} title={label} aria-pressed={activeFormats.includes(command)}
          onPointerDown={event => event.preventDefault()} onClick={() => format(command)}><Icon size={17} aria-hidden="true" /></button>)}</div>
        <div className="description-toolbar-group description-toolbar-list-buttons">{lineButtons.map(({ type, icon: Icon, label }) => <button
          key={type} type="button" aria-label={label} title={label} aria-pressed={lineType === type}
          onPointerDown={event => event.preventDefault()} onClick={() => changeLine(type)}><Icon size={17} aria-hidden="true" /></button>)}</div>
        <details ref={lineMenu} className="context-menu description-line-menu" onBlur={event => {
          if (event.relatedTarget && !event.currentTarget.contains(event.relatedTarget)) event.currentTarget.open = false;
        }} onKeyDown={event => {
          if (event.key === 'Escape' && event.currentTarget.open) {
            event.preventDefault();
            event.stopPropagation();
            event.currentTarget.open = false;
            event.currentTarget.querySelector('summary')?.focus();
          }
        }}>
          <summary aria-label="List formatting" title="List formatting"><List size={17} aria-hidden="true" /><ChevronDown size={12} aria-hidden="true" /></summary>
          <div className="menu-panel">{lineButtons.map(({ type, icon: Icon, label }) => <button
            key={type} type="button" aria-pressed={lineType === type} onPointerDown={event => event.preventDefault()}
            onClick={() => changeLine(type)}><Icon size={16} aria-hidden="true" />{label}</button>)}</div>
        </details>
        <div className="description-toolbar-group">
          <button type="button" ref={linkButton} aria-label="Insert link" title="Insert link" aria-expanded={Boolean(link)}
            onPointerDown={event => event.preventDefault()} onClick={() => link ? (setLink(null), setError('')) : openLink()}><Link2 size={17} aria-hidden="true" /></button>
          <button type="button" aria-label="Insert image" title="Insert image" disabled={preparing} onClick={() => openImagePicker('inline')}><ImagePlus size={17} aria-hidden="true" /></button>
          <button type="button" ref={attachButton} aria-label="Attach image" title="Attach image" disabled={preparing} onClick={() => openImagePicker('attachment')}><Paperclip size={17} aria-hidden="true" /></button>
        </div>
        <input type="file" accept="image/jpeg,image/png,image/webp" aria-label="Choose an image" ref={fileInput} hidden onChange={event => void insertImage(event)} />
      </div>
      {link && <div className="description-link-form" onKeyDown={event => {
        if (event.key === 'Enter' && event.target instanceof HTMLInputElement) { event.preventDefault(); insertLink(); }
        if (event.key === 'Escape') {
          event.preventDefault();
          event.stopPropagation();
          setLink(null);
          setError('');
          linkButton.current?.focus();
        }
      }}>
        <label className="field">Link to<select value={link.type} onChange={event => {
          setError('');
          setLink({ ...link, type: event.target.value === 'list' ? 'list' : 'external', target: '' });
        }}><option value="external">Web address</option><option value="list">Gather list</option></select></label>
        {link.type === 'external' ? <label className="field">Web address<input ref={element => { linkTarget.current = element; }}
          type="url" placeholder="https://example.com" value={link.target} maxLength={descriptionLimits.url}
          onChange={event => setLink({ ...link, target: event.target.value })} /></label> :
          <label className="field">List<select ref={element => { linkTarget.current = element; }} value={link.target}
            onChange={event => setLink({ ...link, target: event.target.value })}>
            <option value="">Choose a list</option>{lists.map(list => <option value={list.id} key={list.id}>{list.title}</option>)}
          </select></label>}
        <label className="field">Link text<input value={link.label} maxLength={descriptionLimits.text}
          onChange={event => setLink({ ...link, label: event.target.value })} /></label>
        <div className="description-link-actions">
          <button type="button" className="text-button" onClick={() => { setLink(null); setError(''); linkButton.current?.focus(); }}>Cancel</button>
          {link.existing && <button type="button" className="text-button" onClick={removeLink}>Remove link</button>}
          <button type="button" className="secondary" onClick={insertLink}>Apply link</button>
        </div>
      </div>}
      <div ref={root} aria-labelledby={labelId} data-detail="description" data-empty={!value.some(block => !isAttachment(block))}
        className="description-editor" contentEditable={!disabled} role="textbox" aria-multiline="true" aria-busy={preparing}
        aria-disabled={disabled} spellCheck suppressContentEditableWarning
        onInput={sync} onKeyDown={handleKey}
        onPaste={event => {
          event.preventDefault();
          document.execCommand('insertText', false, event.clipboardData.getData('text/plain'));
        }}
        onDrop={event => event.preventDefault()}
        onBlur={event => {
          if (!(event.target instanceof Element)) return;
          const menu = event.target.closest<HTMLDetailsElement>('.description-inline-menu');
          if (menu && event.relatedTarget && !menu.contains(event.relatedTarget)) menu.open = false;
        }}
        onClick={event => {
          const target = event.target instanceof Element ? event.target : null;
          if (target?.closest('a')) event.preventDefault();
          const action = target?.closest<HTMLElement>('[data-description-action]')?.dataset.descriptionAction;
          const line = editorLine(event.currentTarget, target);
          if (!line || !action) return;
          if (action === 'check') {
            setDescriptionLine(line, 'checkbox', line.dataset.checked !== 'true');
            line.querySelector<HTMLButtonElement>('button')?.focus();
          } else if (action === 'remove' || action === 'placement') {
            const image = readDescriptionImage(line);
            if (image) editImage(currentBlocks().indexOf(image), action, attachButton.current);
            return;
          }
          sync();
        }} />
      {preparing && <div className="description-state" role="status">Preparing image…</div>}
    </div>
    {value.some(isAttachment) && <div className="description-attachments">
      {value.map((block, index) => isAttachment(block) && <div className="description-attachment-edit" data-description-type="image" key={index}>
        <img src={descriptionImageUrl(block.dataUrl) || undefined} alt="" />
        <span>{block.alt || 'Image attachment'}</span>
        <details className="context-menu description-attachment-menu" onBlur={event => {
          if (event.relatedTarget && !event.currentTarget.contains(event.relatedTarget)) event.currentTarget.open = false;
        }} onKeyDown={event => {
          if (event.key === 'Escape' && event.currentTarget.open) {
            event.preventDefault();
            event.stopPropagation();
            event.currentTarget.open = false;
            event.currentTarget.querySelector('summary')?.focus();
          }
        }}>
          <summary className="icon-button" aria-label={block.alt ? `Actions for ${block.alt}` : 'Attachment actions'}>
            <MoreVertical size={19} aria-hidden="true" />
          </summary>
          <div className="menu-panel">
            <button type="button" onClick={() => editImage(index, 'placement', attachButton.current)}>Show inline</button>
            <button type="button" className="danger-text" onClick={() => editImage(index, 'remove', attachButton.current)}>Remove image</button>
          </div>
        </details>
      </div>)}
    </div>}
    {error && <p className="field-error" role="alert">{error}</p>}
  </div>;
}
