import { ChatArtifacts } from './ChatArtifacts';
import type { ChatEvidence } from './chatTypes';
import type { ChatMessage } from './storage/chatConversationStore';

export function ChatMessageBody({ message }: { message: ChatMessage }) {
  return (
    <>
      <p>{message.content}</p>
      {message.artifacts ? <ChatArtifacts artifacts={message.artifacts} /> : null}
      {message.evidence ? <Evidence evidence={message.evidence} /> : null}
    </>
  );
}

function Evidence({ evidence }: { evidence: ChatEvidence }) {
  return (
    <details className="chatbot-evidence">
      <summary>
        <span>답변 근거</span>
        <span>{evidence.dataAsOf ? `기준일 ${evidence.dataAsOf}` : '조회 시점 근거'}</span>
        <span>출처 {evidence.citations.length}개</span>
        <span>근거 {evidence.evidenceSummary.factCount}개</span>
      </summary>
      <div>
        <ul aria-label="답변 출처">
          {evidence.citations.map((citation) => (
            <li key={citation.citationId}>
              {citation.sourceUrl ? (
                <a href={citation.sourceUrl} rel="noreferrer noopener" target="_blank">
                  {citation.sourceName}
                </a>
              ) : citation.sourceName}
              <span>근거 등급 {citation.evidenceGrade}</span>
            </li>
          ))}
        </ul>
        {evidence.limitations.map((limitation) => <small key={limitation}>{limitation}</small>)}
      </div>
    </details>
  );
}
