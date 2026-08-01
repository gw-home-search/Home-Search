import type { FocusComplexAction } from '../features/chat/actionContract';

export function executeFocusComplexAction(
  action: FocusComplexAction,
  focusMap: (lat: number, lng: number, level: number, delta: number) => void,
  selectComplex: (selection: { parcelId: number; complexId: number }) => void,
  focusDelta: number,
): void {
  focusMap(action.center.lat, action.center.lng, action.level, focusDelta);
  selectComplex({ parcelId: action.parcelId, complexId: action.complexId });
}
