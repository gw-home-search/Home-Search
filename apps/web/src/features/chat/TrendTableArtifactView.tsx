import type { TrendTableArtifact } from './artifactContract';

export function TrendTableArtifactView({ artifact }: { artifact: TrendTableArtifact }) {
  return (
    <section className="chatbot-data-table chatbot-trend-table">
      <h4>{artifact.title}</h4>
      <div className="chatbot-table-scroll" tabIndex={0}>
        <table>
          <thead><tr><th>월</th><th>평균</th><th>최저–최고</th><th>거래 수</th></tr></thead>
          <tbody>{artifact.rows.map((row) => (
            <tr key={row.month}>
              <td>{row.month}</td>
              {row.availability === 'available' ? (
                <>
                  <td>{formatAmount(row.averageAmountTenThousandKrw!)}</td>
                  <td>{formatAmount(row.minimumAmountTenThousandKrw!)}–{formatAmount(row.maximumAmountTenThousandKrw!)}</td>
                  <td>{row.tradeCount}건</td>
                </>
              ) : <td colSpan={3}>{row.reason}</td>}
            </tr>
          ))}</tbody>
        </table>
      </div>
    </section>
  );
}

function formatAmount(amount: number): string {
  return `${amount.toLocaleString('ko-KR')}만원`;
}
