import { describe, expect, it, vi } from 'vitest';

import { queryChatbot, type AuthenticatedChatbotRequest } from './chatbotClient';

describe('챗봇 질문 client', () => {
  it('고정 JSON 경로와 제한된 문맥을 사용하고 근거 응답을 검증한다', async () => {
    const authenticatedRequest = vi.fn<AuthenticatedChatbotRequest>().mockResolvedValue(new Response(JSON.stringify({
      success: true,
      status: 'success',
      answer: '2026년 6월 거래가 확인됐습니다.',
      requestId: 'request-1',
      citations: [{
        citationId: 'citation-1',
        sourceId: 'property.ai_read',
        sourceName: 'Home Search 실거래',
        sourceUrl: null,
        evidenceGrade: 'A',
        datasetVersion: 'property-2026-07-16',
        dataAsOf: '2026-07-16',
        observedAt: null,
        factIds: ['property-trade-1'],
      }],
      dataAsOf: '2026-07-16',
      limitations: ['신고 지연이 반영될 수 있습니다.'],
      evidenceSummary: {
        status: 'supported',
        capabilities: ['recent_trade_lookup'],
        factCount: 1,
        citationCount: 1,
      },
      uiArtifacts: [{
        type: 'factList',
        version: 1,
        artifactId: 'artifact-1',
        title: '확인된 단지 정보',
        items: [{ label: '단지명', value: '잠실엘스', factIds: ['property-trade-1'] }],
      }, {
        type: 'futureArtifact',
        version: 1,
        artifactId: 'future-1',
      }, {
        type: 'factList',
        version: 1,
        artifactId: 'malformed-1',
        title: '잘못된 artifact',
        items: [{ label: '단지명', value: '<script>alert(1)</script>', factIds: [] }],
      }],
      uiActions: [],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));

    const response = await queryChatbot(authenticatedRequest, {
      question: '잠실엘스 최근 거래',
      conversationContext: { messages: [{ role: 'user', content: '잠실엘스 위치' }] },
    });

    expect(authenticatedRequest).toHaveBeenCalledWith('/api/v1/chatbot/query', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ Accept: 'application/json', 'Content-Type': 'application/json' }),
      body: JSON.stringify({
        question: '잠실엘스 최근 거래',
        conversationContext: { messages: [{ role: 'user', content: '잠실엘스 위치' }] },
      }),
    }), 'public');
    expect(response.evidenceSummary.status).toBe('supported');
    expect(response.citations[0]?.factIds).toEqual(['property-trade-1']);
    expect(response.artifacts).toEqual([{
      type: 'factList',
      version: 1,
      artifactId: 'artifact-1',
      title: '확인된 단지 정보',
      items: [{ label: '단지명', value: '잠실엘스', factIds: ['property-trade-1'] }],
    }]);
  });

  it('잘못된 성공 body를 거부하고 응답 원문 노출 없이 실패를 변환한다', async () => {
    const invalid = vi.fn<AuthenticatedChatbotRequest>().mockResolvedValue(new Response(JSON.stringify({
      success: true,
      answer: 'unsupported',
    }), { status: 200 }));
    await expect(queryChatbot(invalid, { question: '질문' })).rejects.toThrow('챗봇 응답을 확인하지 못했습니다.');

    const unavailable = vi.fn<AuthenticatedChatbotRequest>().mockResolvedValue(new Response('provider detail', { status: 503 }));
    await expect(queryChatbot(unavailable, { question: '질문' })).rejects.toThrow('챗봇을 잠시 사용할 수 없습니다.');
  });
});
