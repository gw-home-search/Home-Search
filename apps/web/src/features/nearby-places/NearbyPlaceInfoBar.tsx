import type { NearbyPlace } from './api/fetchNearbyPlaces';
import type { ViewportNearbyPlaceState } from './useViewportNearbyPlaces';
import {
  getUserFeedback,
  type UserFeedbackId,
} from '../../shared/feedback/feedbackCatalog';

type Props = {
  error: UserFeedbackId | null;
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
  const feedbackId = state === 'partial'
    ? error ?? 'NEARBY_PARTIAL'
    : state === 'error' ? error ?? 'NEARBY_UNAVAILABLE' : null;
  if (feedbackId == null) return null;
  const feedback = getUserFeedback(feedbackId);
  return (
    <aside aria-live="polite" className="nearby-place-info-bar nearby-place-info-status">
      <span><strong>{feedback.title}</strong>{feedback.description ? ` ${feedback.description}` : ''}</span>
      {feedback.actionLabel ? <button type="button" onClick={onRetry}>{feedback.actionLabel}</button> : null}
    </aside>
  );
}
