import { HeartIcon } from '../../shared/icons';
import type { FavoriteState } from './favoriteTypes';
import './favorite.css';

type FavoriteToggleProps = {
  state: FavoriteState;
  liveMessage: string;
  onToggle(trigger: HTMLButtonElement): void;
};

export function FavoriteToggle({ state, liveMessage, onToggle }: FavoriteToggleProps) {
  const checking = state.phase === 'checking' || state.phase === 'auth-checking';
  const mutating = state.phase === 'saving' || state.phase === 'removing';
  const disabled = checking || mutating || (state.phase === 'error' && state.favorite == null);
  const selected = state.favorite === true;
  return (
    <>
      <button
        type="button"
        className="favorite-toggle"
        data-favorite-selected={selected ? 'true' : 'false'}
        aria-busy={checking ? 'true' : undefined}
        aria-label={favoriteLabel(state)}
        aria-pressed={selected ? 'true' : 'false'}
        disabled={disabled}
        onClick={(event) => onToggle(event.currentTarget)}
      >
        <HeartIcon aria-hidden="true" filled={selected} />
        {state.phase === 'checking' ? <span className="favorite-progress" aria-hidden="true" /> : null}
      </button>
      <span className="sr-only" aria-live="polite">{liveMessage}</span>
    </>
  );
}

function favoriteLabel(state: FavoriteState): string {
  if (state.phase === 'checking' || state.phase === 'auth-checking') return '관심 상태 확인 중';
  if (state.phase === 'anonymous' || state.phase === 'unavailable') return '로그인하고 관심 단지 저장';
  return state.favorite ? '관심 단지 해제' : '관심 단지 저장';
}
