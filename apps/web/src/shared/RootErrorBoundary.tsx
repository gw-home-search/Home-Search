import {
  Component,
  type ErrorInfo,
  type ReactNode,
} from 'react';

import { RequestStateNotice } from './RequestStateNotice';
import { getUserFeedback } from './feedback/feedbackCatalog';

type RootErrorBoundaryProps = {
  children: ReactNode;
  onReload?: () => void;
};

type RootErrorBoundaryState = {
  failed: boolean;
};

export class RootErrorBoundary extends Component<
  RootErrorBoundaryProps,
  RootErrorBoundaryState
> {
  state: RootErrorBoundaryState = { failed: false };

  static getDerivedStateFromError(): RootErrorBoundaryState {
    return { failed: true };
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    if (import.meta.env.DEV) {
      console.error('Root render failure', error, info);
      return;
    }
    console.error('Root render failure', { scope: 'app-root' });
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <main aria-label="홈서치 표시 오류" className="root-error-boundary">
        <RequestStateNotice
          state="error"
          loadingMessage=""
          emptyMessage=""
          feedback={getUserFeedback('APP_RENDER_FAILED')}
          onRetry={this.props.onReload ?? reloadPage}
        />
      </main>
    );
  }
}

function reloadPage() {
  window.location.reload();
}
