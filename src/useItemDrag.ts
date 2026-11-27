import { useEffect, useRef, useState, type PointerEvent } from 'react';

type Destination = { id: string; after: boolean };
type Source<T> = { group: string; id: string; itemIds: string[]; token: T };
type Session<T> = {
  source: Source<T>;
  handle: HTMLElement;
  scrollArea: HTMLElement | null;
  pointerId: number;
  startX: number;
  startY: number;
  x: number;
  y: number;
  active: boolean;
  destination: Destination | null;
};

export function useItemDrag<T>(drop: (source: Source<T>, destination: Destination) => void) {
  const [preview, setPreview] = useState<{ source: Source<T>; destination: Destination | null } | null>(null);
  const session = useRef<Session<T> | null>(null);
  const frame = useRef(0);

  function targetAt(active: Session<T>) {
    const row = document.elementFromPoint(active.x, active.y)?.closest<HTMLElement>('[data-reorder-item]');
    const id = row?.dataset.reorderItem;
    if (!row || row.dataset.reorderGroup !== active.source.group || !id || id === active.source.id) return null;
    const rect = row.getBoundingClientRect();
    return { id, after: active.y >= (rect.top + rect.bottom) / 2 };
  }

  function refresh(active: Session<T>) {
    const destination = targetAt(active);
    if (active.destination?.id === destination?.id && active.destination?.after === destination?.after) return;
    active.destination = destination;
    setPreview({ source: active.source, destination });
  }

  function scroll() {
    const active = session.current;
    if (!active?.active) return;
    const bounds = active.scrollArea?.getBoundingClientRect();
    const footer = bounds ? null : document.querySelector('.bottom-nav')?.getBoundingClientRect();
    const top = bounds?.top ?? 0;
    const bottom = bounds?.bottom ?? (footer?.height ? footer.top : window.innerHeight);
    const edge = Math.min(56, (bottom - top) / 4);
    const speed = active.y < top + edge ? -10 : active.y > bottom - edge ? 10 : 0;
    if (speed) { (active.scrollArea || window).scrollBy(0, speed); refresh(active); }
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
    start(event: PointerEvent<HTMLElement>, source: Source<T>) {
      if (event.button !== 0 || session.current || source.itemIds.length < 2) return;
      if (event.pointerType !== 'touch') event.preventDefault();
      event.currentTarget.setPointerCapture(event.pointerId);
      session.current = {
        source, handle: event.currentTarget, scrollArea: event.currentTarget.closest<HTMLElement>('[data-drag-scroll]'),
        pointerId: event.pointerId, active: false,
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
