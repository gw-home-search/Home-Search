import type { DistanceMeasureState } from './mapToolTypes';

type DistanceMeasureBarProps = {
  state: DistanceMeasureState;
  onComplete: () => void;
  onExit: () => void;
  onReset: () => void;
  onUndo: () => void;
};

export function DistanceMeasureBar({ state, onComplete, onExit, onReset, onUndo }: DistanceMeasureBarProps) {
  return (
    <section aria-label="거리 측정 도구" className="distance-measure-bar" data-distance-phase={state.phase}>
      <div className="distance-measure-copy">
        <strong>{state.points.length === 0 ? '지도를 눌러 시작점을 선택하세요' : formatDistance(state.lengthMeters)}</strong>
        <span>지도상 선형 거리 · {state.points.length}개 지점</span>
      </div>
      <div className="distance-measure-actions">
        <button type="button" disabled={state.points.length === 0 || state.phase === 'complete'} onClick={onUndo}>한 단계 취소</button>
        <button type="button" disabled={state.points.length === 0} onClick={onReset}>초기화</button>
        <button type="button" disabled={state.points.length < 2 || state.phase === 'complete'} onClick={onComplete}>완료</button>
        <button type="button" onClick={onExit}>나가기</button>
      </div>
    </section>
  );
}

export function formatDistance(lengthMeters: number): string {
  return lengthMeters < 1000
    ? `${Math.round(lengthMeters)}m`
    : `${(lengthMeters / 1000).toFixed(2)}km`;
}
