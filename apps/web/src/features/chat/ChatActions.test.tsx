import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { ChatActions } from './ChatActions';
import type { FocusComplexAction } from './actionContract';

const action: FocusComplexAction = {
  type: 'focusComplex', version: 1, actionId: 'focus-501',
  label: '잠실엘스 지도에서 보기', parcelId: 101, complexId: 501,
  center: { lat: 37.5, lng: 127.1 }, level: 4, openDetail: true,
  autoRun: true, factIds: ['property-complex-501'],
};

describe('지도 action 상태', () => {
  function selectedDetailMarkup(detailState: 'loading' | 'ready' | 'error') {
    return renderToStaticMarkup(<ChatActions
      actions={[action]}
      detailState={detailState}
      executedActionIds={new Set()}
      selectedComplexId={501}
    />);
  }

  it('선택 단지와 상세 loading 상태를 함께 표시한다', () => {
    const html = selectedDetailMarkup('loading');

    expect(html).toContain('지도에 표시됨 · 상세 여는 중');
    expect(html).toContain('aria-pressed="true"');
  });

  it('선택 단지와 상세 ready 상태를 함께 표시한다', () => {
    const html = selectedDetailMarkup('ready');

    expect(html).toContain('지도에 표시됨 · 단지 상세 열림');
    expect(html).toContain('aria-pressed="true"');
  });

  it('선택 단지와 상세 error 상태를 함께 표시한다', () => {
    const html = selectedDetailMarkup('error');

    expect(html).toContain('지도에 표시됨 · 상세 다시 시도');
    expect(html).toContain('aria-pressed="true"');
  });

  it('선택 단지와 상세 상태가 없으면 기본 action label을 표시한다', () => {
    const html = renderToStaticMarkup(<ChatActions
      actions={[action]}
      executedActionIds={new Set()}
      selectedComplexId={501}
    />);

    expect(html).toContain('잠실엘스 지도에서 보기');
    expect(html).toContain('aria-pressed="true"');
  });

  it('이동 중과 이동 실패를 polite live region으로 알린다', () => {
    const moving = renderToStaticMarkup(<ChatActions
      actions={[action]} executedActionIds={new Set()}
      focusActionStatuses={new Map([[action.actionId, 'moving']])}
    />);
    const failed = renderToStaticMarkup(<ChatActions
      actions={[action]} executedActionIds={new Set()}
      focusActionStatuses={new Map([[action.actionId, 'failed']])}
    />);

    expect(moving).toContain('지도로 이동 중');
    expect(moving).toContain('aria-live="polite"');
    expect(failed).toContain('지도를 이동하지 못했습니다 · 다시 시도');
  });
});
