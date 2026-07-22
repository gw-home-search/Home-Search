import type { ChatAction } from './actionContract';

type ChatActionsProps = {
  actions: ChatAction[];
  executedActionIds: ReadonlySet<string>;
  onExecute?: (action: ChatAction) => void;
};

export function ChatActions({ actions, executedActionIds, onExecute }: ChatActionsProps) {
  if (actions.length === 0) return null;
  return (
    <div aria-label="지도에서 보기" className="chatbot-actions">
      {actions.map((action) => {
        const executed = executedActionIds.has(action.actionId);
        return (
          <button
            aria-disabled={executed || onExecute == null}
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
