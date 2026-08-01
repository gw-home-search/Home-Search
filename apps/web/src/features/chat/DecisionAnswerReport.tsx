import type { ChatMessage } from './storage/chatConversationStore';
import { ChatArtifacts } from './ChatArtifacts';
import type { ChatAction } from './actionContract';
import { ChatActions } from './ChatActions';

export function DecisionAnswerReport({
  actions,
  executedActionIds,
  headerActions,
  limitations,
  message,
  onAction,
  selectedComplexId,
}: {
  actions: ChatAction[];
  executedActionIds: ReadonlySet<string>;
  headerActions: ChatAction[];
  limitations: string[];
  message: ChatMessage;
  onAction?: (action: ChatAction) => void;
  selectedComplexId?: number;
}) {
  const report = message.report;
  if (report == null) return null;
  const artifacts = message.artifacts ?? [];
  const primary = artifacts.filter(({ artifactId }) => artifactId === report.primaryArtifactId);
  const detailIds = new Set(report.detailArtifactIds);
  const details = artifacts.filter(({ artifactId }) => detailIds.has(artifactId));
  return (
    <div className="chatbot-decision-report" data-report-kind={report.kind}>
      <h3>{report.opening.text}</h3>
      <ChatActions
        actions={headerActions}
        executedActionIds={executedActionIds}
        onExecute={onAction}
        selectedComplexId={selectedComplexId}
      />
      {primary.length > 0 ? <ChatArtifacts actions={actions} artifacts={primary} onAction={onAction} selectedComplexId={selectedComplexId} /> : null}
      {report.basis.length > 0 ? (
        <section className="chatbot-report-basis">
          <h4>적용 기준</h4>
          <ul>{report.basis.map((item) => <li key={item.text}>{item.text}</li>)}</ul>
        </section>
      ) : null}
      {report.highlights.length > 0 ? (
        <section className="chatbot-report-highlights">
          <h4>먼저 볼 후보</h4>
          <div>{report.highlights.map((highlight, index) => (
            <article key={highlight.complexId}>
              <span>{index + 1}</span>
              <div><strong>{highlight.title}</strong><p>{highlight.body}</p></div>
            </article>
          ))}</div>
        </section>
      ) : null}
      {details.length > 0 ? (
        <section className="chatbot-report-details">
          <h4>{detailHeading(report.kind)}</h4>
          <ChatArtifacts actions={actions} artifacts={details} onAction={onAction} selectedComplexId={selectedComplexId} />
        </section>
      ) : null}
      {limitations.length > 0 ? (
        <section className="chatbot-summary-limitations">
          <h4>확인할 점</h4>
          {limitations.map((limitation) => <p key={limitation}>{limitation}</p>)}
        </section>
      ) : null}
      {message.summary?.followUp ? (
        <p className="chatbot-summary-follow-up">{message.summary.followUp}</p>
      ) : null}
    </div>
  );
}

function detailHeading(kind: NonNullable<ChatMessage['report']>['kind']): string {
  if (kind === 'RECOMMENDATION') return '다른 추천 후보';
  if (kind === 'COMPARISON') return '추가로 확인된 후보';
  return '다른 후보 단지';
}
