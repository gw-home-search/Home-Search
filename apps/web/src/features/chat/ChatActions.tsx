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
        const focusStatus = action.type === 'focusComplex'
          ? focusActionStatuses?.get(action.actionId)
          : undefined;
        if (executed) {
          return <span className="chatbot-map-status" key={action.actionId} role="status">✓ 지도에 표시됨</span>;
        }
        if (selected && detailState === 'ready') {
          return <span className="chatbot-map-status" key={action.actionId} role="status">✓ 지도와 단지 상세에 표시됨</span>;
        }
        if (selected && detailState === 'loading') {
          return <span className="chatbot-map-status" key={action.actionId} role="status">지도와 상세 여는 중</span>;
        }
        if (!selected && focusStatus === 'moving') {
          return <span className="chatbot-map-status" key={action.actionId} role="status">지도로 이동 중</span>;
        }
        const label = selected && detailState === 'error'
          ? '상세 다시 열기'
          : focusStatus === 'failed'
            ? '지도를 이동하지 못했습니다 · 다시 시도'
            : action.label;
        return (
          <span className="chatbot-map-action-row" key={action.actionId}>
            {selected && detailState === 'error'
              ? <span className="chatbot-map-status">지도에 표시됨</span>
              : null}
            <button
              aria-disabled={onExecute == null}
              aria-label={action.label}
              aria-pressed={action.type === 'focusComplex' ? selected : undefined}
              className="chatbot-map-action"
              onClick={() => onExecute?.(action)}
              type="button"
            >
              {label}
            </button>
          </span>
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
