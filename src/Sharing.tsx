import { useEffect, useRef, useState } from 'react';
import { Check, Copy, Link, UserRound, Users } from 'lucide-react';
import { ApiError, createInvitation, getSharing, joinInvitation, leaveList, previewInvitation, removeMember, revokeInvitation } from './api';
import { useAuth } from './auth';
import { invitationUrl } from './invitations';
import type { GatherList, InvitationPreview, ListSharing, Workspace } from './types';

export function SharingPanel({ list, setBusy, changed, left }: { list: GatherList; setBusy: (value: boolean) => void; changed: () => void; left: (workspace: Workspace) => void }) {
  const { session } = useAuth();
  const [sharing, setSharing] = useState<ListSharing | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [retry, setRetry] = useState(0);
  const [doing, setDoing] = useState('');
  const [link, setLink] = useState('');
  const [notice, setNotice] = useState('');
  const [confirmation, setConfirmation] = useState<{ userId: string; username: string } | 'leave' | null>(null);
  const linkRef = useRef<HTMLInputElement>(null);
  const generation = useRef(0);
  const acting = useRef(false);
  const generatedExpiry = useRef<string | null>(null);
  useEffect(() => {
    let active = true; setLoading(true); setError('');
    const refresh = () => {
      if (acting.current || document.hidden) return;
      const requestGeneration = ++generation.current;
      void getSharing(list.id).then(value => {
        if (!active || requestGeneration !== generation.current) return;
        setSharing(value);
        if (!value.inviteActive || (generatedExpiry.current && value.inviteExpiresAt !== generatedExpiry.current)) { if (generatedExpiry.current) setNotice(''); setLink(''); generatedExpiry.current = null; }
      }).catch(err => { if (active && requestGeneration === generation.current) { setError((err as Error).message); setSharing(null); if (err instanceof ApiError && err.status === 404) changed(); } }).finally(() => { if (active && requestGeneration === generation.current) setLoading(false); });
    };
    refresh(); window.addEventListener('focus', refresh); const timer = window.setInterval(refresh, 15000);
    return () => { active = false; ++generation.current; window.removeEventListener('focus', refresh); clearInterval(timer); };
  }, [list.id, retry]);
  const act = async (name: string, action: () => Promise<void>) => {
    if (acting.current) return;
    acting.current = true; ++generation.current;
    setDoing(name); setBusy(true); setError(''); setNotice('');
    try { await action(); changed(); } catch (err) { setError((err as Error).message); if (err instanceof ApiError && err.status === 404) changed(); } finally { acting.current = false; setDoing(''); setBusy(false); }
  };
  if (loading) return <p className="dialog-copy" role="status">Checking who has access…</p>;
  if (!sharing) return <><p className="dialog-error" role="alert">{error || 'We couldn’t load access to this list.'}</p><button className="secondary" onClick={() => setRetry(n => n + 1)}>Try again</button></>;
  return <div className="sharing-panel">
    <p className="dialog-copy">{sharing.owner ? 'Invite someone to edit this list with you.' : `You’re editing this list with ${sharing.ownerUsername}.`} Edits are shared. Favorites, categories and archiving stay personal.</p>
    {error && <p className="auth-error" role="alert">{error}</p>}
    <h3><Users size={18} aria-hidden="true" />People with access</h3>
    <ul className="sharing-people"><li><span className="person-avatar" aria-hidden="true"><UserRound size={18} /></span><span className="person-name">{sharing.ownerUsername}{session.username === sharing.ownerUsername && ' (you)'}</span><span className="badge">Owner</span></li>{sharing.members.map(member => <li key={member.userId}><span className="person-avatar" aria-hidden="true"><UserRound size={18} /></span><span className="person-name">{member.username}{session.userId === member.userId && ' (you)'}<small>Can edit</small></span>{sharing.owner && <button className="text-button danger-text" onClick={() => setConfirmation(member)} aria-label={`Remove ${member.username} from this list`}>Remove</button>}</li>)}</ul>
    {sharing.owner && <section className="invite-section"><h3><Link size={18} aria-hidden="true" />Invite one person</h3><p>Anyone with this link can sign in and join. It works once and expires in 7 days.</p>{sharing.inviteActive && <p className="invite-status">An invitation is pending.{sharing.inviteExpiresAt && ` Expires ${new Date(sharing.inviteExpiresAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}.`} A new link replaces this one.</p>}{link && <div className="invite-link"><label className="field">Invitation link<input ref={linkRef} readOnly value={link} onFocus={event => event.target.select()} /></label><button className="secondary" onClick={async () => { try { await navigator.clipboard.writeText(link); setNotice('Link copied.'); } catch { linkRef.current?.focus(); linkRef.current?.select(); setNotice('Select and copy the invitation link above.'); } }}><Copy size={16} />Copy link</button></div>}<div className="sharing-actions"><button className="primary" onClick={() => void act('invite', async () => { const value = await createInvitation(list.id); setSharing(value); generatedExpiry.current = value.inviteExpiresAt; if (value.token) { setLink(invitationUrl(value.token)); setNotice('Link ready to share.'); } })}>{doing === 'invite' ? 'Creating link…' : sharing.inviteActive ? 'Create a new link' : 'Create invite link'}</button>{sharing.inviteActive && <button className="text-button danger-text" onClick={() => void act('revoke', async () => { setSharing(await revokeInvitation(list.id)); setLink(''); setNotice('Invite link stopped. People who already joined keep access.'); })}>Stop invite link</button>}</div></section>}
    {!sharing.owner && <button className="text-button danger-text leave-list" onClick={() => setConfirmation('leave')}>Leave list</button>}
    {confirmation && <section className="access-confirmation" aria-label={confirmation === 'leave' ? 'Confirm leaving list' : 'Confirm removing person'}><h3>{confirmation === 'leave' ? 'Leave this list?' : `Remove ${confirmation.username}?`}</h3><p>{confirmation === 'leave' ? 'You’ll lose access to this list. Your changes will stay. You can make a private copy first from List actions.' : 'They’ll lose access to this list. Their changes will stay.'}</p><div className="dialog-actions"><button className="secondary" onClick={() => setConfirmation(null)}>Cancel</button><button className="danger" onClick={() => void act('remove', async () => { if (confirmation === 'leave') left(await leaveList(list.id)); else { setSharing(await removeMember(list.id, confirmation.userId)); setNotice(`${confirmation.username} no longer has access.`); setConfirmation(null); } })}>{doing === 'remove' ? 'Saving…' : confirmation === 'leave' ? 'Leave list' : 'Remove person'}</button></div></section>}
    {notice && <p className="sharing-notice" role="status"><Check size={16} aria-hidden="true" />{notice}</p>}
  </div>;
}

