import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { ChatMessageBody } from './ChatMessageBody';
import type { ChatMessage } from './storage/chatConversationStore';

describe('복합 구조화 답변', () => {
  it('직접 결론을 범위 안내보다 먼저 보이고 후속 질문을 칩으로 나눈다', () => {
    const message: ChatMessage = {
      id: 'message-direct', role: 'assistant', content: 'fallback',
      createdAt: '2026-08-03T00:00:00Z',
      summary: {
        version: 1,
        scopeNotice: { text: '‘잠실엘스’를 기준으로 확인했습니다.', factIds: ['fact-1'] },
        headline: { text: '잠실엘스는 송파구 잠실동에 있습니다.', factIds: ['fact-1'] },
        criteria: [], interpretations: [], fragmentSummaries: [],
        followUp: '잠실엘스 최근 실거래를 알려줘 · 잠실엘스 주변 역·노선을 알려줘',
      },
    };

    const html = renderToStaticMarkup(<ChatMessageBody message={message} />);

    expect(html.indexOf('잠실엘스는')).toBeLessThan(html.indexOf('‘잠실엘스’를 기준'));
    expect(html.match(/chatbot-follow-up-prompts/g)).toHaveLength(1);
    expect(html).toContain('주변 역·노선을 알려줘</button>');
  });

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

  it('부분 성공은 성공 결과를 먼저 두고 실패 source를 하단 flat section에 표시한다', () => {
    const message: ChatMessage = {
      id: 'message-partial', role: 'assistant', content: 'fallback',
      createdAt: '2026-08-07T00:00:00Z',
      fragments: [
        { fragmentId: 'rail', capability: 'rail_station_lookup', status: 'failed', answer: '철도 확인 실패', factIds: [], artifactIds: [], actionIds: [], limitations: ['철도 source를 현재 확인하지 못했습니다.'] },
        { fragmentId: 'academy', capability: 'academy_lookup', status: 'success', answer: '학원 확인', factIds: ['fact-1'], artifactIds: ['artifact-1'], actionIds: [], limitations: [] },
      ],
      artifacts: [{ type: 'factList', version: 1, artifactId: 'artifact-1', title: '가까운 학원', items: [{ label: '학원 1', value: '직선거리 300m', factIds: ['fact-1'] }] }],
      summary: {
        version: 1, scopeNotice: null,
        headline: { text: '확인 가능한 주변 정보를 정리했습니다.', factIds: ['fact-1'] },
        criteria: [{ key: 'representativeSelection', label: '대표 선택 근거', value: '가락동 alias로 확정했습니다.', factIds: ['fact-1'] }],
        interpretations: [], followUp: null,
        fragmentSummaries: [
          { fragmentId: 'rail', capability: 'rail_station_lookup', status: 'failed', headline: '철도 확인 실패', factIds: [] },
          { fragmentId: 'academy', capability: 'academy_lookup', status: 'success', headline: '학원 정보를 확인했습니다.', factIds: ['fact-1'] },
        ],
      },
    };

    const html = renderToStaticMarkup(<ChatMessageBody message={message} />);

    expect(html.indexOf('학원 정보를 확인했습니다.')).toBeLessThan(html.indexOf('철도 확인 실패'));
    expect(html.indexOf('확인하지 못한 정보')).toBeLessThan(html.indexOf('철도 source를 현재 확인하지 못했습니다.'));
    expect(html).toContain('<h4>선택 근거</h4>');
    expect(html).not.toContain('<details class="chatbot-selection-basis"');
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

    expect(html).toContain('데이터 참고');
    expect(html).toContain('신고 지연으로 실제 거래와 차이가 있을 수 있습니다.');
    expect(html).not.toContain('답변 출처');
    expect(html).not.toContain('답변 근거');
    expect(html).not.toContain('근거 0개');
    expect(html).not.toContain('출처 0개');
  });

  it('답변과 동일한 limitation 및 omission은 한 번만 표시한다', () => {
    const repeated = '일부 데이터를 확인하지 못해 이 항목은 답변에서 제외했습니다.';
    const message: ChatMessage = {
      id: 'message-repeated-limitation', role: 'assistant', content: repeated,
      createdAt: '2026-07-22T00:00:00Z',
      evidence: {
        requestId: 'request-repeated', dataAsOf: null, citations: [],
        limitations: [repeated],
        evidenceSummary: {
          status: 'unavailable', capabilities: ['recommendation'],
          factCount: 0, citationCount: 0,
        },
      },
      resolution: {
        version: 1, answerMode: 'NO_RESULT', assumptions: [],
        goals: [{ capability: 'recommendation', status: 'unavailable' }],
        omissions: [repeated],
      },
    };

    const html = renderToStaticMarkup(<ChatMessageBody message={message} />);

    expect(html.match(new RegExp(repeated, 'g'))).toHaveLength(1);
    expect(html).not.toContain('데이터 참고');
    expect(html).not.toContain('확인하지 못한 정보');
  });

  it('의사결정 리포트에서는 성공 설명을 경고로 반복하지 않고 실제 제한만 표시한다', () => {
    const message: ChatMessage = {
      id: 'message-report-limitations', role: 'assistant', content: 'fallback',
      createdAt: '2026-07-22T00:00:00Z',
      evidence: {
        requestId: 'request-report', dataAsOf: '2026-07-20', citations: [],
        limitations: [
          '송파구에서 요청한 조건을 적용한 후보를 정리했습니다.',
          '일부 학교 자료를 확인하지 못해 해당 기준은 제외했습니다.',
        ],
        evidenceSummary: {
          status: 'partial', capabilities: ['recommendation'], factCount: 1, citationCount: 0,
        },
      },
      report: {
        version: 1, kind: 'RECOMMENDATION',
        opening: { text: '먼저 살펴볼 후보를 정리했어요.', factIds: ['scope-1'] },
        basis: [{ text: '송파구를 기준으로 확인했습니다.', factIds: ['scope-1'] }],
        primaryArtifactId: null, highlights: [], detailArtifactIds: [], actionIds: [],
      },
    };

    const html = renderToStaticMarkup(<ChatMessageBody message={message} />);

    expect(html).not.toContain('요청한 조건을 적용한 후보를 정리했습니다.');
    expect(html).toContain('일부 학교 자료를 확인하지 못해 해당 기준은 제외했습니다.');
    expect(html.match(/확인하지 못한 정보/g)).toHaveLength(1);
  });

  it('의사결정 리포트가 있어도 검증된 후속 질문을 표시한다', () => {
    const message: ChatMessage = {
      id: 'message-report-follow-up', role: 'assistant', content: 'fallback',
      createdAt: '2026-07-22T00:00:00Z',
      evidence: {
        requestId: 'request-report-follow-up', dataAsOf: '2026-07-20', citations: [],
        limitations: [], evidenceSummary: {
          status: 'supported', capabilities: ['recent_trade_lookup'], factCount: 1,
          citationCount: 0,
        },
      },
      summary: {
        version: 1, scopeNotice: null,
        headline: { text: '대표 단지의 실거래를 확인했습니다.', factIds: ['trade-1'] },
        criteria: [], interpretations: [], fragmentSummaries: [],
        followUp: '같은 단지의 거래량도 확인해보세요.',
      },
      report: {
        version: 1, kind: 'PROPERTY_OVERVIEW',
        opening: { text: '대표 단지의 실거래를 확인했습니다.', factIds: ['trade-1'] },
        basis: [], primaryArtifactId: null, highlights: [], detailArtifactIds: [],
        actionIds: [],
      },
    };

    const html = renderToStaticMarkup(<ChatMessageBody message={message} />);

    expect(html).toContain('같은 단지의 거래량도 확인해보세요.');
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
