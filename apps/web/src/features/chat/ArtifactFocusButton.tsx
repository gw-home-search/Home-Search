import type { ChatAction } from './actionContract';
import { focusActionForFacts } from './focusActionForFacts';

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
  return (
    <button
      aria-disabled={onAction == null}
      aria-label={action.label}
      aria-pressed={action.complexId === selectedComplexId}
      className="chatbot-artifact-map-action"
      onClick={() => onAction?.(action)}
      type="button"
    >
      지도에서 보기
    </button>
  );
}
