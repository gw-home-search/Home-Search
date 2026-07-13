import { forwardRef } from 'react';

import { BackIcon, CloseIcon } from '../../../shared/icons';
import type { RoadviewRuntimeState } from './mapToolTypes';

type RoadviewPaneProps = {
  state: RoadviewRuntimeState;
  onClose: () => void;
};

export const RoadviewPane = forwardRef<HTMLDivElement, RoadviewPaneProps>(function RoadviewPane(
  { state, onClose },
  ref,
) {
  return (
    <section aria-label="거리뷰 패널" className="roadview-pane" data-roadview-state={state}>
      <header className="roadview-pane-header">
        <button type="button" aria-label="거리뷰 뒤로가기" onClick={onClose}>
          <BackIcon aria-hidden="true" />
        </button>
        <strong>거리뷰</strong>
        <span role="status">{roadviewStateLabel(state)}</span>
        <button type="button" aria-label="거리뷰 닫기" onClick={onClose}>
          <CloseIcon aria-hidden="true" />
        </button>
      </header>
      <div aria-label="카카오 거리뷰 화면" className="roadview-host" ref={ref} />
    </section>
  );
});

function roadviewStateLabel(state: RoadviewRuntimeState): string {
  if (state === 'loading') return '불러오는 중';
  if (state === 'unavailable') return '이 위치 주변에는 거리뷰가 없습니다';
  if (state === 'error') return '거리뷰를 불러오지 못했습니다';
  return '';
}
