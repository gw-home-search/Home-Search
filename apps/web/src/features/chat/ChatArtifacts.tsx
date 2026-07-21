import type { ChatArtifact, FactListArtifact } from './artifactContract';
import { ComparisonTableArtifactView } from './ComparisonTableArtifactView';

export function ChatArtifacts({ artifacts }: { artifacts: ChatArtifact[] }) {
  if (artifacts.length === 0) return null;
  return (
    <div aria-label="구조화된 답변" className="chatbot-artifacts">
      {artifacts.map((artifact) => (
        artifact.type === 'comparisonTable'
          ? <ComparisonTableArtifactView artifact={artifact} key={artifact.artifactId} />
          : <FactListArtifactView artifact={artifact} key={artifact.artifactId} />
      ))}
    </div>
  );
}

function FactListArtifactView({ artifact }: { artifact: FactListArtifact }) {
  return (
    <section className="chatbot-fact-list">
      <h4>{artifact.title}</h4>
      <dl>
        {artifact.items.map((item) => (
          <div key={`${item.label}:${item.factIds.join(':')}`}>
            <dt>{item.label}</dt>
            <dd>{item.value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
