import { useRef, useState } from 'react';
import { ArrowDown, ArrowUp, GripVertical } from 'lucide-react';
import AnimatedChecklist from './AnimatedChecklist';
import Dialog from './Dialog';
import { reorderItemIds } from './itemOrder';
import { useItemDrag } from './useItemDrag';

interface Props {
  groups: { id: string; label: string }[];
  preferred: string[];
  busy: boolean;
  error: string;
  close: () => void;
  save: (groupIds: string[], expectedGroupIds: string[]) => Promise<boolean>;
}

export default function GroupOrderDialog({ groups, preferred, busy, error, close, save }: Props) {
  const [message, setMessage] = useState('');
  const area = useRef<HTMLDivElement>(null);
  const done = useRef<HTMLButtonElement>(null);
  const ids = groups.map(group => group.id);

  const move = async (current: string[], from: string, target: string, after: boolean, expected: string[]) => {
    const next = reorderItemIds(current, from, target, after);
    if (!next || busy) return;
    setMessage('');
    if (await save(next, expected)) {
      const name = groups.find(group => group.id === from)?.label;
      setMessage(`Moved ${name} to position ${next.indexOf(from) + 1} of ${next.length}.`);
    }
  };
  const drag = useItemDrag<string[]>(({ itemIds, id, token }, destination) => {
    void move(itemIds, id, destination.id, destination.after, token);
  });
  const moveWithButton = async (id: string, destination: string, direction: 'up' | 'down') => {
    await move(ids, id, destination, direction === 'down', preferred);
    requestAnimationFrame(() => {
      const row = area.current?.querySelector<HTMLElement>(`[data-reorder-item="${CSS.escape(id)}"]`);
      (row?.querySelector<HTMLElement>(`[data-direction="${direction}"]:enabled`) || row?.querySelector<HTMLElement>('button:enabled') || done.current)?.focus();
    });
  };

  return <Dialog title="Reorder groups" className="group-order-dialog" busy={busy} close={close}>
    <div onKeyDown={event => {
      if (event.key === 'Escape' && drag.preview) {
        event.preventDefault();
        event.stopPropagation();
        drag.cancel();
      }
    }}>
      <p className="dialog-copy">Drag groups or use the arrows. This order applies across the list.</p>
      {error && <div className="dialog-error" role="alert">{error}</div>}
      <div className="group-order-scroll" ref={area} data-drag-scroll="">
        <AnimatedChecklist className="group-order-content" items={preferred} count={ids.length} view={JSON.stringify(ids)}>
          <ol className="group-order-list">
            {groups.map(({ id, label }, index) => {
              const destination = drag.preview?.destination;
              const rowClass = [
                'group-order-row', drag.preview?.source.id === id && 'item-lifted',
                destination?.id === id && (destination.after ? 'drop-after' : 'drop-before'),
              ].filter(Boolean).join(' ');
              return <li key={id} className={rowClass} data-motion-row={id} data-reorder-group="group-order" data-reorder-item={id}>
                {ids.length > 1 && <span className="item-grip" aria-hidden="true"
                  onPointerDown={event => { if (!busy) drag.start(event, { group: 'group-order', id, itemIds: ids, token: preferred }); }}
                  onPointerMove={drag.move} onPointerUp={drag.finish} onPointerCancel={drag.cancel} onLostPointerCapture={drag.cancel}>
                  <GripVertical size={20} />
                </span>}
                <span className="group-order-label">{label}</span>
                {ids.length > 1 && <>
                  <button className="icon-button" type="button" aria-label={`Move ${label} up`} title="Move up" data-direction="up"
                    disabled={index === 0 || Boolean(drag.preview)} onClick={() => void moveWithButton(id, ids[index - 1], 'up')}><ArrowUp size={18} aria-hidden="true" /></button>
                  <button className="icon-button" type="button" aria-label={`Move ${label} down`} title="Move down" data-direction="down"
                    disabled={index === ids.length - 1 || Boolean(drag.preview)} onClick={() => void moveWithButton(id, ids[index + 1], 'down')}><ArrowDown size={18} aria-hidden="true" /></button>
                </>}
              </li>;
            })}
          </ol>
        </AnimatedChecklist>
      </div>
      <p className="visually-hidden" role="status">{message}</p>
      <div className="dialog-actions">
        {preferred.length > 0 && <button className="secondary" type="button" disabled={Boolean(drag.preview)} onClick={async () => {
          setMessage('');
          if (await save([], preferred)) {
            setMessage('Default group order restored.');
            requestAnimationFrame(() => done.current?.focus());
          }
        }}>Reset order</button>}
        <button className="primary" type="button" ref={done} onClick={close}>Done</button>
      </div>
    </div>
  </Dialog>;
}
