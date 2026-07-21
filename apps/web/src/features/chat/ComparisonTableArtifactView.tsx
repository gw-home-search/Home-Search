import type { ComparisonTableArtifact } from './artifactContract';

export function ComparisonTableArtifactView({
  artifact,
}: {
  artifact: ComparisonTableArtifact;
}) {
  return (
    <section className="chatbot-comparison-table">
      <h4>{artifact.title}</h4>
      <div className="chatbot-comparison-scroll" tabIndex={0}>
        <table>
          <thead>
            <tr>
              <th scope="col">비교 항목</th>
              {artifact.columns.map((column) => (
                <th key={column.key} scope="col">{column.label}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {artifact.rows.map((row) => (
              <tr key={row.key}>
                <th scope="row">{row.label}</th>
                {row.cells.map((cell, index) => (
                  <td data-availability={cell.availability} key={artifact.columns[index]?.key}>
                    {cell.availability === 'available'
                      ? String(cell.value)
                      : `확인 불가 · ${cell.reason}`}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="chatbot-comparison-basis">
        기준일 {artifact.basis.cutoffDate} · 최근 365일 · 전용면적{' '}
        {artifact.basis.exclusiveAreaSquareMeters}㎡ · 최근 거래 최대 3건
      </p>
    </section>
  );
}
