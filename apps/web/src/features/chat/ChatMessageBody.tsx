import { AnswerSources } from './AnswerSources';
import { ChatArtifacts } from './ChatArtifacts';
import { ChatActions } from './ChatActions';
import type { ChatAction } from './actionContract';
import type { ChatMessage } from './storage/chatConversationStore';
import type { ChatUiSummary } from './summaryContract';
import { DecisionAnswerReport } from './DecisionAnswerReport';

type ChatMessageBodyProps = {
  message: ChatMessage;
  executedActionIds?: ReadonlySet<string>;
  onUiAction?: (action: ChatAction) => void;
  selectedComplexId?: number;
};

export function ChatMessageBody({
  message,
  executedActionIds = EMPTY_ACTION_IDS,
  onUiAction,
  selectedComplexId,
}: ChatMessageBodyProps) {
  const supportingDetails = visibleSupportingDetails(message);
  const artifactActions = message.actions?.filter((action) => (
    action.type === 'focusComplex' && actionLinkedToArtifacts(action, message.artifacts ?? [])
  )) ?? [];
  const embeddedActionIds = new Set(artifactActions.map(({ actionId }) => actionId));
  const unplacedActions = message.actions?.filter(
    ({ actionId }) => !embeddedActionIds.has(actionId),
  ) ?? [];
  const headerActions = unplacedActions.filter(
    (action) => action.type === 'focusComplex' && action.autoRun,
  ).slice(0, 1);
  const headerActionIds = new Set(headerActions.map(({ actionId }) => actionId));
  const remainingActions = unplacedActions.filter(
    ({ actionId }) => !headerActionIds.has(actionId),
  );
  return (
    <>
      {message.report ? (
        <DecisionAnswerReport
          actions={artifactActions}
          executedActionIds={executedActionIds}
          headerActions={headerActions}
          limitations={supportingDetails.limitations}
          message={message}
          onAction={onUiAction}
          selectedComplexId={selectedComplexId}
        />
      ) : message.summary ? (
        <StructuredAnswer
          actions={artifactActions}
          executedActionIds={executedActionIds}
          headerActions={headerActions}
          limitations={supportingDetails.limitations}
          message={message}
          onAction={onUiAction}
          selectedComplexId={selectedComplexId}
          summary={message.summary}
        />
      ) : (
        <>
          <p>{message.content}</p>
          <ChatActions
            actions={headerActions}
            executedActionIds={executedActionIds}
            onExecute={onUiAction}
            selectedComplexId={selectedComplexId}
          />
          {message.artifacts ? <ChatArtifacts
            actions={artifactActions}
            artifacts={message.artifacts}
            onAction={onUiAction}
            selectedComplexId={selectedComplexId}
          /> : null}
        </>
      )}
      {remainingActions.length > 0 ? (
        <ChatActions
          actions={remainingActions}
          executedActionIds={executedActionIds}
          onExecute={onUiAction}
          selectedComplexId={selectedComplexId}
        />
      ) : null}
      {message.summary == null && message.report == null && supportingDetails.limitations.length ? (
        <section className="chatbot-answer-limitations">
          <h4>참고</h4>
          {supportingDetails.limitations.map((limitation) => <p key={limitation}>{limitation}</p>)}
        </section>
      ) : null}
      <ResolutionDetails
        hideAssumptions={message.report != null || (message.summary?.criteria.length ?? 0) > 0}
        message={message}
        omissions={supportingDetails.omissions}
      />
      <AnswerSources citations={message.evidence?.citations ?? []} />
    </>
  );
}

