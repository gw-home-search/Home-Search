import { describe, expect, it, vi } from 'vitest';

import { queryChatbot, type AuthenticatedChatbotRequest } from './chatbotClient';

describe('queryChatbot', () => {
  it('uses the fixed JSON route, bounded context, and validated evidence response', async () => {
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
  });

  it('rejects invalid success bodies and maps non-success responses without leaking response text', async () => {
    const invalid = vi.fn<AuthenticatedChatbotRequest>().mockResolvedValue(new Response(JSON.stringify({
      success: true,
      answer: 'unsupported',
    }), { status: 200 }));
    await expect(queryChatbot(invalid, { question: '질문' })).rejects.toThrow('챗봇 응답을 확인하지 못했습니다.');

    const unavailable = vi.fn<AuthenticatedChatbotRequest>().mockResolvedValue(new Response('provider detail', { status: 503 }));
    await expect(queryChatbot(unavailable, { question: '질문' })).rejects.toThrow('챗봇을 잠시 사용할 수 없습니다.');
  });
});
