import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';

import { AnswerSources } from './AnswerSources';
import type { ChatCitation } from './chatTypes';

describe('챗봇 답변 출처', () => {
  let root: Root | null = null;
  let host: HTMLDivElement | null = null;

  afterEach(async () => {
    if (root != null) await act(async () => root?.unmount());
    host?.remove();
    root = null;
    host = null;
  });

  it('출처가 4개 이상이면 두 개만 먼저 보여주고 전체 보기를 제공한다', async () => {
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    await act(async () => root?.render(<AnswerSources citations={[
      citation('1', '실거래'), citation('2', '단지 정보'), citation('3', '철도역'), citation('4', '학원'),
    ]} />));

    expect(host.textContent).toContain('실거래');
    expect(host.textContent).toContain('단지 정보');
    expect(host.textContent).not.toContain('철도역');
    const trigger = [...host.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent === '출처 전체 보기') ?? null;
    expect(trigger).not.toBeNull();
    await act(async () => trigger?.click());

    expect(host.querySelector('[aria-label="전체 답변 출처"]')?.textContent).toContain('철도역');
    expect(host.querySelector('[aria-label="전체 답변 출처"]')?.textContent).toContain('학원');
  });
});

function citation(id: string, sourceName: string): ChatCitation {
  return {
    citationId: `citation-${id}`,
    sourceId: `source-${id}`,
    sourceName,
    sourceUrl: null,
    evidenceGrade: 'A',
    datasetVersion: 'dataset-1',
    dataAsOf: '2026-07-22',
    observedAt: null,
    factIds: [`fact-${id}`],
  };
}
