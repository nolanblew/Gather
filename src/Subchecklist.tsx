import { useId, useRef, useState } from 'react';
import { ArrowDown, ArrowUp, Check, ChevronDown, ListChecks, MoreVertical, Plus, Trash2 } from 'lucide-react';
import { subitemLimits } from './itemDetails';
import type { Subitem } from './types';

export function newSubitemId() {
  return 'step-' + Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('');
}

export function SubchecklistEditor({ value, onChange, open, onOpen }: {
  value: Subitem[];
  onChange: (steps: Subitem[]) => void;
  open: boolean;
  onOpen: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const id = useId();
  const done = value.filter(step => step.checked).length;
  const update = (id: string, fields: Partial<Subitem>) => onChange(value.map(step => step.id === id ? { ...step, ...fields } : step));
  const add = () => {
    if (value.length >= subitemLimits.count) return;
    onOpen();
    const id = newSubitemId();
    onChange([...value, { id, text: '', checked: false }]);
    requestAnimationFrame(() => ref.current?.querySelector<HTMLInputElement>(`[data-step-id="${id}"]`)?.focus());
  };
  const move = (index: number, offset: number) => {
    const target = index + offset;
    if (target < 0 || target >= value.length) return;
    const steps = value.slice();
    const [step] = steps.splice(index, 1);
    steps.splice(target, 0, step);
    onChange(steps);
  };
  const remove = (index: number) => {
    onChange(value.filter((_, entry) => index !== entry));
    requestAnimationFrame(() => {
      const inputs = ref.current?.querySelectorAll<HTMLInputElement>('[data-step-id]');
      (inputs?.[index] || inputs?.[index - 1] || ref.current?.querySelector<HTMLButtonElement>('.add-step'))?.focus();
    });
  };
  if (!open) return <button className="text-button add-subchecklist" type="button" onClick={add}><Plus size={16} aria-hidden="true" />Add subchecklist</button>;

  return <section className="subchecklist-editor" aria-labelledby={id} ref={ref}>
    <header className="subchecklist-heading">
      <div className="item-field-label" id={id}>Subchecklist</div>
      {value.length > 0 && <span className="small-muted">{done} of {value.length}</span>}
    </header>
    <div className="subchecklist-editor-rows">
      {value.map((step, index) => <div className="subchecklist-edit-row" key={step.id}>
        <label className="check-target subcheck-target">
          <input type="checkbox" checked={step.checked}
            aria-label={`${step.checked ? 'Uncheck' : 'Complete'} step ${step.text || index + 1}`}
            onChange={event => update(step.id, { checked: event.target.checked })} />
          <span className="checkbox-visual" aria-hidden="true">{step.checked && <Check size={13} />}</span>
        </label>
        <input type="text" className="subchecklist-step-input" maxLength={subitemLimits.text} data-step-id={step.id}
          aria-label={`Step ${index + 1}`} placeholder="Step name" value={step.text}
          onChange={event => update(step.id, { text: event.target.value })}
          onKeyDown={event => {
            if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
              event.preventDefault();
              if (step.text.trim()) add();
            }
          }} />
        <details className="context-menu subchecklist-step-menu" onBlur={event => {
          if (event.relatedTarget && !event.currentTarget.contains(event.relatedTarget)) event.currentTarget.open = false;
        }} onKeyDown={event => {
          if (event.key === 'Escape' && event.currentTarget.open) {
            event.preventDefault();
            event.stopPropagation();
            event.currentTarget.open = false;
            event.currentTarget.querySelector('summary')?.focus();
          }
        }} onClick={event => {
          if (event.target instanceof Element && event.target.closest('button')) {
            event.currentTarget.open = false;
            event.currentTarget.querySelector('summary')?.focus();
          }
        }}>
          <summary className="icon-button" aria-label={`Actions for step ${step.text || index + 1}`}><MoreVertical size={19} aria-hidden="true" /></summary>
          <div className="menu-panel">
            <button type="button" disabled={index === 0} onClick={() => move(index, -1)}><ArrowUp size={16} aria-hidden="true" />Move up</button>
            <button type="button" disabled={index === value.length - 1} onClick={() => move(index, 1)}><ArrowDown size={16} aria-hidden="true" />Move down</button>
            <button type="button" className="danger-text" onClick={() => remove(index)}><Trash2 size={16} aria-hidden="true" />Remove step</button>
          </div>
        </details>
      </div>)}
      <button className="text-button add-step" type="button" disabled={value.length >= subitemLimits.count} onClick={add}><Plus size={16} aria-hidden="true" />Add step</button>
    </div>
    {value.length >= subitemLimits.count && <span className="field-help">Reached the limit of 100 steps.</span>}
  </section>;
}

export function SubchecklistPreview({ itemText, value, busy, toggle, expandedBySearch }: {
  itemText: string;
  value: Subitem[];
  busy: boolean;
  toggle: (step: Subitem) => void;
  expandedBySearch: boolean;
}) {
  const id = useId();
  const [expanded, setExpanded] = useState<boolean | null>(null);
  const open = expanded ?? expandedBySearch;
  const count = value.filter(step => step.checked).length;
  return <div className="subchecklist-preview">
    <button type="button" className="subchecklist-progress" aria-expanded={open} aria-controls={id}
      aria-label={`Subchecklist for ${itemText}, ${count} of ${value.length} done`} onClick={() => setExpanded(!open)}>
      <ListChecks size={16} aria-hidden="true" />{count} of {value.length} done<ChevronDown size={15} aria-hidden="true" />
    </button>
    <ul id={id} hidden={!open} className="subchecklist-preview-rows">
      {value.map(step => <li key={step.id}>
        <label className={`subchecklist-preview-step${step.checked ? ' step-complete' : ''}`}>
          <span className="check-target subcheck-target">
            <input type="checkbox" checked={step.checked} disabled={busy}
              aria-label={`${step.checked ? 'Uncheck' : 'Complete'} step ${step.text}`} onChange={() => toggle(step)} />
            <span className="checkbox-visual" aria-hidden="true">{step.checked && <Check size={13} />}</span>
          </span>
          <span>{step.text}</span>
        </label>
      </li>)}
    </ul>
  </div>;
}
