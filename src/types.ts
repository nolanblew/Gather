export type Color = 'sage' | 'peach' | 'lavender' | 'sky' | 'sand' | 'rose';
export interface Category { id: string; name: string; color: Color; position: number }
export interface Section { id: string; title: string; position: number }
export interface Item { id: string; text: string; quantity: string; note: string; checked: boolean; sectionId: string | null; position: number }
export type ListKind = 'checklist' | 'notes';
export type RepeatCadence = 'manual' | 'daily' | 'weekly';
export interface GatherList { id: string; title: string; description: string; kind: ListKind; repeatCadence: RepeatCadence; icon: string; color: Color; categoryId: string | null; favorite: boolean; archived: boolean; isTemplate: boolean; isSample: boolean; createdAt: string; updatedAt: string; position: number; sections: Section[]; items: Item[]; role?: 'owner' | 'editor'; ownerUsername?: string; shared?: boolean }
export interface Workspace { version: 1 | 2; categories: Category[]; lists: GatherList[]; createdListId?: string; joinedListId?: string }
export interface ListSharing { owner: boolean; ownerUsername: string; role: 'owner' | 'editor'; members: { userId: string; username: string }[]; inviteActive: boolean; inviteExpiresAt: string | null; token?: string }
export interface InvitationPreview { listId: string; listTitle: string; ownerUsername: string; expiresAt: string; alreadyMember: boolean }
export type Payload = Record<string, unknown>;
export interface SearchMatch { field: string; text: string; itemId?: string; sectionId?: string }
export interface SearchResult { listId: string; score: number; match: SearchMatch; matchType: 'exact' | 'fuzzy' | 'semantic' }
export interface SearchResponse { results: SearchResult[]; provider: 'local' | 'fuzzy' | 'openai'; status: 'ready' | 'warming' | 'degraded'; reason?: string; partial?: boolean }
export interface SearchStatus { provider: 'local' | 'fuzzy' | 'openai'; model: string | null; state: 'ready' | 'warming' | 'degraded'; reason?: string }
