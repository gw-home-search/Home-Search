import type { CandidateProfileArtifact } from './artifactContract';
import type { ChatAction } from './actionContract';
import { ArtifactFocusButton } from './ArtifactFocusButton';

export function CandidateProfileArtifactView({
  artifact,
  actions = [],
  onAction,
  selectedComplexId,
}: {
  artifact: CandidateProfileArtifact;
  actions?: ChatAction[];
  onAction?: (action: ChatAction) => void;
  selectedComplexId?: number;
}) {
  return (
    <details
      className="chatbot-candidate-profile"
      data-artifact-id={artifact.artifactId}
      open={artifact.rank === 1}
    >
      <summary>
        <span className="chatbot-candidate-rank">{artifact.rank}</span>
        <span>
          <strong>{artifact.title}</strong>
          <small>{artifact.reasons[0]?.text}</small>
        </span>
      </summary>
      <div className="chatbot-candidate-profile-body">
        <ArtifactFocusButton actions={actions} factIds={artifact.factIds} onAction={onAction} selectedComplexId={selectedComplexId} />
        {artifact.address || artifact.unitCount != null || artifact.useDate ? (
          <p className="chatbot-candidate-meta">
            {[artifact.address,
              artifact.unitCount == null ? null : `${artifact.unitCount.toLocaleString('ko-KR')}세대`,
              artifact.useDate == null ? null : `${artifact.useDate.slice(0, 4)}년 사용승인`,
            ].filter(Boolean).join(' · ')}
          </p>
        ) : null}
        <section>
          <h5>추천 이유</h5>
          <ul>{artifact.reasons.map((reason) => <li key={reason.text}>{reason.text}</li>)}</ul>
        </section>
        {artifact.sections.map((section) => (
          <section key={section.key}>
            <h5>{section.label}</h5>
            <dl>{section.items.map((item) => (
              <div key={`${item.label}:${item.value}`}>
                <dt>{item.label}</dt><dd>{item.value}</dd>
              </div>
            ))}</dl>
          </section>
        ))}
      </div>
    </details>
  );
}
