export type Color = 'sage' | 'peach' | 'lavender' | 'sky' | 'sand' | 'rose';
export interface Category { id: string; name: string; color: Color; position: number }
export interface Section { id: string; title: string; position: number }
export type PlaceKind = 'city' | 'store' | 'home' | 'work' | 'online' | 'other';
export interface PlaceFields { name: string; kind: PlaceKind; region: string; countryCode: string }
export interface Place extends PlaceFields { id: string }
export interface Recipient { id: string; name: string }
export interface Availability { mode: 'unassigned' | 'anywhere' | 'selected'; placeIds: string[] }
export interface Item { id: string; text: string; quantity: string; note: string; checked: boolean; sectionId: string | null; position: number; availability?: Availability; recipientId?: string | null; recipientIds?: string[] }
export type ListKind = 'checklist' | 'notes';
export type RepeatCadence = 'manual' | 'daily' | 'weekly';
export interface GatherList { id: string; title: string; description: string; kind: ListKind; repeatCadence: RepeatCadence; icon: string; color: Color; categoryId: string | null; favorite: boolean; archived: boolean; isTemplate: boolean; isSample: boolean; createdAt: string; updatedAt: string; position: number; sections: Section[]; items: Item[]; role?: 'owner' | 'editor'; ownerUsername?: string; shared?: boolean; numbered?: boolean; orderToken?: string; personGroupOrder?: string[]; placeGroupOrder?: string[]; places?: Place[]; recipients?: Recipient[]; coverId?: string }
export interface Workspace { version: 1 | 2 | 3 | 4; categories: Category[]; lists: GatherList[]; createdListId?: string; joinedListId?: string; createdItemId?: string }
export interface ListSharing { owner: boolean; ownerUsername: string; role: 'owner' | 'editor'; members: { userId: string; username: string }[]; inviteActive: boolean; inviteExpiresAt: string | null; token?: string }
export interface InvitationPreview { listId: string; listTitle: string; ownerUsername: string; expiresAt: string; alreadyMember: boolean }
export type Payload = Record<string, unknown>;
export type Mutation = (type: string, payload: Payload) => Promise<Workspace | null>;
export interface SearchMatch { field: string; text: string; itemId?: string; sectionId?: string }
export interface SearchResult { listId: string; score: number; match: SearchMatch; matchType: 'exact' | 'fuzzy' | 'semantic' }
export interface SearchResponse { results: SearchResult[]; provider: 'local' | 'fuzzy' | 'openai'; status: 'ready' | 'warming' | 'degraded'; reason?: string; partial?: boolean }
export interface SearchStatus { provider: 'local' | 'fuzzy' | 'openai'; model: string | null; state: 'ready' | 'warming' | 'degraded'; reason?: string }
