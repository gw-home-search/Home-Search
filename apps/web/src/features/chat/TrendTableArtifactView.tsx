import type { TrendTableArtifact } from './artifactContract';
import { formatTenThousandKrw } from './amountFormat';

export function TrendTableArtifactView({ artifact }: { artifact: TrendTableArtifact }) {
  const observedRows = artifact.rows.filter((row) => row.availability === 'available');
  const latest = [...observedRows].sort((left, right) => right.month.localeCompare(left.month))[0];
  const tradeCount = observedRows.reduce((sum, row) => sum + (row.tradeCount ?? 0), 0);
  return (
    <section className="chatbot-data-table chatbot-trend-table">
      <h4>{artifact.title}</h4>
      <dl className="chatbot-trend-metrics">
        <div><dt>관찰된 달</dt><dd>{observedRows.length}개월</dd></div>
        <div><dt>거래</dt><dd>{tradeCount}건</dd></div>
        <div>
          <dt>최근 관찰</dt>
          <dd>{latest == null
            ? '확인 불가'
            : `${latest.month} · ${formatTenThousandKrw(latest.averageAmountTenThousandKrw!)} · ${latest.tradeCount ?? 0}건`}</dd>
        </div>
      </dl>
      <div className="chatbot-table-scroll" tabIndex={0}>
        <table>
          <caption>{artifact.title} 조회 결과</caption>
          <thead><tr><th>월</th><th>평균</th><th>최저–최고</th><th>거래 수</th></tr></thead>
          <tbody>{artifact.rows.map((row) => (
            <tr key={row.month}>
              <td>{row.month}</td>
              {row.availability === 'available' ? (
                <>
                  <td>{formatTenThousandKrw(row.averageAmountTenThousandKrw!)}</td>
                  <td>{formatTenThousandKrw(row.minimumAmountTenThousandKrw!)}–{formatTenThousandKrw(row.maximumAmountTenThousandKrw!)}</td>
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
