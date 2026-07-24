import { useEffect, useRef } from 'react';

import { CloseIcon } from '../../shared/icons';
import { SocialProviderIcon } from './SocialProviderIcon';
import type { OAuthProvider } from './authTypes';
import {
  getUserFeedback,
  type UserFeedbackId,
} from '../../shared/feedback/feedbackCatalog';

type AuthDialogProps = {
  connectingProvider: OAuthProvider | null;
  error: UserFeedbackId | null;
  isOpen: boolean;
  onClose: () => void;
  onProviderSelect: (provider: OAuthProvider) => void;
  onRetry: () => void;
};

const PROVIDERS: ReadonlyArray<{ id: OAuthProvider; label: string }> = [
  { id: 'kakao', label: '카카오로 계속하기' },
  { id: 'naver', label: '네이버로 계속하기' },
  { id: 'google', label: 'Google로 계속하기' },
];

export function AuthDialog({
  connectingProvider,
  error,
  isOpen,
  onClose,
  onProviderSelect,
  onRetry,
}: AuthDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const firstProviderRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog == null) return;
    if (isOpen) {
      if (!dialog.open) {
        if (typeof dialog.showModal === 'function') dialog.showModal();
        else dialog.setAttribute('open', '');
      }
      queueMicrotask(() => firstProviderRef.current?.focus());
      return;
    }
    if (dialog.open) {
      if (typeof dialog.close === 'function') dialog.close();
      else dialog.removeAttribute('open');
    }
  }, [isOpen]);

  return (
    <dialog
      aria-describedby="auth-dialog-description auth-dialog-auto-signup"
      aria-labelledby="auth-dialog-title"
      className="auth-dialog"
      data-ui-component="auth-dialog"
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
      ref={dialogRef}
    >
      <div className="auth-dialog-card">
        <div className="auth-dialog-brand-row">
          <div className="auth-dialog-brand">
            <img alt="" aria-hidden="true" height="36" src="/home-search-logo.png" width="36" />
            <span className="auth-dialog-brand-copy"><strong>홈서치</strong><span>계정</span></span>
          </div>
          <button aria-label="로그인 창 닫기" className="auth-dialog-close" onClick={onClose} type="button">
            <CloseIcon aria-hidden="true" />
          </button>
        </div>
        <div className="auth-dialog-heading">
          <h2 id="auth-dialog-title">로그인 / 회원가입</h2>
          <p id="auth-dialog-description">소셜 계정으로 홈서치를 이용하세요.</p>
          <p id="auth-dialog-auto-signup">처음 방문한 경우 계정이 자동으로 생성됩니다.</p>
        </div>

        {error != null ? <AuthFeedback feedbackId={error} onRetry={onRetry} /> : null}

        <div className="auth-provider-list">
          {PROVIDERS.map((provider, index) => (
            <button
              className={`auth-provider-button auth-provider-${provider.id}`}
              data-auth-provider={provider.id}
              aria-busy={connectingProvider === provider.id}
              disabled={connectingProvider != null}
              key={provider.id}
              onClick={() => onProviderSelect(provider.id)}
              ref={index === 0 ? firstProviderRef : undefined}
              type="button"
            >
              <SocialProviderIcon provider={provider.id} />
              <span className="auth-provider-label">{connectingProvider === provider.id ? '연결 중...' : provider.label}</span>
            </button>
          ))}
        </div>
        <p className="auth-dialog-security">소셜 계정의 비밀번호는 홈서치에 저장되지 않습니다.</p>
      </div>
    </dialog>
  );
}

function AuthFeedback({
  feedbackId,
  onRetry,
}: {
  feedbackId: UserFeedbackId;
  onRetry: () => void;
}) {
  const feedback = getUserFeedback(feedbackId);
  return (
    <div className="auth-dialog-error" role="alert">
      <strong>{feedback.title}</strong>
      {feedback.description ? <span>{feedback.description}</span> : null}
      {feedback.actionLabel ? (
        <button onClick={onRetry} type="button">{feedback.actionLabel}</button>
      ) : null}
    </div>
  );
}
