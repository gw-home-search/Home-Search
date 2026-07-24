import { describe, expect, it, vi } from 'vitest';

import { queryChatbot, type AuthenticatedChatbotRequest } from './chatbotClient';
import { readConversationMemory } from '../conversationContract';

describe('챗봇 질문 client', () => {
  it('추천 후보 순서를 보존하는 conversation memory v2를 검증한다', () => {
    expect(readConversationMemory({
      version: 2,
      scopeKind: 'RECOMMENDATION',
      complexIds: [501, 502, 503],
      regionCode: '11710',
    })).toEqual({
      version: 2,
      scopeKind: 'RECOMMENDATION',
      complexIds: [501, 502, 503],
      regionCode: '11710',
    });
    expect(readConversationMemory({
      version: 2,
      scopeKind: 'RECOMMENDATION',
      complexIds: [501, 501],
    })).toBeNull();
  });

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
      uiActions: [{
        type: 'showNearbyCategory',
        version: 1,
        actionId: 'action-1',
        label: '지도에서 병원 보기',
        category: 'HOSPITAL',
        center: { lat: 37.513, lng: 127.082 },
        level: 4,
        factIds: ['property-trade-1'],
      }, {
        type: 'showNearbyCategory',
        version: 1,
        actionId: 'malformed-action',
        label: '<script>bad</script>',
        category: 'CAFE',
        center: { lat: 37.513, lng: 127.082 },
        level: 4,
        factIds: ['property-trade-1'],
      }],
      uiSummary: {
        version: 1,
        scopeNotice: { text: '잠실엘스 기준으로 확인했습니다.', factIds: ['property-trade-1'] },
        headline: { text: '최근 거래를 확인했습니다.', factIds: ['property-trade-1'] },
        criteria: [{
          key: 'END_DATE', label: '기준일', value: '2026-07-16', factIds: ['property-trade-1'],
        }],
        interpretations: [],
        followUp: '기간이나 면적을 바꿔 다시 확인할 수 있습니다.',
        fragmentSummaries: [],
      },
      uiReport: {
        version: 1,
        kind: 'RECENT_TRADE',
        opening: { text: '최근 거래를 확인했습니다.', factIds: ['property-trade-1'] },
        basis: [{ text: '기준일: 2026-07-16', factIds: ['property-trade-1'] }],
        primaryArtifactId: 'artifact-1',
        highlights: [],
        detailArtifactIds: [],
        actionIds: ['action-1'],
      },
      fragments: [{
        fragmentId: 'fragment-1', capability: 'recent_trade_lookup', status: 'success',
        answer: '거래를 확인했습니다.', factIds: ['property-trade-1'],
        artifactIds: ['artifact-1'], actionIds: ['action-1'], limitations: [],
      }],
      conversationResolution: {
        version: 1,
        answerMode: 'BEST_EFFORT',
        goals: [{ capability: 'recent_trade_lookup', status: 'degraded' }],
        assumptions: [{ code: 'DEFAULT_PERIOD_ONE_YEAR', text: '최근 1년을 기준으로 확인했습니다.' }],
        omissions: [],
      },
      conversationMemoryPatch: {
        version: 1,
        complexId: 501,
        regionCode: '11710',
        scopeKind: 'COMPLEX',
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));

    const response = await queryChatbot(authenticatedRequest, {
      question: '잠실엘스 최근 거래',
      uiContext: {
        mapViewport: {
          bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.7, neLng: 127.2 },
          level: 4,
        },
        selectedComplex: { complexId: 501, parcelId: 1001 },
      },
      conversationContext: {
        messages: [{ role: 'user', content: '잠실엘스 위치' }],
        memory: { version: 1, complexId: 501, scopeKind: 'COMPLEX' },
      },
    });

    expect(authenticatedRequest).toHaveBeenCalledWith('/api/v1/chatbot/query', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ Accept: 'application/json', 'Content-Type': 'application/json' }),
      body: JSON.stringify({
        question: '잠실엘스 최근 거래',
        uiContext: {
          mapViewport: {
            bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.7, neLng: 127.2 },
            level: 4,
          },
          selectedComplex: { complexId: 501, parcelId: 1001 },
        },
        conversationContext: {
          messages: [{ role: 'user', content: '잠실엘스 위치' }],
          memory: { version: 1, complexId: 501, scopeKind: 'COMPLEX' },
        },
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
    expect(response.actions).toEqual([{
      type: 'showNearbyCategory',
      version: 1,
      actionId: 'action-1',
      label: '지도에서 병원 보기',
      category: 'HOSPITAL',
      center: { lat: 37.513, lng: 127.082 },
      level: 4,
      factIds: ['property-trade-1'],
    }]);
    expect(response.summary?.headline.text).toBe('최근 거래를 확인했습니다.');
    expect(response.fragments[0]?.artifactIds).toEqual(['artifact-1']);
    expect(response.conversationResolution?.answerMode).toBe('BEST_EFFORT');
    expect(response.conversationMemoryPatch).toEqual({
      version: 1, complexId: 501, regionCode: '11710', scopeKind: 'COMPLEX',
    });
    expect(response.report?.primaryArtifactId).toBe('artifact-1');
    expect(response.report?.actionIds).toEqual(['action-1']);
  });

  it('유효하지 않은 선택 단지는 버리고 유효한 viewport만 보낸다', async () => {
    const authenticatedRequest = vi.fn<AuthenticatedChatbotRequest>().mockResolvedValue(
      responseWithSummary(null),
    );

    await queryChatbot(authenticatedRequest, {
      question: '이 주변은 어때?',
      uiContext: {
        mapViewport: {
          bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.7, neLng: 127.2 },
          level: 4,
        },
        selectedComplex: { complexId: 501, parcelId: 0 },
      },
    });

    const [, init] = authenticatedRequest.mock.calls[0] ?? [];
    expect(JSON.parse(String(init?.body))).toEqual({
      question: '이 주변은 어때?',
      uiContext: {
        mapViewport: {
          bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.7, neLng: 127.2 },
          level: 4,
        },
      },
    });
  });

  it('잘못된 uiSummary는 무시하고 text fallback을 유지한다', async () => {
    const authenticatedRequest = vi.fn<AuthenticatedChatbotRequest>().mockResolvedValue(
      responseWithSummary({
        version: 2,
        headline: { text: '지원하지 않는 버전', factIds: ['property-trade-1'] },
      }),
    );

    const response = await queryChatbot(authenticatedRequest, { question: '잠실엘스 최근 거래' });

    expect(response.answer).toBe('text fallback');
    expect(response.summary).toBeNull();
  });

  it('잘못된 성공 body를 거부하고 응답 원문 노출 없이 실패를 변환한다', async () => {
    const invalid = vi.fn<AuthenticatedChatbotRequest>().mockResolvedValue(new Response(JSON.stringify({
      success: true,
      answer: 'unsupported',
    }), { status: 200 }));
    await expect(queryChatbot(invalid, { question: '질문' })).rejects.toMatchObject({
      failure: { kind: 'invalid-response', service: 'chatbot' },
    });

    const unavailable = vi.fn<AuthenticatedChatbotRequest>().mockResolvedValue(new Response('provider detail', { status: 503 }));
    await expect(queryChatbot(unavailable, { question: '질문' })).rejects.toMatchObject({
      failure: { kind: 'service-unavailable', status: 503 },
    });
  });

  it('브라우저 abort 내부 문구를 노출하지 않고 취소로 분류한다', async () => {
    const aborted = vi.fn<AuthenticatedChatbotRequest>().mockRejectedValue(
      new DOMException('signal is aborted without reason', 'AbortError'),
    );

    await expect(queryChatbot(aborted, { question: '질문' })).rejects.toMatchObject({
      failure: { kind: 'cancelled', service: 'chatbot' },
    });
  });

  it('timeout은 caller 취소와 구분한다', async () => {
    const timedOut = vi.fn<AuthenticatedChatbotRequest>().mockRejectedValue(
      new DOMException('Request timed out', 'TimeoutError'),
    );

    await expect(queryChatbot(timedOut, { question: '질문' })).rejects.toMatchObject({
      failure: { kind: 'timeout', service: 'chatbot' },
    });
  });
});

function responseWithSummary(uiSummary: unknown): Response {
  return new Response(JSON.stringify({
    success: true,
    status: 'success',
    answer: 'text fallback',
    requestId: 'request-1',
    citations: [{
      citationId: 'citation-1', sourceId: 'property.ai_read', sourceName: 'Home Search 실거래',
      sourceUrl: null, evidenceGrade: 'A', datasetVersion: 'property-2026-07-16',
      dataAsOf: '2026-07-16', observedAt: null, factIds: ['property-trade-1'],
    }],
    dataAsOf: '2026-07-16',
    limitations: [],
    evidenceSummary: {
      status: 'supported', capabilities: ['recent_trade_lookup'], factCount: 1, citationCount: 1,
    },
    uiArtifacts: [],
    uiActions: [],
    uiSummary,
  }), { status: 200, headers: { 'Content-Type': 'application/json' } });
}
