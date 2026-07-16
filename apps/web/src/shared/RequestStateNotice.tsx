import { useEffect, useRef, useState, type ReactNode } from 'react';

export type RequestNoticeState = 'idle' | 'loading' | 'ready' | 'empty' | 'error';

type RequestStateNoticeProps = {
  state: RequestNoticeState;
  loadingMessage: string;
  emptyMessage: string;
  errorMessage: string;
  secondaryMessage?: string;
  technicalError?: string | null;
  retryLabel?: string;
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
  errorMessage,
  secondaryMessage,
  technicalError,
  retryLabel = '다시 시도',
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
      ? <span className="sr-only" role="status" aria-live="polite">불러오기를 완료했습니다</span>
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

  const technical = technicalErrorDetails(technicalError);
  return (
    <div className={`request-state-notice request-state-error ${className}`.trim()} role="alert">
      <div className="request-state-copy">
        <strong>{errorMessage}</strong>
        {secondaryMessage ? <p>{secondaryMessage}</p> : null}
      </div>
      <div className="request-state-actions">
        {onRetry ? (
          <button type="button" aria-label={retryAriaLabel} disabled={retryDisabled} onClick={onRetry}>
            {retryDisabled ? '다시 불러오는 중' : retryLabel}
          </button>
        ) : null}
        {secondaryAction}
      </div>
      {technical ? (
        <details>
          <summary>오류 정보</summary>
          <dl>
            {technical.status ? <div><dt>상태</dt><dd>HTTP {technical.status}</dd></div> : null}
            {technical.detail ? <div><dt>상세</dt><dd>{technical.detail}</dd></div> : null}
          </dl>
        </details>
      ) : null}
    </div>
  );
}

function technicalErrorDetails(error: string | null | undefined): {
  status: string | null;
  detail: string | null;
} | null {
  if (!error) {
    return null;
  }

  const normalized = error.replace(/https?:\/\/\S+/giu, '[URL 숨김]').trim();
  const statusMatch = normalized.match(/\b([45]\d{2})\b/u);
  const status = statusMatch?.[1] ?? null;
  const detail = statusMatch
    ? normalized.slice((statusMatch.index ?? 0) + statusMatch[0].length).replace(/^\s*[:-]?\s*/u, '').trim()
    : null;

  if (!status && !detail) {
    return null;
  }
  return { status, detail: detail || null };
}
