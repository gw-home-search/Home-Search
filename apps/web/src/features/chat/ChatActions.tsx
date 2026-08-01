import type { ChatAction } from './actionContract';

type ChatActionsProps = {
  actions: ChatAction[];
  executedActionIds: ReadonlySet<string>;
  onExecute?: (action: ChatAction) => void;
  selectedComplexId?: number;
};

export function ChatActions({ actions, executedActionIds, onExecute, selectedComplexId }: ChatActionsProps) {
  if (actions.length === 0) return null;
  return (
    <div aria-label="지도에서 보기" className="chatbot-actions">
      {actions.map((action) => {
        const executed = action.type === 'showNearbyCategory'
          && executedActionIds.has(action.actionId);
        return (
          <button
            aria-disabled={executed || onExecute == null}
            aria-label={action.label}
            aria-pressed={action.type === 'focusComplex'
              ? action.complexId === selectedComplexId
              : undefined}
            className="chatbot-map-action"
            key={action.actionId}
            onClick={() => {
              if (!executed) onExecute?.(action);
            }}
            type="button"
          >
            {executed ? '지도에 표시됨' : action.label}
          </button>
        );
      })}
    </div>
  );
}
