import type { ChatArtifact, FactListArtifact } from './artifactContract';
import type { ChatAction } from './actionContract';
import { ComparisonTableArtifactView } from './ComparisonTableArtifactView';
import { RecommendationCardsArtifactView } from './RecommendationCardsArtifactView';
import { RecommendationTableArtifactView } from './RecommendationTableArtifactView';
import { TradeTableArtifactView } from './TradeTableArtifactView';
import { TrendTableArtifactView } from './TrendTableArtifactView';
import { CandidateProfileArtifactView } from './CandidateProfileArtifactView';
import { focusActionForFacts } from './focusActionForFacts';

export function ChatArtifacts({
  actions = [],
  artifacts,
  onAction,
  selectedComplexId,
  hideCandidateProfileActions = false,
}: {
  actions?: ChatAction[];
  artifacts: ChatArtifact[];
  onAction?: (action: ChatAction) => void;
  selectedComplexId?: number;
  hideCandidateProfileActions?: boolean;
}) {
  if (artifacts.length === 0) return null;
  return (
    <div aria-label="구조화된 답변" className="chatbot-artifacts">
      {artifacts.map((artifact) => (
        artifact.type === 'comparisonTable'
          ? <ComparisonTableArtifactView actions={actions} artifact={artifact} key={artifact.artifactId} onAction={onAction} selectedComplexId={selectedComplexId} />
          : artifact.type === 'recommendationCards'
            ? <RecommendationCardsArtifactView actions={actions} artifact={artifact} key={artifact.artifactId} onAction={onAction} selectedComplexId={selectedComplexId} />
            : artifact.type === 'recommendationTable'
              ? <RecommendationTableArtifactView actions={actions} artifact={artifact} key={artifact.artifactId} onAction={onAction} selectedComplexId={selectedComplexId} />
            : artifact.type === 'tradeTable'
              ? <TradeTableArtifactView artifact={artifact} key={artifact.artifactId} />
            : artifact.type === 'trendTable'
              ? <TrendTableArtifactView artifact={artifact} key={artifact.artifactId} />
              : artifact.type === 'candidateProfile'
                ? <CandidateProfileArtifactView actions={actions} artifact={artifact} hideFocusAction={hideCandidateProfileActions} key={artifact.artifactId} onAction={onAction} selectedComplexId={selectedComplexId} />
            : <FactListArtifactView
              actions={actions}
              artifact={artifact}
              key={artifact.artifactId}
              onAction={onAction}
              selectedComplexId={selectedComplexId}
            />
      ))}
    </div>
  );
}

function FactListArtifactView({
  actions,
  artifact,
  onAction,
  selectedComplexId,
}: {
  actions: ChatAction[];
  artifact: FactListArtifact;
  onAction?: (action: ChatAction) => void;
  selectedComplexId?: number;
}) {
  const content = (
    <dl>
      {artifact.items.map((item) => {
        const action = focusActionForFacts(actions, item.factIds);
        return (
          <div key={`${item.label}:${item.factIds.join(':')}`}>
            {action == null ? (
              <><dt>{item.label}</dt><dd>{item.value}</dd></>
            ) : (
              <button
                aria-disabled={onAction == null}
                aria-label={action.label}
                aria-pressed={action.complexId === selectedComplexId}
                className="chatbot-candidate-map-action"
                onClick={() => onAction?.(action)}
                type="button"
              >
                <span>{item.label}</span><small>{item.value}</small>
              </button>
            )}
          </div>
        );
      })}
    </dl>
  );
  if (artifact.artifactId === 'alternative-complexes') {
    return (
      <details className="chatbot-fact-list chatbot-alternative-complexes">
        <summary>다른 후보</summary>
        {content}
      </details>
    );
  }
  return (
    <section className="chatbot-fact-list">
      <h4>{artifact.title}</h4>
      {content}
    </section>
  );
}
