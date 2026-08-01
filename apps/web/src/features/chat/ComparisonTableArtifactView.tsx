import { useMemo, useState } from 'react';

import type { ComparisonTableArtifact } from './artifactContract';
import type { ChatAction } from './actionContract';
import { ArtifactFocusButton } from './ArtifactFocusButton';

export function ComparisonTableArtifactView({
  artifact,
  actions = [],
  onAction,
  selectedComplexId,
}: {
  artifact: ComparisonTableArtifact;
  actions?: ChatAction[];
  onAction?: (action: ChatAction) => void;
  selectedComplexId?: number;
}) {
  const availableGroups = useMemo(() => GROUPS.filter(({ key }) => (
    artifact.rows.some((row) => comparisonGroup(row.key, row.group) === key)
  )), [artifact.rows]);
  const [selectedGroup, setSelectedGroup] = useState<ComparisonGroup | 'ALL'>('ALL');
  const rows = selectedGroup === 'ALL'
    ? artifact.rows
    : artifact.rows.filter((row) => comparisonGroup(row.key, row.group) === selectedGroup);
  return (
    <section className="chatbot-comparison-table">
      <h4>{artifact.title}</h4>
      {availableGroups.length > 1 ? (
        <div aria-label="비교 항목" className="chatbot-comparison-tabs" role="group">
          <button aria-pressed={selectedGroup === 'ALL'} onClick={() => setSelectedGroup('ALL')} type="button">전체</button>
          {availableGroups.map(({ key, label }) => (
            <button aria-pressed={selectedGroup === key} key={key} onClick={() => setSelectedGroup(key)} type="button">{label}</button>
          ))}
        </div>
      ) : null}
      <div className="chatbot-comparison-scroll" tabIndex={0}>
        <table>
          <thead>
            <tr>
              <th scope="col">비교 항목</th>
              {artifact.columns.map((column) => (
                <th key={column.key} scope="col">
                  {column.label}
                  <ArtifactFocusButton actions={actions} factIds={column.factIds} onAction={onAction} selectedComplexId={selectedComplexId} />
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
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
      {artifact.basis.cutoffDate && artifact.basis.exclusiveAreaSquareMeters ? (
        <p className="chatbot-comparison-basis">
          기준일 {artifact.basis.cutoffDate} · 최근 365일 · 전용면적{' '}
          {artifact.basis.exclusiveAreaSquareMeters}㎡ · 최근 거래 최대 3건
        </p>
      ) : (
        <p className="chatbot-comparison-basis">면적 조건이 없어 가격을 제외하고 확인 가능한 항목을 비교했습니다.</p>
      )}
    </section>
  );
}

type ComparisonGroup = 'PRICE' | 'SCALE' | 'TRANSPORT' | 'EDUCATION' | 'LIFESTYLE';

const GROUPS: Array<{ key: ComparisonGroup; label: string }> = [
  { key: 'PRICE', label: '가격' },
  { key: 'SCALE', label: '기본정보' },
  { key: 'TRANSPORT', label: '교통' },
  { key: 'EDUCATION', label: '교육' },
  { key: 'LIFESTYLE', label: '생활' },
];

function comparisonGroup(
  rowKey: string,
  group?: ComparisonGroup,
): ComparisonGroup {
  if (group) return group;
  if (['latestTrade', 'recentThreeMedian', 'tradeSampleCount'].includes(rowKey)) return 'PRICE';
  if (rowKey === 'nearestRail') return 'TRANSPORT';
  if (rowKey === 'studentAccess') return 'EDUCATION';
  if (['nearestRetail', 'youngChildAccess'].includes(rowKey)) return 'LIFESTYLE';
  return 'SCALE';
}
