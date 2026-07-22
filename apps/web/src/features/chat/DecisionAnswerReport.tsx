import type { ChatMessage } from './storage/chatConversationStore';
import { ChatArtifacts } from './ChatArtifacts';

export function DecisionAnswerReport({
  limitations,
  message,
}: {
  limitations: string[];
  message: ChatMessage;
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
      {report.basis.length > 0 ? (
        <section className="chatbot-report-basis">
          <h4>적용 기준</h4>
          <ul>{report.basis.map((item) => <li key={item.text}>{item.text}</li>)}</ul>
        </section>
      ) : null}
      {primary.length > 0 ? <ChatArtifacts artifacts={primary} /> : null}
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
          <h4>후보별 상세</h4>
          <ChatArtifacts artifacts={details} />
        </section>
      ) : null}
      {limitations.length > 0 ? (
        <section className="chatbot-summary-limitations">
          <h4>확인할 점</h4>
          {limitations.map((limitation) => <p key={limitation}>{limitation}</p>)}
        </section>
      ) : null}
    </div>
  );
}
