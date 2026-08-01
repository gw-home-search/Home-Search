import type { RecommendationCardsArtifact } from './artifactContract';
import type { ChatAction } from './actionContract';
import { ArtifactFocusButton } from './ArtifactFocusButton';

export function RecommendationCardsArtifactView({
  artifact,
  actions = [],
  onAction,
  selectedComplexId,
}: {
  artifact: RecommendationCardsArtifact;
  actions?: ChatAction[];
  onAction?: (action: ChatAction) => void;
  selectedComplexId?: number;
}) {
  return (
    <section className="chatbot-recommendation-cards">
      <h4>{artifact.title}</h4>
      <div className="chatbot-recommendation-list">
        {artifact.cards.map((card) => (
          <article key={card.complexId}>
            <div className="chatbot-recommendation-heading">
              <strong>{card.rank}. {card.complexName}</strong>
              <span>조건 충족도 {formatScore(card.totalScore)}점</span>
            </div>
            <ArtifactFocusButton actions={actions} factIds={card.factIds} onAction={onAction} selectedComplexId={selectedComplexId} />
            {card.activeThemes.length > 0 && (
              <div aria-label="반영한 생활조건" className="chatbot-recommendation-themes">
                {card.activeThemes.map((theme) => <span key={theme}>{themeLabel(theme)}</span>)}
              </div>
            )}
            <dl>
              <div>
                <dt>최근 거래</dt>
                <dd>{card.latestTrade.date} · {formatKrw(card.latestTrade.amountTenThousandKrw)}</dd>
              </div>
              <div>
                <dt>최근 3건 중앙값</dt>
                <dd>{formatKrw(card.recentThreeMedian.amountTenThousandKrw)}</dd>
              </div>
            </dl>
            <details>
              <summary>점수 근거</summary>
              <ul>
                {card.scoreBreakdown.map((item) => (
                  <li key={item.key}>
                    <div>
                      <span>{item.label}</span>
                      {item.details?.map((detail) => <small key={detail}>{publicMetricText(detail)}</small>)}
                    </div>
                    <span>{formatScore(item.points)} / {formatScore(item.weight)}점
                      {item.distanceMeters == null ? '' : ` · ${item.distanceMeters.toLocaleString('ko-KR')}m`}</span>
                  </li>
                ))}
              </ul>
              {card.limitations.map((limitation) => <p key={limitation}>{limitation}</p>)}
            </details>
          </article>
        ))}
      </div>
    </section>
  );
}

function formatScore(value: number): string {
  return value.toLocaleString('ko-KR', { maximumFractionDigits: 1 });
}

function formatKrw(amountTenThousandKrw: number): string {
  const eok = Math.floor(amountTenThousandKrw / 10_000);
  const manWon = amountTenThousandKrw % 10_000;
  if (eok > 0 && manWon > 0) return `${eok.toLocaleString('ko-KR')}억 ${manWon.toLocaleString('ko-KR')}만원`;
  if (eok > 0) return `${eok.toLocaleString('ko-KR')}억원`;
  return `${manWon.toLocaleString('ko-KR')}만원`;
}

function themeLabel(theme: RecommendationCardsArtifact['cards'][number]['activeThemes'][number]) {
  return { TRANSIT: '교통', STUDENT: '학생', YOUNG_CHILD: '영유아', SHOPPING: '쇼핑' }[theme];
}

function publicMetricText(value: string): string {
  return value.replace(/Sbiz\s*교육업소/gi, '학원 위치');
}
