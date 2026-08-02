import type { ChatAction } from './actionContract';
import type { DetailRequestState } from '../../app/mapAppTypes';

type ChatActionsProps = {
  actions: ChatAction[];
  executedActionIds: ReadonlySet<string>;
  onExecute?: (action: ChatAction) => void;
  selectedComplexId?: number;
  detailState?: DetailRequestState;
  focusActionStatuses?: ReadonlyMap<string, 'moving' | 'failed'>;
};

export function ChatActions({
  actions,
  detailState,
  executedActionIds,
  focusActionStatuses,
  onExecute,
  selectedComplexId,
}: ChatActionsProps) {
  if (actions.length === 0) return null;
  return (
    <div aria-label="지도에서 보기" className="chatbot-actions">
      {actions.map((action) => {
        const executed = action.type === 'showNearbyCategory'
          && executedActionIds.has(action.actionId);
        const selected = action.type === 'focusComplex'
          && action.complexId === selectedComplexId;
        const focusLabel = selected
          ? detailState === 'ready'
            ? '지도에 표시됨 · 단지 상세 열림'
            : detailState === 'error'
              ? '지도에 표시됨 · 상세 다시 시도'
              : '지도에 표시됨 · 상세 여는 중'
          : action.type === 'focusComplex' && focusActionStatuses?.get(action.actionId) === 'moving'
            ? '지도로 이동 중'
            : action.type === 'focusComplex' && focusActionStatuses?.get(action.actionId) === 'failed'
              ? '지도를 이동하지 못했습니다 · 다시 시도'
              : action.label;
        return (
          <button
            aria-disabled={executed || onExecute == null}
            aria-label={action.label}
            aria-pressed={action.type === 'focusComplex'
              ? selected
              : undefined}
            className="chatbot-map-action"
            key={action.actionId}
            onClick={() => {
              if (!executed) onExecute?.(action);
            }}
            type="button"
          >
            {executed ? '지도에 표시됨' : focusLabel}
          </button>
        );
      })}
      <span aria-live="polite" className="chatbot-action-live" role="status">
        {actions.some((action) => action.type === 'focusComplex' && action.complexId === selectedComplexId)
          ? detailState === 'ready'
            ? '단지 상세가 열렸습니다.'
            : detailState === 'error'
              ? '지도 선택은 완료됐지만 상세를 열지 못했습니다.'
              : '단지 상세를 여는 중입니다.'
          : actions.some((action) => action.type === 'focusComplex'
            && focusActionStatuses?.get(action.actionId) === 'failed')
            ? '지도로 이동하지 못했습니다. 다시 시도할 수 있습니다.'
            : actions.some((action) => action.type === 'focusComplex'
              && focusActionStatuses?.get(action.actionId) === 'moving')
              ? '지도로 이동 중입니다.'
              : ''}
      </span>
    </div>
  );
}
