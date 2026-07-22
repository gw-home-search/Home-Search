let ownerUserId: number | null = null;
const values = new Map<number, boolean>();

export function syncFavoriteOwner(userId: number | null): void {
  if (ownerUserId === userId) return;
  ownerUserId = userId;
  values.clear();
}

export function getCachedFavorite(complexId: number): boolean | undefined {
  return values.get(complexId);
}

export function setCachedFavorite(complexId: number, favorite: boolean): void {
  values.set(complexId, favorite);
}

export function resetFavoriteStore(): void {
  ownerUserId = null;
  values.clear();
}
