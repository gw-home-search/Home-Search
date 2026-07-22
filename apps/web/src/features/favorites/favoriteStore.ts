let ownerUserId: number | null = null;
const values = new Map<number, boolean>();
const listeners = new Set<(change: FavoriteStoreChange) => void>();

export type FavoriteStoreChange = {
  ownerUserId: number;
  complexId: number;
  favorite: boolean;
};

export function syncFavoriteOwner(userId: number | null): void {
  if (ownerUserId === userId) return;
  ownerUserId = userId;
  values.clear();
}

export function getCachedFavorite(complexId: number): boolean | undefined {
  return values.get(complexId);
}

export function setCachedFavorite(complexId: number, favorite: boolean): void {
  if (values.get(complexId) === favorite) return;
  values.set(complexId, favorite);
  if (ownerUserId == null) return;
  const change = { ownerUserId, complexId, favorite };
  listeners.forEach((listener) => listener(change));
}

export function primeCachedFavorite(complexId: number, favorite: boolean): void {
  if (values.has(complexId)) return;
  values.set(complexId, favorite);
}

export function subscribeFavoriteStore(listener: (change: FavoriteStoreChange) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function resetFavoriteStore(): void {
  ownerUserId = null;
  values.clear();
  listeners.clear();
}
