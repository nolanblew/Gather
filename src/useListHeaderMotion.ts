import { useEffect, useRef } from 'react';

const clamp = (value: number) => Math.min(1, Math.max(0, value));

export default function useListHeaderMotion() {
  const editorRef = useRef<HTMLDivElement>(null);
  const toolbarRef = useRef<HTMLDivElement>(null);
  const heroRef = useRef<HTMLElement>(null);
  const titleRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    const editor = editorRef.current;
    const toolbar = toolbarRef.current;
    const hero = heroRef.current;
    const title = titleRef.current;
    if (!editor || !toolbar || !hero || !title) return;

    const reduced = matchMedia('(prefers-reduced-motion: reduce)');
    const viewport = window.visualViewport;
    let frame = 0;
    const update = () => {
      frame = 0;
      const bar = toolbar.getBoundingClientRect();
      const cover = hero.getBoundingClientRect();
      const heading = title.getBoundingClientRect();
      const travel = Math.max(12, Math.min(heading.height, 42));
      const titleFold = clamp((bar.bottom - heading.top + 12) / travel);
      const coverFold = clamp((bar.bottom - cover.top) / Math.min(110, Math.max(36, cover.height / 2)));
      const keyboard = viewport && Math.abs(viewport.scale - 1) < .02
        ? Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop) : 0;
      editor.style.setProperty('--list-title-fold', String(reduced.matches ? Number(titleFold >= .5) : titleFold));
      editor.style.setProperty('--list-cover-fold', String(reduced.matches ? Number(coverFold >= .5) : coverFold));
      editor.style.setProperty('--list-toolbar-height', `${bar.height}px`);
      editor.style.setProperty('--list-keyboard-inset', `${keyboard}px`);
    };
    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(update);
    };
    const observer = new ResizeObserver(schedule);
    observer.observe(toolbar);
    observer.observe(hero);
    reduced.addEventListener('change', schedule);
    window.addEventListener('scroll', schedule, { passive: true });
    window.addEventListener('resize', schedule);
    viewport?.addEventListener('resize', schedule);
    viewport?.addEventListener('scroll', schedule);
    update();
    return () => {
      observer.disconnect();
      reduced.removeEventListener('change', schedule);
      window.removeEventListener('scroll', schedule);
      window.removeEventListener('resize', schedule);
      viewport?.removeEventListener('resize', schedule);
      viewport?.removeEventListener('scroll', schedule);
      cancelAnimationFrame(frame);
    };
  }, []);

  return { editorRef, toolbarRef, heroRef, titleRef };
}
