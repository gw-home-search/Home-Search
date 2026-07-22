import { IDBFactory } from 'fake-indexeddb';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthProvider } from '../auth/AuthProvider';
import type { AuthClient } from '../auth/api/authClient';
import { ChatbotPanel } from './ChatbotPanel';
import type { ChatAction } from './actionContract';
import {
  IndexedDbChatConversationStore,
  type ChatConversation,
} from './storage/chatConversationStore';

describe('챗봇 패널', () => {
  let root: Root | undefined;
  let host: HTMLDivElement | undefined;

  afterEach(() => {
    if (root) act(() => root?.unmount());
    host?.remove();
    vi.restoreAllMocks();
  });

  it('Enter로 질문을 보내고 Shift+Enter와 한글 조합 Enter는 전송하지 않는다', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-panel-enter');
    const client = authenticatedClient();
    ({ root, host } = await renderPanel(client, store));

    await waitFor(() => host?.querySelector<HTMLButtonElement>('.chatbot-launcher')?.disabled === false);
    await click(host.querySelector<HTMLButtonElement>('.chatbot-launcher'));
    const textarea = host.querySelector<HTMLTextAreaElement>('#chatbot-question');
    await waitFor(() => textarea?.disabled === false);

    await change(textarea, '잠실엘스 최근 거래');
    await keyDown(textarea, { key: 'Enter', shiftKey: true });
    await keyDown(textarea, { key: 'Enter', isComposing: true });
    expect(client.authenticatedRequest).not.toHaveBeenCalled();

    await keyDown(textarea, { key: 'Enter' });
    await waitFor(() => client.authenticatedRequest.mock.calls.length === 1);
    expect(client.authenticatedRequest).toHaveBeenCalledTimes(1);
  });

  it('패널 열기와 반복 새 대화는 저장하지 않고 첫 질문을 보낼 때만 저장한다', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-panel-draft');
    ({ root, host } = await renderPanel(authenticatedClient(), store));

    await waitFor(() => host?.querySelector<HTMLButtonElement>('.chatbot-launcher')?.disabled === false);
    await click(host.querySelector<HTMLButtonElement>('.chatbot-launcher'));
    await waitFor(() => host?.querySelector<HTMLTextAreaElement>('#chatbot-question')?.disabled === false);
    expect(await store.list()).toHaveLength(0);

    await click(host.querySelector<HTMLButtonElement>('.chatbot-new-conversation'));
    await click(host.querySelector<HTMLButtonElement>('.chatbot-new-conversation'));
    expect(await store.list()).toHaveLength(0);

    const textarea = host.querySelector<HTMLTextAreaElement>('#chatbot-question');
    await change(textarea, '잠실엘스 최근 거래');
    await keyDown(textarea, { key: 'Enter' });
    await waitFor(async () => (await store.list())[0]?.messages.length === 2);
    expect(await store.list()).toHaveLength(1);
  });

  it('API 실패 후에도 보낸 질문을 대화에 유지한다', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-panel-failed-request');
    const client = authenticatedClient();
    client.authenticatedRequest = vi.fn().mockRejectedValue(new Error('잠시 후 다시 시도해주세요.'));
    ({ root, host } = await renderPanel(client, store));

    await waitFor(() => host?.querySelector<HTMLButtonElement>('.chatbot-launcher')?.disabled === false);
    await click(host.querySelector<HTMLButtonElement>('.chatbot-launcher'));
    const textarea = host.querySelector<HTMLTextAreaElement>('#chatbot-question');
    await waitFor(() => textarea?.disabled === false);
    await change(textarea, '실패해도 남아야 하는 질문');
    await keyDown(textarea, { key: 'Enter' });
    await waitFor(() => host?.textContent?.includes('챗봇 요청을 완료하지 못했습니다.') === true);

    const [saved] = await store.list();
    expect(saved?.messages).toHaveLength(1);
    expect(saved?.messages[0]?.content).toBe('실패해도 남아야 하는 질문');
  });

  it('제한된 인증 질문을 보내고 재마운트 후에도 답변 근거를 유지한다', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-panel-persist');
    const client = authenticatedClient();
    ({ root, host } = await renderPanel(client, store));

    await waitFor(() => host?.querySelector<HTMLButtonElement>('.chatbot-launcher')?.disabled === false);
    await click(host.querySelector<HTMLButtonElement>('.chatbot-launcher'));
    await waitFor(() => host?.querySelector<HTMLTextAreaElement>('textarea[name="chatbot-question"]')?.disabled === false);
    const textarea = host.querySelector<HTMLTextAreaElement>('textarea[name="chatbot-question"]');
    await change(textarea, '잠실엘스 최근 거래');
    await click(host.querySelector<HTMLButtonElement>('button[type="submit"]'));
    await waitFor(() => host?.textContent?.includes('근거가 확인된 답변입니다.') === true);

    expect(host.textContent).toContain('근거가 확인된 답변입니다.');
    expect(host.textContent).toContain('기준일 2026-07-16');
    expect(host.textContent).toContain('출처 1개');
    expect(host.textContent).toContain('Home Search 실거래');
    expect(host.textContent).toContain('근거 등급 A');
    expect(host.querySelector('.chatbot-fact-list')?.textContent).toContain('확인된 단지 정보');
    expect(host.querySelector('.chatbot-fact-list')?.textContent).toContain('잠실엘스');
    expect(client.authenticatedRequest).toHaveBeenCalledWith(
      '/api/v1/chatbot/query',
      expect.any(Object),
      'public',
    );
    const saved = await store.list();
    expect(saved[0]?.messages.map(({ role }) => role)).toEqual(['user', 'assistant']);
    expect(saved[0]?.messages[1]?.evidence?.citations[0]?.sourceId).toBe('property.ai_read');
    expect(saved[0]?.messages[1]?.artifacts).toHaveLength(1);

    act(() => root?.unmount());
    root = undefined;
    host.remove();
    ({ root, host } = await renderPanel(client, store));
    await waitFor(() => host?.querySelector<HTMLButtonElement>('.chatbot-launcher')?.disabled === false);
    await click(host.querySelector<HTMLButtonElement>('.chatbot-launcher'));
    await waitFor(() => host?.textContent?.includes('근거가 확인된 답변입니다.') === true);
    expect(host.textContent).toContain('근거가 확인된 답변입니다.');
    expect(host.textContent).toContain('기준일 2026-07-16');
  });

  it('구조화 summary가 있으면 text fallback을 중복 표시하지 않고 전달 순서대로 표시한다', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-panel-summary');
    const client = authenticatedClient();
    const response = JSON.parse(await (await client.authenticatedRequest(
      '/api/v1/chatbot/query', {}, 'public',
    )).text());
    response.answer = '중복되면 안 되는 text fallback';
    response.uiSummary = {
      version: 1,
      scopeNotice: { text: '잠실엘스 기준으로 확인했습니다.', factIds: ['property-trade-1'] },
      headline: { text: '최근 거래를 확인했습니다.', factIds: ['property-trade-1'] },
      criteria: [{ key: 'END_DATE', label: '기준일', value: '2026-07-16', factIds: ['property-trade-1'] }],
      interpretations: [{
        key: 'RECENT_TRADE', label: '최근 거래 해석', text: '현재 데이터 기준 거래입니다.',
        factIds: ['property-trade-1'],
      }],
      followUp: '기간을 바꿔 확인할 수 있습니다.',
      fragmentSummaries: [],
    };
    client.authenticatedRequest = vi.fn().mockResolvedValue(new Response(JSON.stringify(response)));
    ({ root, host } = await renderPanel(client, store));

    await waitFor(() => host?.querySelector<HTMLButtonElement>('.chatbot-launcher')?.disabled === false);
    await click(host.querySelector<HTMLButtonElement>('.chatbot-launcher'));
    await waitFor(() => host?.querySelector<HTMLTextAreaElement>('#chatbot-question')?.disabled === false);
    await change(host.querySelector<HTMLTextAreaElement>('#chatbot-question'), '잠실엘스 최근 거래');
    await click(host.querySelector<HTMLButtonElement>('button[type="submit"]'));
    await waitFor(() => host?.textContent?.includes('최근 거래를 확인했습니다.') === true);

    expect(host.textContent).not.toContain('중복되면 안 되는 text fallback');
    const structured = host.querySelector('.chatbot-structured-answer');
    expect(structured?.textContent).toContain('잠실엘스 기준으로 확인했습니다.');
    expect(structured?.textContent).toContain('기준일');
    expect(structured?.textContent).toContain('현재 데이터 기준 거래입니다.');
    expect(structured?.textContent).toContain('신고 지연이 반영될 수 있습니다.');
    expect(structured?.textContent).toContain('기간을 바꿔 확인할 수 있습니다.');
  });

  it('새 대화는 첫 질문 전까지 저장하지 않고 입력 가능한 draft로 유지한다', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-panel-lifecycle');
    ({ root, host } = await renderPanel(authenticatedClient(), store));
    await waitFor(() => host?.querySelector<HTMLButtonElement>('.chatbot-launcher')?.disabled === false);
    await click(host.querySelector<HTMLButtonElement>('.chatbot-launcher'));
    await waitFor(() => host?.querySelector<HTMLTextAreaElement>('textarea[name="chatbot-question"]')?.disabled === false);

    const textarea = host.querySelector<HTMLTextAreaElement>('textarea[name="chatbot-question"]');
    await change(textarea, '저장될 첫 질문');
    await keyDown(textarea, { key: 'Enter' });
    await waitFor(async () => (await store.list()).length === 1);

    await click(host.querySelector<HTMLButtonElement>('.chatbot-new-conversation'));
    expect(await store.list()).toHaveLength(1);
    expect(host.querySelector<HTMLTextAreaElement>('textarea[name="chatbot-question"]')?.disabled).toBe(false);
  });

  it('지도 action은 버튼을 누를 때 한 번만 전달하고 대화에는 action만 저장한다', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-panel-action');
    const onUiAction = vi.fn((_action: ChatAction) => true);
    const action = {
      type: 'showNearbyCategory',
      version: 1,
      actionId: 'action-request-1-hospital',
      label: '지도에서 병원 보기',
      category: 'HOSPITAL',
      center: { lat: 37.513, lng: 127.082 },
      level: 4,
      factIds: ['property-trade-1'],
    };
    ({ root, host } = await renderPanel(authenticatedClient([action]), store, onUiAction));

    await waitFor(() => host?.querySelector<HTMLButtonElement>('.chatbot-launcher')?.disabled === false);
    await click(host.querySelector<HTMLButtonElement>('.chatbot-launcher'));
    await waitFor(() => host?.querySelector<HTMLTextAreaElement>('#chatbot-question')?.disabled === false);
    await change(host.querySelector<HTMLTextAreaElement>('#chatbot-question'), '잠실엘스 주변 병원 지도');
    await click(host.querySelector<HTMLButtonElement>('button[type="submit"]'));
    await waitFor(() => (host ? buttonByText(host, '지도에서 병원 보기') : null) != null);

    expect(onUiAction).not.toHaveBeenCalled();
    const actionButton = host ? buttonByText(host, '지도에서 병원 보기') : null;
    await click(actionButton);
    expect(onUiAction).toHaveBeenCalledTimes(1);
    expect(onUiAction).toHaveBeenCalledWith(action);
    expect(actionButton?.textContent).toBe('지도에 표시됨');
    expect(actionButton?.getAttribute('aria-disabled')).toBe('true');
    await click(actionButton);
    expect(onUiAction).toHaveBeenCalledTimes(1);

    const saved = await store.list();
    expect(saved[0]?.messages[1]?.actions).toEqual([action]);
    expect(JSON.stringify(saved)).not.toContain('placeUrl');
    expect(JSON.stringify(saved)).not.toContain('phone');
  });

  it('대화 목록을 GPT형 내비게이션으로 보여주고 목록에서 대화를 전환한다', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-panel-navigation');
    await store.save(conversation('older', '잠실엘스 최근 거래', '2026-07-18T09:00:00.000Z'));
    await store.save(conversation('newer', '마포래미안 비교', '2026-07-19T09:00:00.000Z'));
    ({ root, host } = await renderPanel(authenticatedClient(), store));

    await waitFor(() => host?.querySelector<HTMLButtonElement>('.chatbot-launcher')?.disabled === false);
    await click(host.querySelector<HTMLButtonElement>('.chatbot-launcher'));
    await click(host.querySelector<HTMLButtonElement>('button[aria-label="대화 목록 열기"]'));
    await waitFor(() => host?.querySelectorAll('.chatbot-conversation-list button').length === 2);

    const conversationList = host.querySelector<HTMLElement>('.chatbot-conversation-list');
    expect(conversationList).not.toBeNull();
    const newer = conversationList ? buttonByText(conversationList, '마포래미안 비교') : null;
    const older = conversationList ? buttonByText(conversationList, '잠실엘스 최근 거래') : null;
    expect(newer?.getAttribute('aria-pressed')).toBe('true');
    expect(older?.getAttribute('aria-pressed')).toBe('false');

    await click(older);
    await click(host.querySelector<HTMLButtonElement>('button[aria-label="대화 목록 열기"]'));
    const reopenedList = host.querySelector<HTMLElement>('.chatbot-conversation-list');
    expect(reopenedList ? buttonByText(reopenedList, '잠실엘스 최근 거래')?.getAttribute('aria-pressed') : null)
      .toBe('true');
  });

  it('빈 대화에서 단지와 조회 유형이 다른 질문 예시를 입력창에 채운다', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-panel-prompts');
    ({ root, host } = await renderPanel(authenticatedClient(), store));

    await waitFor(() => host?.querySelector<HTMLButtonElement>('.chatbot-launcher')?.disabled === false);
    await click(host.querySelector<HTMLButtonElement>('.chatbot-launcher'));
    const recentTradeQuestion = '마포래미안푸르지오 전용 84㎡의 최근 실거래 5건을 거래일과 층까지 알려줘';
    const priceTrendQuestion = '헬리오시티 전용 59㎡의 최근 1년 월별 가격 흐름과 거래량을 보여줘';
    const lifestyleQuestion = '잠실엘스 주변 학원 위치와 가까운 역·노선을 함께 알려줘';
    await waitFor(() => host?.querySelector<HTMLButtonElement>(`button[aria-label="${recentTradeQuestion}"]`) != null);

    expect(host.querySelector<HTMLButtonElement>(`button[aria-label="${priceTrendQuestion}"]`)).not.toBeNull();
    expect(host.querySelector<HTMLButtonElement>(`button[aria-label="${lifestyleQuestion}"]`)).not.toBeNull();
    expect([...host.querySelectorAll('.chatbot-example-kind')].map(({ textContent }) => textContent))
      .toEqual(['최근 실거래', '가격 흐름', '생활 인프라']);
    await click(host.querySelector<HTMLButtonElement>(`button[aria-label="${recentTradeQuestion}"]`));
    expect(host.querySelector<HTMLTextAreaElement>('textarea[name="chatbot-question"]')?.value)
      .toBe(recentTradeQuestion);
  });

  it('긴 질문은 네 줄까지 입력창 높이를 늘리고 이후에는 내부 스크롤로 전환한다', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-panel-autogrow');
    ({ root, host } = await renderPanel(authenticatedClient(), store));

    await waitFor(() => host?.querySelector<HTMLButtonElement>('.chatbot-launcher')?.disabled === false);
    await click(host.querySelector<HTMLButtonElement>('.chatbot-launcher'));
    await waitFor(() => host?.querySelector<HTMLTextAreaElement>('#chatbot-question')?.disabled === false);
    const textarea = host.querySelector<HTMLTextAreaElement>('#chatbot-question');
    expect(textarea).not.toBeNull();

    Object.defineProperty(textarea, 'scrollHeight', { configurable: true, value: 72 });
    await change(textarea, '마포래미안푸르지오 전용 84㎡의 최근 실거래를 거래일과 층까지 자세히 알려줘');
    expect(textarea?.style.height).toBe('72px');
    expect(textarea?.style.overflowY).toBe('hidden');

    Object.defineProperty(textarea, 'scrollHeight', { configurable: true, value: 144 });
    await change(textarea, '마포래미안푸르지오와 헬리오시티의 최근 실거래와 가격 흐름을 면적별로 길게 비교해줘');
    expect(textarea?.style.height).toBe('96px');
    expect(textarea?.style.overflowY).toBe('auto');
  });

  it('앱 헤더 진입 버튼은 간결하게 유지하고 열린 패널은 홈서치 전용 identity를 사용한다', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-panel-beta');
    ({ root, host } = await renderPanel(authenticatedClient(), store));

    await waitFor(() => host?.querySelector<HTMLButtonElement>('.chatbot-launcher')?.disabled === false);
    const launcher = host.querySelector<HTMLButtonElement>('.chatbot-launcher');
    expect(launcher?.querySelector('svg.chatbot-ai-mark')).not.toBeNull();
    expect(launcher?.querySelectorAll('.chatbot-ai-mark-bar')).toHaveLength(3);
    expect(launcher?.querySelector('.chatbot-launcher-label')?.textContent).toBe('AI');
    expect(launcher?.querySelector('.chatbot-beta-badge')).toBeNull();

    await click(launcher);
    await waitFor(() => host?.querySelector('.chatbot-panel-brand .chatbot-beta-badge') != null);
    expect(host.querySelector('.chatbot-panel-brand')?.textContent).toContain('홈서치 AI');
    expect(host.querySelector('.chatbot-panel-brand svg.chatbot-ai-mark')).not.toBeNull();
    expect(host.querySelector('.chatbot-panel-brand .chatbot-beta-badge')?.textContent).toBe('Beta');
    expect(host.querySelector('.chatbot-new-conversation')?.textContent).toContain('새 대화');
    expect(host.querySelector('.chatbot-panel-toolbar')?.querySelector('.chatbot-sparkle-mark')).toBeNull();
    expect(host.querySelector('.chatbot-form .chatbot-beta-badge')).toBeNull();
    await waitFor(() => host?.querySelector('.chatbot-empty-intro') != null);
    expect(host.querySelector('.chatbot-example-questions')?.querySelector('svg')).toBeNull();
    expect(host.querySelectorAll('.chatbot-example-questions button')).toHaveLength(3);
    expect(host.querySelector('.chatbot-example-questions')?.textContent).toContain(
      '주변 학원 위치와 가까운 역·노선',
    );
    expect(document.activeElement).toBe(host.querySelector<HTMLTextAreaElement>('#chatbot-question'));
    expect(host.querySelector('.chatbot-empty-intro')?.textContent).toContain('어떤 집을 찾고 계세요?');
    const question = host.querySelector<HTMLTextAreaElement>('#chatbot-question');
    expect(question?.placeholder).toBe('원하는 지역과 조건을 입력해 보세요.');
    expect(question?.getAttribute('rows')).toBe('1');
    expect(host.querySelector('.chatbot-composer .chatbot-send-icon')).not.toBeNull();
  });
});

