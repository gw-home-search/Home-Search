import { IDBFactory } from 'fake-indexeddb';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthProvider } from '../auth/AuthProvider';
import type { AuthClient } from '../auth/api/authClient';
import { ChatbotPanel } from './ChatbotPanel';
import { IndexedDbChatConversationStore } from './storage/chatConversationStore';

describe('ChatbotPanel', () => {
  let root: Root | undefined;
  let host: HTMLDivElement | undefined;

  afterEach(() => {
    if (root) act(() => root?.unmount());
    host?.remove();
    vi.restoreAllMocks();
  });

  it('sends a bounded authenticated query and persists answer evidence across remount', async () => {
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
    expect(client.authenticatedRequest).toHaveBeenCalledWith(
      '/api/v1/chatbot/query',
      expect.any(Object),
      'public',
    );
    const saved = await store.list();
    expect(saved[0]?.messages.map(({ role }) => role)).toEqual(['user', 'assistant']);
    expect(saved[0]?.messages[1]?.evidence?.citations[0]?.sourceId).toBe('property.ai_read');

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

  it('supports new conversation and selected/all deletion without server storage', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-panel-lifecycle');
    ({ root, host } = await renderPanel(authenticatedClient(), store));
    await waitFor(() => host?.querySelector<HTMLButtonElement>('.chatbot-launcher')?.disabled === false);
    await click(host.querySelector<HTMLButtonElement>('.chatbot-launcher'));
    await waitFor(() => host?.querySelector<HTMLTextAreaElement>('textarea[name="chatbot-question"]')?.disabled === false);

    await click(buttonByText(host, '새 대화'));
    await waitFor(async () => (await store.list()).length === 2);
    expect(await store.list()).toHaveLength(2);
    await click(buttonByText(host, '현재 삭제'));
    await waitFor(async () => (await store.list()).length === 1);
    expect(await store.list()).toHaveLength(1);

    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await click(buttonByText(host, '전체 삭제'));
    await waitFor(async () => (await store.list()).length === 0);
    expect(await store.list()).toHaveLength(0);
    expect(host.querySelector<HTMLTextAreaElement>('textarea[name="chatbot-question"]')?.disabled).toBe(true);
  });
});

function authenticatedClient(): AuthClient {
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
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })),
    authorizationUrl: vi.fn(),
    logout: vi.fn().mockResolvedValue(undefined),
    restoreSession: vi.fn().mockResolvedValue({
      kind: 'authenticated',
      currentUser: { userId: 7, provider: 'google', displayName: '홍길동', profileImage: null },
    }),
  };
}

async function renderPanel(client: AuthClient, store: IndexedDbChatConversationStore) {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  await act(async () => root.render(
    <AuthProvider client={client}>
      <ChatbotPanel store={store} />
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
