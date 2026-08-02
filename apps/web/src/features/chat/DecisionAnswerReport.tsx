import type { ChatMessage } from './storage/chatConversationStore';
import { ChatArtifacts } from './ChatArtifacts';
import type { ChatAction } from './actionContract';
import { ChatActions } from './ChatActions';
import { FollowUpPrompts } from './FollowUpPrompts';
import type { DetailRequestState } from '../../app/mapAppTypes';
import { isDataNote, isWarningLimitation } from './limitationPresentation';

export function DecisionAnswerReport({
  actions,
  executedActionIds,
  headerActions,
  limitations,
  message,
  onAction,
  onFollowUp,
  selectedComplexId,
  detailState,
  focusActionStatuses,
}: {
  actions: ChatAction[];
  executedActionIds: ReadonlySet<string>;
  headerActions: ChatAction[];
  limitations: string[];
  message: ChatMessage;
  onAction?: (action: ChatAction) => void;
  onFollowUp?: (question: string) => void;
  selectedComplexId?: number;
  detailState?: DetailRequestState;
  focusActionStatuses?: ReadonlyMap<string, 'moving' | 'failed'>;
}) {
  const report = message.report;
  if (report == null) return null;
  const artifacts = message.artifacts ?? [];
  const primary = artifacts.filter(({ artifactId }) => artifactId === report.primaryArtifactId);
  const artifactById = new Map(artifacts.map((artifact) => [artifact.artifactId, artifact]));
  const details = report.detailArtifactIds.flatMap((artifactId) => {
    const artifact = artifactById.get(artifactId);
    return artifact == null ? [] : [artifact];
  });
  const warnings = limitations.filter(isWarningLimitation);
  const dataNotes = limitations.filter((item) => isDataNote(item) && !isWarningLimitation(item));
  return (
    <div className="chatbot-decision-report" data-report-kind={report.kind}>
      <h3>{report.opening.text}</h3>
      <ChatActions
        actions={headerActions}
        executedActionIds={executedActionIds}
        onExecute={onAction}
        selectedComplexId={selectedComplexId}
        detailState={detailState}
        focusActionStatuses={focusActionStatuses}
      />
      {primary.length > 0 ? <ChatArtifacts actions={actions} artifacts={primary} onAction={onAction} selectedComplexId={selectedComplexId} /> : null}
      {report.basis.length > 0 ? (
        <section className="chatbot-report-basis">
          <h4>조회 조건</h4>
          <ul>{report.basis.map((item) => <li key={item.text}>{item.text}</li>)}</ul>
        </section>
      ) : null}
      {(message.summary?.interpretations.length ?? 0) > 0 ? (
        <section className="chatbot-summary-interpretations">
          <h4>핵심값</h4>
          {message.summary?.interpretations.slice(0, 2).map((item) => (
            <div key={item.key}><strong>{item.label}</strong><p>{item.text}</p></div>
          ))}
        </section>
      ) : null}
      {message.summary?.criteria.find(({ key }) => key === 'representativeSelection') ? (
        <details className="chatbot-selection-basis">
          <summary>단지 선택 기준</summary>
          <p>{message.summary.criteria.find(({ key }) => key === 'representativeSelection')?.value}</p>
        </details>
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
      {dataNotes.length > 0 ? (
        <section className="chatbot-summary-limitations">
          <h4>데이터 참고</h4>
          {dataNotes.map((limitation) => <p key={limitation}>{limitation}</p>)}
        </section>
      ) : null}
      {warnings.length > 0 ? (
        <section className="chatbot-summary-limitations">
          <h4>확인하지 못한 정보</h4>
          {warnings.map((limitation) => <p key={limitation}>{limitation}</p>)}
        </section>
      ) : null}
      {message.summary?.followUp ? (
        <FollowUpPrompts onSelect={onFollowUp} value={message.summary.followUp} />
      ) : null}
    </div>
  );
}

function detailHeading(kind: NonNullable<ChatMessage['report']>['kind']): string {
  if (kind === 'RECOMMENDATION') return '다른 추천 후보';
  if (kind === 'COMPARISON') return '추가로 확인된 후보';
  return '다른 후보 단지';
}
