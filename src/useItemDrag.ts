import { useEffect, useRef, useState, type PointerEvent } from 'react';

type Destination = { id: string; after: boolean };
type Source = { group: string; id: string; itemIds: string[]; token: string };
type Session = {
  source: Source;
  handle: HTMLElement;
  pointerId: number;
  startX: number;
  startY: number;
  x: number;
  y: number;
  active: boolean;
  destination: Destination | null;
};

export function useItemDrag(drop: (source: Source, destination: Destination) => void) {
  const [preview, setPreview] = useState<{ source: Source; destination: Destination | null } | null>(null);
  const session = useRef<Session | null>(null);
  const frame = useRef(0);

  function targetAt(active: Session) {
    const row = document.elementFromPoint(active.x, active.y)?.closest<HTMLElement>('[data-reorder-item]');
    const id = row?.dataset.reorderItem;
    if (!row || row.dataset.reorderGroup !== active.source.group || !id || id === active.source.id) return null;
    const rect = row.getBoundingClientRect();
    return { id, after: active.y >= (rect.top + rect.bottom) / 2 };
  }

  function refresh(active: Session) {
    const destination = targetAt(active);
    if (active.destination?.id === destination?.id && active.destination?.after === destination?.after) return;
    active.destination = destination;
    setPreview({ source: active.source, destination });
  }

  function scroll() {
    const active = session.current;
    if (!active?.active) return;
    const footer = document.querySelector('.bottom-nav')?.getBoundingClientRect();
    const bottom = footer?.height ? footer.top : window.innerHeight;
    const speed = active.y < 56 ? -10 : active.y > bottom - 56 ? 10 : 0;
    if (speed) { window.scrollBy(0, speed); refresh(active); }
    frame.current = requestAnimationFrame(scroll);
  }

  function cancel() {
    const active = session.current;
    session.current = null;
    cancelAnimationFrame(frame.current);
    if (active?.handle.hasPointerCapture(active.pointerId)) active.handle.releasePointerCapture(active.pointerId);
    setPreview(null);
  }

  useEffect(() => () => cancelAnimationFrame(frame.current), []);

  return {
    preview,
    cancel,
    start(event: PointerEvent<HTMLElement>, source: Source) {
      if (event.button !== 0 || session.current || source.itemIds.length < 2) return;
      event.preventDefault();
      event.currentTarget.setPointerCapture(event.pointerId);
      session.current = {
        source, handle: event.currentTarget, pointerId: event.pointerId, active: false,
        startX: event.clientX, startY: event.clientY, x: event.clientX, y: event.clientY, destination: null,
      };
    },
    move(event: PointerEvent<HTMLElement>) {
      const active = session.current;
      if (!active || active.pointerId !== event.pointerId) return;
      active.x = event.clientX;
      active.y = event.clientY;
      if (!active.active) {
        if (Math.hypot(active.x - active.startX, active.y - active.startY) < 5) return;
        active.active = true;
        setPreview({ source: active.source, destination: null });
        frame.current = requestAnimationFrame(scroll);
      }
      refresh(active);
    },
    finish(event: PointerEvent<HTMLElement>) {
      const active = session.current;
      if (!active || active.pointerId !== event.pointerId) return;
      active.x = event.clientX;
      active.y = event.clientY;
      const destination = active.active ? targetAt(active) : null;
      cancel();
      if (destination) drop(active.source, destination);
    },
  };
}
