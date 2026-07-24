import { useEffect, useRef } from 'react';

import { RequestStateNotice } from '../../shared/RequestStateNotice';
import { ChevronRightIcon } from '../../shared/icons';
import type { RegionComplexSummary, RegionDetail, RegionSummary } from '../region/api/fetchRegions';
import { ComplexList } from './ComplexList';
import { RegionChoiceGrid } from './RegionChoiceGrid';

type PanelRequestState = 'idle' | 'loading' | 'ready' | 'empty' | 'error';
type RegionTrailItem = { id: number; name: string };

export function RegionExplorerSection({
  hidden,
  onComplexSelect,
  onLoadRootRegions,
  onRegionSelect,
  onRegionTrailSelect,
  onRetry,
  regionComplexes,
  regionDetail,
  regionError,
  regionState,
  regionTrail,
  rootRegions,
}: {
  hidden: boolean;
  onComplexSelect: (complex: RegionComplexSummary) => void;
  onLoadRootRegions: () => void;
  onRegionSelect: (region: RegionTrailItem) => void;
  onRegionTrailSelect: (region: RegionTrailItem, index: number) => void;
  onRetry: () => void;
  regionComplexes: RegionComplexSummary[];
  regionDetail: RegionDetail | null;
  regionError: string | null;
  regionState: PanelRequestState;
  regionTrail: RegionTrailItem[];
  rootRegions: RegionSummary[];
}) {
  const currentRegionStepRef = useRef<HTMLButtonElement>(null);
  const showRegionComplexes = regionDetail?.children.length === 0 && regionComplexes.length > 0;

  useEffect(() => {
    currentRegionStepRef.current?.scrollIntoView?.({ block: 'nearest', inline: 'end' });
  }, [regionTrail]);

  return (
    <section id="exploration-panel-region" aria-label="지역 탐색 패널" className="panel-section region-panel" data-api-flow="region" hidden={hidden}>
      <nav aria-label="지역 단계" className="region-breadcrumb">
        <button type="button" aria-current={regionTrail.length === 0 ? 'page' : undefined} aria-label="지역 처음으로" className="region-breadcrumb-link region-breadcrumb-root" onClick={onLoadRootRegions}>시도 선택</button>
        {regionTrail.map((region, index) => (
          <span className="region-breadcrumb-step" key={region.id}>
            <ChevronRightIcon aria-hidden="true" />
            <button type="button" ref={index === regionTrail.length - 1 ? currentRegionStepRef : undefined} aria-current={index === regionTrail.length - 1 ? 'page' : undefined} aria-label={`지역 단계 이동 ${region.name}`} className="region-breadcrumb-link" onClick={() => onRegionTrailSelect(region, index)}>{region.name}</button>
          </span>
        ))}
      </nav>
      <RequestStateNotice state={regionState} loadingMessage="지역을 불러오는 중" emptyMessage="표시할 지역이 없습니다" errorMessage="지역 정보를 불러오지 못했어요" technicalError={regionError} onRetry={onRetry} />
      {rootRegions.length > 0 ? (
        <RegionChoiceGrid
          ariaLabel="지역 탐색"
          onSelect={onRegionSelect}
          regions={rootRegions}
          selectedCode={rootRegions.find((region) => regionTrail.some((item) => item.id === region.id))?.code}
          selectionLabel="지역 이동"
        />
      ) : null}
      {showRegionComplexes ? (
        <section aria-label="선택한 읍면동 단지" className="region-complex-section">
          <div className="panel-section-header region-complex-heading"><p>단지</p><span aria-label={`${regionComplexes.length.toLocaleString()}개 단지`}>{regionComplexes.length.toLocaleString()}</span></div>
          <ComplexList ariaLabel="지역 단지 목록" items={regionComplexes.map((complex) => ({
            id: complex.complexId,
            ariaLabel: `지역 단지 선택 ${complex.complexName}`,
            name: complex.complexName,
            address: complex.address ?? '주소 정보 없음',
            approvalYear: complex.useDate?.match(/^\d{4}/)?.[0],
            unitCount: complex.unitCnt,
            buildingCount: complex.dongCnt,
            onSelect: () => onComplexSelect(complex),
          }))} />
        </section>
      ) : null}
    </section>
  );
}