export function InvitationPanel({ token, setBusy, joined, cancel }: { token: string; setBusy: (value: boolean) => void; joined: (workspace: Workspace, listId: string) => void; cancel: () => void }) {
  const { session } = useAuth();
  const [preview, setPreview] = useState<InvitationPreview | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [retry, setRetry] = useState(0);
  const [joining, setJoining] = useState(false);
  useEffect(() => { let active = true; setLoading(true); setError(''); setPreview(null); void previewInvitation(token).then(value => { if (active) setPreview(value); }).catch(err => { if (active) setError((err as Error).message); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, [token, retry]);
  const join = async () => { if (joining || !preview) return; setJoining(true); setBusy(true); setError(''); try { const next = await joinInvitation(token); joined(next, next.joinedListId || preview.listId); } catch (err) { setError((err as Error).message); setPreview(null); } finally { setJoining(false); setBusy(false); } };
  if (loading) return <p className="dialog-copy" role="status">Checking your invitation…</p>;
  return <div className="invitation-panel">{error && <p className="auth-error" role="alert">{error}</p>}{preview ? <><span className="invitation-mark" aria-hidden="true"><Users size={26} /></span><h3>{preview.listTitle}</h3><p className="joining-account">Joining as <strong>{session.username}</strong></p><p className="dialog-copy">{preview.alreadyMember ? 'You already have access to this list.' : `${preview.ownerUsername} invited you to edit this list together.`}</p>{!preview.alreadyMember && <p>Everyone can add, edit and check off items. Your favorites, categories and archive stay yours.</p>}<div className="dialog-actions"><button className="secondary" onClick={cancel}>Not now</button><button className="primary" onClick={() => void join()}>{joining ? 'Opening list…' : preview.alreadyMember ? 'Open list' : 'Join list'}</button></div></> : <><p className="dialog-copy">This invitation may have expired, been used, or been stopped. Ask the list owner for a new link.</p><div className="dialog-actions"><button className="secondary" onClick={cancel}>Back to your lists</button><button className="primary" onClick={() => setRetry(n => n + 1)}>Try again</button></div></>}</div>;
}
