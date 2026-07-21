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
});
