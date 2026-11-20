import { Component, createRef, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
  items: object;
  view: string;
  count: number;
  className?: string;
}

function visibleRows(root: HTMLElement) {
  const rows = root.querySelectorAll<HTMLElement>('[data-motion-row]');
  const visible = new Map<string, { node: HTMLElement; rect: DOMRect }>();
  if (rows.length > 120) return visible;
  for (const node of rows) {
    const rect = node.getBoundingClientRect();
    if (rect.bottom > 0 && rect.top < innerHeight) visible.set(node.dataset.motionRow!, { node, rect });
  }
  return visible;
}

// Capture layout immediately before React changes it, so scrolling between edits
// never looks like row movement. All measurements precede animation writes.
export default class AnimatedChecklist extends Component<Props> {
  private root = createRef<HTMLDivElement>();
  private effects = new Set<Animation>();

  getSnapshotBeforeUpdate(previous: Props) {
    if ((previous.items === this.props.items && previous.view === this.props.view) ||
        Math.max(previous.count, this.props.count) > 120 || !Element.prototype.animate ||
        matchMedia('(prefers-reduced-motion: reduce)').matches) return null;
    return visibleRows(this.root.current!);
  }

  componentDidUpdate(_: Props, __: unknown, before: ReturnType<typeof visibleRows> | null) {
    if (!before) return;
    for (const animation of this.effects) animation.cancel();
    const after = visibleRows(this.root.current!);
    for (const [key, { node, rect }] of after) {
      const old = before.get(key)?.rect;
      const delta = old ? old.top - rect.top : 6;
      if (old && Math.abs(delta) < 1) continue;
      const travel = Math.abs(delta) < 240 ? delta : 0;
      this.play(node, [{ translate: `0 ${travel}px`, opacity: old && travel ? 1 : 0 }, { translate: '0 0', opacity: 1 }]);
    }
    // Only genuine removals get an outgoing label. The copy has no controls
    // and stays outside the list's layout and accessibility tree.
    if (after.size || this.props.count === 0) for (const [key, { node, rect }] of before) {
      if (after.has(key) || node.isConnected || node.dataset.motionItem === undefined ||
          this.root.current!.querySelector(`[data-motion-item="${CSS.escape(node.dataset.motionItem)}"]`)) continue;
      const label = document.createElement('div');
      label.textContent = node.querySelector('.item-text')?.textContent ?? '';
      label.setAttribute('aria-hidden', 'true');
      Object.assign(label.style, { position: 'fixed', pointerEvents: 'none', top: rect.top + 'px', left: rect.left + 'px', maxWidth: rect.width + 'px', overflow: 'hidden', whiteSpace: 'nowrap' });
      this.root.current!.appendChild(label);
      this.play(label, [{ opacity: .65 }, { opacity: 0, translate: '0 -4px' }], () => label.remove());
    }
  }

  private play(node: HTMLElement, frames: Keyframe[], cleanup?: () => void) {
    const animation = node.animate(frames, { duration: 160, easing: 'ease-out' });
    this.effects.add(animation);
    animation.onfinish = animation.oncancel = () => { this.effects.delete(animation); cleanup?.(); };
  }

  componentWillUnmount() { for (const animation of this.effects) animation.cancel(); }
  render() { return <div className={this.props.className ?? 'checklist'} ref={this.root}>{this.props.children}</div>; }
}
