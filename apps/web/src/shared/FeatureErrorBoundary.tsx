import {
  Component,
  type ErrorInfo,
  type ReactNode,
} from 'react';

import { RequestStateNotice } from './RequestStateNotice';
import { getUserFeedback } from './feedback/feedbackCatalog';

type FeatureErrorBoundaryProps = {
  children: ReactNode;
  feature:
    | 'account'
    | 'chatbot'
    | 'complex-detail'
    | 'exploration'
    | 'filters'
    | 'map'
    | 'my-page';
  className?: string;
};

type FeatureErrorBoundaryState = {
  failed: boolean;
};

export class FeatureErrorBoundary extends Component<
  FeatureErrorBoundaryProps,
  FeatureErrorBoundaryState
> {
  state: FeatureErrorBoundaryState = { failed: false };

  static getDerivedStateFromError(): FeatureErrorBoundaryState {
    return { failed: true };
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    if (import.meta.env.DEV) {
      console.error('Feature render failure', error, info);
      return;
    }
    console.error('Feature render failure', { feature: this.props.feature });
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <section
        aria-label="기능 표시 오류"
        className={`feature-error-boundary ${this.props.className ?? ''}`.trim()}
        data-feature={this.props.feature}
      >
        <RequestStateNotice
          state="error"
          loadingMessage=""
          emptyMessage=""
          feedback={getUserFeedback('FEATURE_RENDER_FAILED')}
          onRetry={() => this.setState({ failed: false })}
        />
      </section>
    );
  }
}
