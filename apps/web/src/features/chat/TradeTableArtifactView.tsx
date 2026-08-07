import type { TradeTableArtifact } from './artifactContract';
import { formatTenThousandKrw } from './amountFormat';

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
              <td>{formatTenThousandKrw(row.amountTenThousandKrw)}</td>
              <td>{row.floor == null ? '확인 불가' : `${row.floor}층`}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
    </section>
  );
}
