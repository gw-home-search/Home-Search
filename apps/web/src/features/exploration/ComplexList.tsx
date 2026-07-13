import type { Key } from 'react';

export type ComplexListItem = {
  id: Key;
  ariaLabel: string;
  name: string;
  address: string;
  approvalYear?: string;
  unitCount?: number | null;
  buildingCount?: number | null;
  onSelect: () => void;
};

type ComplexListProps = {
  ariaLabel: string;
  items: ComplexListItem[];
};

export function ComplexList({ ariaLabel, items }: ComplexListProps) {
  return (
    <ul aria-label={ariaLabel} className="panel-list complex-list" data-ui-component="complex-list">
      {items.map((item) => (
        <li key={item.id}>
          <button
            type="button"
            className="complex-list-row"
            aria-label={item.ariaLabel}
            onClick={item.onSelect}
          >
            <span className="complex-list-main">
              <span className="complex-list-name">{item.name}</span>
              <span className="complex-list-context">
                <span className="complex-list-address">{item.address}</span>
                {item.approvalYear == null ? null : (
                  <span className="complex-list-approval">· {item.approvalYear}년 승인</span>
                )}
              </span>
            </span>
            {item.unitCount == null && item.buildingCount == null ? null : (
              <span className="complex-list-stats" aria-label="단지 규모">
                {item.unitCount == null ? null : (
                  <strong className="complex-list-unit">{item.unitCount.toLocaleString()}세대</strong>
                )}
                {item.buildingCount == null ? null : (
                  <span className="complex-list-building">{item.buildingCount.toLocaleString()}동</span>
                )}
              </span>
            )}
          </button>
        </li>
      ))}
    </ul>
  );
}
