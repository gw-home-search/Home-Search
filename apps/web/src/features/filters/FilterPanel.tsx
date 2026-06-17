import type { FormEventHandler } from 'react';

type FilterPanelProps = {
  activeFilterCount: number;
  formKey: number;
  onSubmit: FormEventHandler<HTMLFormElement>;
  onReset: () => void;
};

export function FilterPanel({
  activeFilterCount,
  formKey,
  onSubmit,
  onReset,
}: FilterPanelProps) {
  return (
    <form
      key={formKey}
      aria-label="마커 필터"
      className="filter-panel"
      data-filter-state={activeFilterCount > 0 ? 'active' : 'idle'}
      data-map-overlay="filters"
      data-ui-layer="filter-controls"
      onSubmit={onSubmit}
    >
      <fieldset className="filter-group">
        <legend>면적</legend>
        <div className="filter-range">
          <label>
            <span>최소</span>
            <input
              aria-label="최소 평형"
              name="pyeongMin"
              placeholder="평"
              type="number"
            />
          </label>
          <label>
            <span>최대</span>
            <input
              aria-label="최대 평형"
              name="pyeongMax"
              placeholder="평"
              type="number"
            />
          </label>
        </div>
      </fieldset>
      <fieldset className="filter-group">
        <legend>가격</legend>
        <div className="filter-range">
          <label>
            <span>최소</span>
            <input
              aria-label="최소 가격 억"
              name="priceEokMin"
              placeholder="억"
              step="0.1"
              type="number"
            />
          </label>
          <label>
            <span>최대</span>
            <input
              aria-label="최대 가격 억"
              name="priceEokMax"
              placeholder="억"
              step="0.1"
              type="number"
            />
          </label>
        </div>
      </fieldset>
      <fieldset className="filter-group">
        <legend>연식</legend>
        <div className="filter-range">
          <label>
            <span>최소</span>
            <input
              aria-label="최소 연식"
              name="ageMin"
              placeholder="년"
              type="number"
            />
          </label>
          <label>
            <span>최대</span>
            <input
              aria-label="최대 연식"
              name="ageMax"
              placeholder="년"
              type="number"
            />
          </label>
        </div>
      </fieldset>
      <fieldset className="filter-group">
        <legend>세대수</legend>
        <div className="filter-range">
          <label>
            <span>최소</span>
            <input
              aria-label="최소 세대수"
              name="unitMin"
              placeholder="세대"
              type="number"
            />
          </label>
          <label>
            <span>최대</span>
            <input
              aria-label="최대 세대수"
              name="unitMax"
              placeholder="세대"
              type="number"
            />
          </label>
        </div>
      </fieldset>
      <div className="filter-actions">
        <p className="filter-status" aria-live="polite">
          {activeFilterCount > 0 ? `필터 ${activeFilterCount}개 적용` : '필터 없음'}
        </p>
        <button type="submit" aria-label="마커 필터 적용">
          적용
        </button>
        <button type="button" aria-label="마커 필터 초기화" onClick={onReset}>
          초기화
        </button>
      </div>
    </form>
  );
}
