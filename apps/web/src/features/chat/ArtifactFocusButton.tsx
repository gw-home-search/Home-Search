import type { ChatAction } from './actionContract';
import { focusActionForFacts } from './focusActionForFacts';
import { MapPinIcon } from './MapPinIcon';

export function ArtifactFocusButton({
  actions,
  factIds,
  onAction,
  selectedComplexId,
}: {
  actions: ChatAction[];
  factIds: string[];
  onAction?: (action: ChatAction) => void;
  selectedComplexId?: number;
}) {
  const action = focusActionForFacts(actions, factIds);
  if (action == null) {
    return <span className="chatbot-map-unavailable">지도 위치 확인 불가</span>;
  }
  const selected = action.complexId === selectedComplexId;
  return (
    <button
      aria-disabled={onAction == null}
      aria-label={action.label}
      aria-pressed={selected}
      className="chatbot-artifact-map-action"
      onClick={() => onAction?.(action)}
      type="button"
    >
      <MapPinIcon selected={selected} />
      {selected ? '선택됨' : '지도'}
    </button>
  );
}
