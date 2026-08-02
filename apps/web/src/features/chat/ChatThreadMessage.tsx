import type { Ref } from 'react';

import type { ChatAction } from './actionContract';
import { ChatMessageBody } from './ChatMessageBody';
import type { ChatMessage } from './storage/chatConversationStore';
import type { DetailRequestState } from '../../app/mapAppTypes';

type ChatThreadMessageProps = {
  executedActionIds?: ReadonlySet<string>;
  message: ChatMessage;
  messageRef?: Ref<HTMLElement>;
  onUiAction?: (action: ChatAction) => void;
  onRetry?: () => void;
  retrying?: boolean;
  selectedComplexId?: number;
  detailState?: DetailRequestState;
  onFollowUp?: (question: string) => void;
  focusActionStatuses?: ReadonlyMap<string, 'moving' | 'failed'>;
};

export function ChatThreadMessage({
  executedActionIds,
  message,
  messageRef,
  onUiAction,
  onRetry,
  retrying = false,
  selectedComplexId,
  detailState,
  onFollowUp,
  focusActionStatuses,
}: ChatThreadMessageProps) {
  const isUser = message.role === 'user';
  return (
    <article
      aria-label={isUser ? '내 질문' : '홈서치 AI 답변'}
      className={`chatbot-message chatbot-message-${message.role}`}
      ref={messageRef}
    >
      {isUser ? (
        <p>{message.content}</p>
      ) : (
        <ChatMessageBody
          executedActionIds={executedActionIds}
          message={message}
          onUiAction={onUiAction}
          onFollowUp={onFollowUp}
          selectedComplexId={selectedComplexId}
          detailState={detailState}
          focusActionStatuses={focusActionStatuses}
        />
      )}
      {!isUser && onRetry ? (
        <button
          className="chatbot-assistant-retry"
          disabled={retrying}
          onClick={onRetry}
          type="button"
        >
          {retrying ? '다시 시도 중' : '다시 시도'}
        </button>
      ) : null}
    </article>
  );
}

export function ChatPendingMessage({ message = '질문 해석' }: { message?: string }) {
  return (
    <div aria-live="polite" className="chatbot-pending" role="status">
      <span aria-hidden="true" />
      {message}
    </div>
  );
}
