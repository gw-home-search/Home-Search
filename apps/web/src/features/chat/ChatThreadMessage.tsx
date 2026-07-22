import type { ChatAction } from './actionContract';
import { ChatMessageBody } from './ChatMessageBody';
import type { ChatMessage } from './storage/chatConversationStore';

type ChatThreadMessageProps = {
  executedActionIds?: ReadonlySet<string>;
  message: ChatMessage;
  onUiAction?: (action: ChatAction) => void;
};

export function ChatThreadMessage({
  executedActionIds,
  message,
  onUiAction,
}: ChatThreadMessageProps) {
  const isUser = message.role === 'user';
  return (
    <article
      aria-label={isUser ? '내 질문' : '홈서치 AI 답변'}
      className={`chatbot-message chatbot-message-${message.role}`}
    >
      {isUser ? (
        <p>{message.content}</p>
      ) : (
        <ChatMessageBody
          executedActionIds={executedActionIds}
          message={message}
          onUiAction={onUiAction}
        />
      )}
    </article>
  );
}

export function ChatPendingMessage() {
  return (
    <div aria-live="polite" className="chatbot-pending" role="status">
      <span aria-hidden="true" />
      데이터를 확인하고 있어요
    </div>
  );
}
