import type { NearbyPlace } from './api/fetchNearbyPlaces';
import type { ViewportNearbyPlaceState } from './useViewportNearbyPlaces';

type Props = {
  error: string | null;
  place: NearbyPlace | null;
  state: ViewportNearbyPlaceState;
  onRetry: () => void;
};

export function NearbyPlaceInfoBar({ error, place, state, onRetry }: Props) {
  if (place) {
    return (
      <aside aria-label="선택 장소 정보" className="nearby-place-info-bar">
        <div>
          <strong>{place.name}</strong>
          <span>{place.categoryDetail ?? 'Kakao 장소'}</span>
          <span>{place.roadAddress ?? place.address ?? '주소 정보 없음'}{place.phone ? ` · ${place.phone}` : ''}</span>
        </div>
        {place.placeUrl ? <a href={place.placeUrl} target="_blank" rel="noreferrer">Kakao맵</a> : null}
      </aside>
    );
  }
  const message = state === 'partial'
    ? (error ?? '일부 주변시설 정보를 업데이트하지 못했습니다.')
    : state === 'error'
      ? (error ?? '주변시설 업데이트를 실패했습니다.')
      : null;
  if (!message) return null;
  return (
    <aside aria-live="polite" className="nearby-place-info-bar nearby-place-info-status">
      <span>{message}</span>
      {state === 'error' || state === 'partial' ? <button type="button" onClick={onRetry}>다시 시도</button> : null}
    </aside>
  );
}