function conversation(id: string, title: string, updatedAt: string): ChatConversation {
  return {
    id,
    title,
    createdAt: updatedAt,
    updatedAt,
    messages: [{
      id: `${id}-message`,
      role: 'user',
      content: title,
      createdAt: updatedAt,
    }],
  };
}

function authenticatedClient(uiActions: unknown[] = []): AuthClient {
  return {
    authenticatedRequest: vi.fn().mockResolvedValue(new Response(JSON.stringify({
      success: true,
      status: 'success',
      answer: '근거가 확인된 답변입니다.',
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
      }],
      uiActions,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })),
    authorizationUrl: vi.fn(),
    logout: vi.fn().mockResolvedValue(undefined),
    restoreSession: vi.fn().mockResolvedValue({
      kind: 'authenticated',
      currentUser: { userId: 7, provider: 'google', displayName: '홍길동', profileImage: null },
    }),
  };
}

async function renderPanel(
  client: AuthClient,
  store: IndexedDbChatConversationStore,
  onUiAction?: (action: ChatAction) => boolean,
) {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  await act(async () => root.render(
    <AuthProvider client={client}>
      <ChatbotPanel onUiAction={onUiAction} store={store} />
    </AuthProvider>,
  ));
  await act(async () => Promise.resolve());
  return { root, host };
}

async function click(button: HTMLButtonElement | null) {
  expect(button).not.toBeNull();
  await act(async () => button?.click());
  await act(async () => Promise.resolve());
}

async function change(input: HTMLTextAreaElement | null, value: string) {
  expect(input).not.toBeNull();
  await act(async () => {
    if (input == null) return;
    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
    setter?.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

async function keyDown(input: HTMLTextAreaElement | null, init: KeyboardEventInit) {
  expect(input).not.toBeNull();
  await act(async () => {
    input?.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init }));
  });
  await act(async () => Promise.resolve());
}

function buttonByText(container: HTMLElement, text: string): HTMLButtonElement | null {
  return [...container.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent === text) ?? null;
}

async function waitFor(predicate: () => boolean | Promise<boolean>) {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    if (await predicate()) return;
    await act(async () => new Promise((resolve) => setTimeout(resolve, 0)));
  }
  throw new Error('Timed out waiting for UI state');
}
