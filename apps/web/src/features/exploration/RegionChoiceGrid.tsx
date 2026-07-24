import { CheckIcon } from '../../shared/icons';
import type { RegionSummary } from '../region/api/fetchRegions';

export function RegionChoiceGrid({
  ariaLabel,
  onSelect,
  regions,
  selectedCode,
  selectionLabel,
}: {
  ariaLabel: string;
  onSelect: (region: RegionSummary) => void;
  regions: RegionSummary[];
  selectedCode?: string | null;
  selectionLabel: string;
}) {
  return (
    <ul aria-label={ariaLabel} className="panel-list region-grid-list">
      {regions.map((region) => {
        const selected = selectedCode === region.code;
        return (
          <li key={region.id}>
            <button
              aria-label={`${selectionLabel} ${region.name}`}
              aria-pressed={selected}
              type="button"
              onClick={() => onSelect(region)}
            >
              <span className="region-tile-label">{region.name}</span>
              {selected ? <CheckIcon aria-hidden="true" /> : null}
            </button>
          </li>
        );
      })}
    </ul>
  );
}
