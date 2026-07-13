export type FavoritePhase =
  | 'auth-checking'
  | 'anonymous'
  | 'unavailable'
  | 'checking'
  | 'ready'
  | 'saving'
  | 'removing'
  | 'error';

export type FavoriteState = {
  phase: FavoritePhase;
  favorite: boolean | null;
};

export type FavoriteStatus = {
  complexId: number;
  favorite: boolean;
  savedAt: string | null;
};

export type FavoriteListPage = {
  content: Array<{ complexId: number; savedAt: string }>;
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};
