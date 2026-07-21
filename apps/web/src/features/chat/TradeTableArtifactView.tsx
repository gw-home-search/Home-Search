import type { TradeTableArtifact } from './artifactContract';

export function TradeTableArtifactView({ artifact }: { artifact: TradeTableArtifact }) {
  return (
    <section className="chatbot-data-table chatbot-trade-table">
      <h4>{artifact.title}</h4>
      <div className="chatbot-table-scroll" tabIndex={0}>
        <table>
          <thead><tr><th>거래일</th><th>전용면적</th><th>금액</th><th>층</th></tr></thead>
          <tbody>{artifact.rows.map((row) => (
            <tr key={row.tradeId}>
              <td>{row.dealDate}</td>
              <td>{row.exclusiveAreaSquareMeters}㎡</td>
              <td>{formatAmount(row.amountTenThousandKrw)}</td>
              <td>{row.floor == null ? '확인 불가' : `${row.floor}층`}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
    </section>
  );
}

function formatAmount(amount: number): string {
  const eok = Math.floor(amount / 10_000);
  const remainder = amount % 10_000;
  if (eok > 0 && remainder > 0) return `${eok.toLocaleString('ko-KR')}억 ${remainder.toLocaleString('ko-KR')}만원`;
  if (eok > 0) return `${eok.toLocaleString('ko-KR')}억원`;
  return `${remainder.toLocaleString('ko-KR')}만원`;
}
