import { useEffect, useRef, useState, type ReactNode } from 'react';

import {
  getUserFeedback,
  type UserFeedbackDefinition,
} from './feedback/feedbackCatalog';

export type RequestNoticeState = 'idle' | 'loading' | 'ready' | 'empty' | 'error';

type RequestStateNoticeProps = {
  state: RequestNoticeState;
  loadingMessage: string;
  emptyMessage: string;
  feedback?: UserFeedbackDefinition;
  retryAriaLabel?: string;
  retryDisabled?: boolean;
  onRetry?: () => void;
  secondaryAction?: ReactNode;
  className?: string;
};

const LOADING_DELAY_MILLIS = 150;

export function RequestStateNotice({
  state,
  loadingMessage,
  emptyMessage,
  feedback = getUserFeedback('UNEXPECTED_FAILURE'),
  retryAriaLabel,
  retryDisabled = false,
  onRetry,
  secondaryAction,
  className = '',
}: RequestStateNoticeProps) {
  const [showLoading, setShowLoading] = useState(false);
  const [announceReady, setAnnounceReady] = useState(false);
  const previousState = useRef(state);

  useEffect(() => {
    if (state !== 'loading') {
      setShowLoading(false);
      return undefined;
    }

    const timer = window.setTimeout(() => setShowLoading(true), LOADING_DELAY_MILLIS);
    return () => window.clearTimeout(timer);
  }, [state]);

  useEffect(() => {
    const shouldAnnounce = state === 'ready'
      && (previousState.current === 'loading' || previousState.current === 'error');
    previousState.current = state;
    if (!shouldAnnounce) return undefined;
    setAnnounceReady(true);
    const timer = window.setTimeout(() => setAnnounceReady(false), 1200);
    return () => window.clearTimeout(timer);
  }, [state]);

  if (state === 'ready') {
    return announceReady
      ? <span className="sr-only" role="status" aria-live="polite">불러오기를 완료했어요</span>
      : null;
  }

  if (state === 'idle' || (state === 'loading' && !showLoading)) {
    return null;
  }

  if (state === 'loading') {
    return (
      <p className={`request-state-notice request-state-loading ${className}`.trim()} role="status" aria-live="polite">
        <span aria-hidden="true" className="request-state-spinner" />
        {loadingMessage}
      </p>
    );
  }

  if (state === 'empty') {
    return (
      <div className={`request-state-notice request-state-empty ${className}`.trim()} role="status" aria-live="polite">
        <p>{emptyMessage}</p>
        {secondaryAction}
      </div>
    );
  }

  const role = feedback.announcement === 'alert'
    ? 'alert'
    : feedback.announcement === 'status' ? 'status' : undefined;
  return (
    <div
      aria-live={feedback.announcement === 'status' ? 'polite' : undefined}
      className={`request-state-notice request-state-${feedback.tone} ${className}`.trim()}
      role={role}
    >
      <div className="request-state-copy">
        <strong>{feedback.title}</strong>
        {feedback.description ? <p>{feedback.description}</p> : null}
      </div>
      <div className="request-state-actions">
        {onRetry && feedback.actionLabel ? (
          <button type="button" aria-label={retryAriaLabel} disabled={retryDisabled} onClick={onRetry}>
            {retryDisabled ? '다시 불러오는 중' : feedback.actionLabel}
          </button>
        ) : null}
        {secondaryAction}
      </div>
    </div>
  );
}
