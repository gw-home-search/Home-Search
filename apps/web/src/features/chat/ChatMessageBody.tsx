import { AnswerSources } from './AnswerSources';
import { ChatArtifacts } from './ChatArtifacts';
import { ChatActions } from './ChatActions';
import type { ChatAction } from './actionContract';
import type { ChatMessage } from './storage/chatConversationStore';
import type { ChatUiSummary } from './summaryContract';

type ChatMessageBodyProps = {
  message: ChatMessage;
  executedActionIds?: ReadonlySet<string>;
  onUiAction?: (action: ChatAction) => void;
};

export function ChatMessageBody({
  message,
  executedActionIds = EMPTY_ACTION_IDS,
  onUiAction,
}: ChatMessageBodyProps) {
  return (
    <>
      {message.summary ? (
        <StructuredAnswer message={message} summary={message.summary} />
      ) : (
        <>
          <p>{message.content}</p>
          {message.artifacts ? <ChatArtifacts artifacts={message.artifacts} /> : null}
        </>
      )}
      {message.actions ? (
        <ChatActions
          actions={message.actions}
          executedActionIds={executedActionIds}
          onExecute={onUiAction}
        />
      ) : null}
      {message.summary == null && message.evidence?.limitations.length ? (
        <section className="chatbot-answer-limitations">
          <h4>확인할 점</h4>
          {message.evidence.limitations.map((limitation) => <p key={limitation}>{limitation}</p>)}
        </section>
      ) : null}
      <AnswerSources citations={message.evidence?.citations ?? []} />
    </>
  );
}

function StructuredAnswer({
  message,
  summary,
}: {
  message: ChatMessage;
  summary: ChatUiSummary;
}) {
  const hasFragmentGroups = summary.fragmentSummaries.length > 0
    && (message.fragments?.length ?? 0) > 0;
  return (
    <div className="chatbot-structured-answer">
      {summary.scopeNotice ? <p className="chatbot-scope-notice">{summary.scopeNotice.text}</p> : null}
      <h3>{summary.headline.text}</h3>
      {summary.fragmentSummaries.length > 0 ? (
        <div aria-label="요청별 확인 결과" className="chatbot-fragment-summaries">
          {summary.fragmentSummaries.map((fragment) => (
            <section key={fragment.fragmentId}>
              <h4>{capabilityLabel(fragment.capability)}</h4>
              <p>{fragment.headline}</p>
              {(() => {
                const detail = message.fragments?.find(
                  ({ fragmentId }) => fragmentId === fragment.fragmentId,
                );
                if (detail == null) return null;
                const artifactIds = new Set(detail.artifactIds);
                const fragmentArtifacts = message.artifacts?.filter(
                  ({ artifactId }) => artifactIds.has(artifactId),
                ) ?? [];
                return (
                  <>
                    {fragmentArtifacts.length > 0
                      ? <ChatArtifacts artifacts={fragmentArtifacts} />
                      : null}
                    {detail.limitations.map((limitation) => (
                      <p className="chatbot-fragment-limitation" key={limitation}>{limitation}</p>
                    ))}
                  </>
                );
              })()}
            </section>
          ))}
        </div>
      ) : null}
      {summary.criteria.length > 0 ? (
        <section className="chatbot-summary-criteria">
          <h4>적용 조건</h4>
          <dl>{summary.criteria.map((criterion) => (
            <div key={criterion.key}><dt>{criterion.label}</dt><dd>{criterion.value}</dd></div>
          ))}</dl>
        </section>
      ) : null}
      {!hasFragmentGroups && message.artifacts
        ? <ChatArtifacts artifacts={message.artifacts} />
        : null}
      {summary.interpretations.length > 0 ? (
        <section className="chatbot-summary-interpretations">
          <h4>조건별 해석</h4>
          {summary.interpretations.map((item) => (
            <div key={item.key}><strong>{item.label}</strong><p>{item.text}</p></div>
          ))}
        </section>
      ) : null}
      {message.evidence?.limitations.length ? (
        <section className="chatbot-summary-limitations">
          <h4>확인할 점</h4>
          {message.evidence.limitations.map((limitation) => <p key={limitation}>{limitation}</p>)}
        </section>
      ) : null}
      {summary.followUp ? <p className="chatbot-summary-follow-up">{summary.followUp}</p> : null}
    </div>
  );
}

function capabilityLabel(capability: string): string {
  return {
    complex_identity: '단지 정보',
    recent_trade_lookup: '최근 실거래',
    price_trend: '가격 흐름',
    school_location: '학교 위치',
    academy_lookup: '학원 접근성',
    academy_registry_summary: '학원 등록 현황',
    rail_station_lookup: '철도역 접근성',
    retail_location: '대규모점포 접근성',
    childcare_lookup: '어린이집 위치',
    kakao_place_search: '지도 탐색',
    comparison: '단지 비교',
    recommendation: '조건 추천',
  }[capability] ?? '확인 결과';
}

const EMPTY_ACTION_IDS: ReadonlySet<string> = new Set();
