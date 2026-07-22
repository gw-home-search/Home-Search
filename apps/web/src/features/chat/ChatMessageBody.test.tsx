import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { ChatMessageBody } from './ChatMessageBody';
import type { ChatMessage } from './storage/chatConversationStore';

describe('복합 구조화 답변', () => {
  it('artifact를 실제 fragment id 아래에 한 번만 표시한다', () => {
    const message: ChatMessage = {
      id: 'message-1', role: 'assistant', content: 'text fallback',
      createdAt: '2026-07-21T00:00:00Z',
      evidence: {
        requestId: 'request-1', dataAsOf: '2026-07-20', limitations: [],
        evidenceSummary: {
          status: 'supported', capabilities: ['complex_identity', 'academy_lookup'],
          factCount: 2, citationCount: 1,
        },
        citations: [{
          citationId: 'citation-1', sourceId: 'property.ai_read',
          sourceName: 'Home Search', sourceUrl: null, evidenceGrade: 'A',
          datasetVersion: 'property-v1', dataAsOf: '2026-07-20', observedAt: null,
          factIds: ['fact-1', 'fact-2'],
        }],
      },
      artifacts: [
        { type: 'factList', version: 1, artifactId: 'artifact-1', title: '단지 결과표', items: [{ label: '단지명', value: '후보 1', factIds: ['fact-1'] }] },
        { type: 'factList', version: 1, artifactId: 'artifact-2', title: '학원 결과표', items: [{ label: '학원', value: '학원 1', factIds: ['fact-2'] }] },
      ],
      fragments: [
        { fragmentId: 'fragment-1', capability: 'complex_identity', status: 'success', answer: '단지', factIds: ['fact-1'], artifactIds: ['artifact-1'], actionIds: [], limitations: [] },
        { fragmentId: 'fragment-2', capability: 'academy_lookup', status: 'success', answer: '학원', factIds: ['fact-2'], artifactIds: ['artifact-2'], actionIds: [], limitations: [] },
      ],
      summary: {
        version: 1, scopeNotice: null,
        headline: { text: '2개 요청을 모두 확인했습니다.', factIds: ['fact-1', 'fact-2'] },
        criteria: [], interpretations: [], followUp: null,
        fragmentSummaries: [
          { fragmentId: 'fragment-1', capability: 'complex_identity', status: 'success', headline: '단지 정보를 확인했습니다.', factIds: ['fact-1'] },
          { fragmentId: 'fragment-2', capability: 'academy_lookup', status: 'success', headline: '학원 정보를 확인했습니다.', factIds: ['fact-2'] },
        ],
      },
    };

    const html = renderToStaticMarkup(<ChatMessageBody message={message} />);

    expect(html.indexOf('단지 정보를 확인했습니다.')).toBeLessThan(html.indexOf('단지 결과표</h4>'));
    expect(html.indexOf('학원 정보를 확인했습니다.')).toBeLessThan(html.indexOf('학원 결과표</h4>'));
    expect(html.match(/단지 결과표<\/h4>/g)).toHaveLength(1);
    expect(html.match(/학원 결과표<\/h4>/g)).toHaveLength(1);
    expect(html).not.toContain('text fallback');
  });

  it('출처가 없으면 근거 UI를 만들지 않고 limitation을 답변에 포함한다', () => {
    const message: ChatMessage = {
      id: 'message-no-source', role: 'assistant', content: '현재 확인 가능한 답변입니다.',
      createdAt: '2026-07-21T00:00:00Z',
      evidence: {
        requestId: 'request-no-source', dataAsOf: null, citations: [],
        limitations: ['신고 지연으로 실제 거래와 차이가 있을 수 있습니다.'],
        evidenceSummary: { status: 'unavailable', capabilities: [], factCount: 0, citationCount: 0 },
      },
    };

    const html = renderToStaticMarkup(<ChatMessageBody message={message} />);

    expect(html).toContain('확인할 점');
    expect(html).toContain('신고 지연으로 실제 거래와 차이가 있을 수 있습니다.');
    expect(html).not.toContain('답변 출처');
    expect(html).not.toContain('답변 근거');
    expect(html).not.toContain('근거 0개');
    expect(html).not.toContain('출처 0개');
  });

  it('동일한 실제 출처는 한 번만 표시하고 근거 메타데이터는 숨긴다', () => {
    const duplicate = {
      citationId: 'citation-duplicate', sourceId: 'property.ai_read',
      sourceName: 'Home Search 실거래', sourceUrl: 'https://example.com/trades',
      evidenceGrade: 'A' as const, datasetVersion: 'property-v1', dataAsOf: '2026-07-20',
      observedAt: null, factIds: ['fact-1'],
    };
    const message: ChatMessage = {
      id: 'message-sources', role: 'assistant', content: '거래를 확인했습니다.',
      createdAt: '2026-07-21T00:00:00Z',
      evidence: {
        requestId: 'request-sources', dataAsOf: '2026-07-20', limitations: [],
        evidenceSummary: { status: 'supported', capabilities: [], factCount: 1, citationCount: 2 },
        citations: [
          { ...duplicate, citationId: 'citation-1' },
          { ...duplicate, citationId: 'citation-2' },
        ],
      },
    };

    const html = renderToStaticMarkup(<ChatMessageBody message={message} />);

    expect(html.match(/Home Search 실거래/g)).toHaveLength(1);
    expect(html).toContain('href="https://example.com/trades"');
    expect(html).not.toContain('2026-07-20');
    expect(html).not.toContain('근거 등급');
  });
});
