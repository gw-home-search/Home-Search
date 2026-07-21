import type { RecommendationCardsArtifact } from './artifactContract';

export function RecommendationCardsArtifactView({
  artifact,
}: {
  artifact: RecommendationCardsArtifact;
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
                    <span>{item.label}</span>
                    <span>
                      {formatScore(item.points)} / {formatScore(item.weight)}점
                      {item.distanceMeters == null ? '' : ` · ${item.distanceMeters.toLocaleString('ko-KR')}m`}
                    </span>
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