function StructuredAnswer({
  actions,
  executedActionIds,
  headerActions,
  limitations,
  message,
  onAction,
  selectedComplexId,
  summary,
}: {
  actions: ChatAction[];
  executedActionIds: ReadonlySet<string>;
  headerActions: ChatAction[];
  limitations: string[];
  message: ChatMessage;
  onAction?: (action: ChatAction) => void;
  selectedComplexId?: number;
  summary: ChatUiSummary;
}) {
  const hasFragmentGroups = summary.fragmentSummaries.length > 0
    && (message.fragments?.length ?? 0) > 0;
  return (
    <div className="chatbot-structured-answer">
      {summary.scopeNotice ? <p className="chatbot-scope-notice">{summary.scopeNotice.text}</p> : null}
      <h3>{summary.headline.text}</h3>
      <ChatActions
        actions={headerActions}
        executedActionIds={executedActionIds}
        onExecute={onAction}
        selectedComplexId={selectedComplexId}
      />
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
                      ? <ChatArtifacts actions={actions} artifacts={fragmentArtifacts} onAction={onAction} selectedComplexId={selectedComplexId} />
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
        ? <ChatArtifacts actions={actions} artifacts={message.artifacts} onAction={onAction} selectedComplexId={selectedComplexId} />
        : null}
      {summary.interpretations.length > 0 ? (
        <section className="chatbot-summary-interpretations">
          <h4>조건별 해석</h4>
          {summary.interpretations.map((item) => (
            <div key={item.key}><strong>{item.label}</strong><p>{item.text}</p></div>
          ))}
        </section>
      ) : null}
      {limitations.length ? (
        <section className="chatbot-summary-limitations">
          <h4>참고</h4>
          {limitations.map((limitation) => <p key={limitation}>{limitation}</p>)}
        </section>
      ) : null}
      {summary.followUp ? <p className="chatbot-summary-follow-up">{summary.followUp}</p> : null}
    </div>
  );
}

function ResolutionDetails({
  hideAssumptions,
  message,
  omissions,
}: {
  hideAssumptions: boolean;
  message: ChatMessage;
  omissions: string[];
}) {
  const assumptions = hideAssumptions
    ? []
    : message.resolution?.assumptions.slice(0, 3) ?? [];
  if (assumptions.length === 0 && omissions.length === 0) return null;
  return (
    <div className="chatbot-resolution-details">
      {assumptions.length > 0 ? (
        <section>
          <h4>적용한 기준</h4>
          {assumptions.map(({ code, text }) => <p key={code}>{text}</p>)}
        </section>
      ) : null}
      {omissions.length > 0 ? (
        <section>
          <h4>확인하지 못한 항목</h4>
          {omissions.map((omission) => <p key={omission}>{omission}</p>)}
        </section>
      ) : null}
    </div>
  );
}

function visibleSupportingDetails(message: ChatMessage): {
  limitations: string[];
  omissions: string[];
} {
  const displayed = new Set<string>();
  if (message.summary == null) {
    displayed.add(message.content.trim());
  } else {
    const summary = message.summary;
    for (const text of [
      summary.scopeNotice?.text,
      summary.headline.text,
      summary.followUp,
      ...summary.fragmentSummaries.map(({ headline }) => headline),
      ...(message.fragments?.flatMap(({ limitations }) => limitations) ?? []),
    ]) {
      if (text?.trim()) displayed.add(text.trim());
    }
  }
  if (message.report != null) {
    displayed.add(message.report.opening.text.trim());
    for (const item of message.report.basis) displayed.add(item.text.trim());
    for (const item of message.report.highlights) displayed.add(item.body.trim());
  }
  const limitations = uniqueText(message.evidence?.limitations ?? [])
    .filter((text) => !displayed.has(text))
    .filter((text) => message.report == null || isActionableLimitation(text));
  for (const limitation of limitations) displayed.add(limitation);
  const omissions = uniqueText(message.resolution?.omissions ?? [])
    .filter((text) => !displayed.has(text));
  return { limitations, omissions };
}

function isActionableLimitation(text: string): boolean {
  return /(못|없|제외|지연|불가|주의|아니|않|미만|부족|차이|대체|표본|오래|중단)/.test(text);
}

function uniqueText(values: string[]): string[] {
  return [...new Set(values.map((value) => value.trim()).filter(Boolean))];
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

function actionLinkedToArtifacts(action: ChatAction, artifacts: NonNullable<ChatMessage['artifacts']>): boolean {
  const actionFacts = new Set(action.factIds);
  const linked = (factIds: string[]) => factIds.some((factId) => actionFacts.has(factId));
  return artifacts.some((artifact) => {
    if (artifact.type === 'factList') return artifact.items.some((item) => linked(item.factIds));
    if (artifact.type === 'comparisonTable') return artifact.columns.some((item) => linked(item.factIds));
    if (artifact.type === 'recommendationCards') return artifact.cards.some((item) => linked(item.factIds));
    if (artifact.type === 'recommendationTable') return artifact.rows.some((item) => linked(item.factIds));
    if (artifact.type === 'candidateProfile') return linked(artifact.factIds);
    return false;
  });
}
