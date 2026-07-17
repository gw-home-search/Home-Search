import { IDBFactory } from 'fake-indexeddb';
import { describe, expect, it } from 'vitest';

import {
  buildConversationContext,
  createChatConversation,
  IndexedDbChatConversationStore,
  type ChatConversation,
} from './chatConversationStore';

describe('IndexedDbChatConversationStore', () => {
  it('persists multiple conversations and lists the most recently updated first', async () => {
    const indexedDB = new IDBFactory();
    const firstStore = new IndexedDbChatConversationStore(indexedDB, 'chat-test-persist');
    const first = conversation('first', '2026-07-17T00:00:00.000Z');
    const second = conversation('second', '2026-07-17T01:00:00.000Z');

    await firstStore.save(first);
    await firstStore.save(second);

    const reloadedStore = new IndexedDbChatConversationStore(indexedDB, 'chat-test-persist');
    await expect(reloadedStore.list()).resolves.toEqual([second, first]);
    await expect(reloadedStore.get(first.id)).resolves.toEqual(first);
  });

  it('deletes one selected conversation or all conversations', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-test-delete');
    await store.save(conversation('first', '2026-07-17T00:00:00.000Z'));
    await store.save(conversation('second', '2026-07-17T01:00:00.000Z'));

    await store.delete('first');
    expect((await store.list()).map(({ id }) => id)).toEqual(['second']);

    await store.clear();
    await expect(store.list()).resolves.toEqual([]);
  });

  it('exports and imports a versioned archive without partial writes', async () => {
    const source = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-test-export');
    const target = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-test-import');
    const first = conversation('first', '2026-07-17T00:00:00.000Z');
    await source.save(first);

    const archive = await source.exportArchive('2026-07-17T02:00:00.000Z');
    await target.importArchive(archive, 'merge');
    await expect(target.list()).resolves.toEqual([first]);

    const beforeInvalidImport = await target.list();
    await expect(target.importArchive('{"version":1,"conversations":[{"id":"broken"}]}', 'replace'))
      .rejects.toThrow('Invalid chat archive');
    await expect(target.list()).resolves.toEqual(beforeInvalidImport);
  });

  it('replace import removes conversations that are absent from the archive', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-test-replace');
    await store.save(conversation('old', '2026-07-17T00:00:00.000Z'));
    const replacement = conversation('replacement', '2026-07-17T03:00:00.000Z');

    await store.importArchive(JSON.stringify({
      version: 1,
      exportedAt: '2026-07-17T04:00:00.000Z',
      conversations: [replacement],
    }), 'replace');

    await expect(store.list()).resolves.toEqual([replacement]);
  });

  it('rejects an archive larger than the browser import limit', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-test-size-limit');
    const oversized = JSON.stringify({
      version: 1,
      exportedAt: '2026-07-17T04:00:00.000Z',
      conversations: [],
      padding: 'x'.repeat(10 * 1024 * 1024),
    });

    await expect(store.importArchive(oversized, 'merge')).rejects.toThrow('Invalid chat archive');
    await expect(store.list()).resolves.toEqual([]);
  });

  it('rejects an imported citation with a non-HTTPS source URL', async () => {
    const store = new IndexedDbChatConversationStore(new IDBFactory(), 'chat-test-source-url');
    const imported = conversation('malicious-link', '2026-07-17T04:00:00.000Z');
    imported.messages[0] = {
      ...imported.messages[0]!,
      role: 'assistant',
      evidence: evidence('javascript:alert(1)'),
    };

    await expect(store.importArchive(JSON.stringify({
      version: 1,
      exportedAt: '2026-07-17T05:00:00.000Z',
      conversations: [imported],
    }), 'merge')).rejects.toThrow('Invalid chat archive');
    await expect(store.list()).resolves.toEqual([]);
  });
});

describe('chat conversation helpers', () => {
  it('creates a local-only empty conversation', () => {
    const created = createChatConversation({
      id: 'new-id',
      now: '2026-07-17T05:00:00.000Z',
      title: ' 새 대화 ',
    });

    expect(created).toEqual({
      id: 'new-id',
      title: '새 대화',
      createdAt: '2026-07-17T05:00:00.000Z',
      updatedAt: '2026-07-17T05:00:00.000Z',
      messages: [],
    });
  });

  it('sends only the newest bounded messages as conversationContext', () => {
    const messages = Array.from({ length: 15 }, (_, index) => ({
      id: `message-${index}`,
      role: index % 2 === 0 ? 'user' as const : 'assistant' as const,
      content: `${index}:` + 'x'.repeat(2_500),
      createdAt: `2026-07-17T00:${String(index).padStart(2, '0')}:00.000Z`,
    }));

    const context = buildConversationContext(messages);

    expect(context.messages).toHaveLength(6);
    expect(context.messages[0]?.content.startsWith('9:')).toBe(true);
    expect(context.messages.at(-1)?.content.startsWith('14:')).toBe(true);
    expect(context.messages.every(({ content }) => content.length === 2_000)).toBe(true);
    expect(context.messages.reduce((total, message) => total + message.content.length, 0)).toBe(12_000);
  });
});

function conversation(id: string, updatedAt: string): ChatConversation {
  return {
    id,
    title: `${id} conversation`,
    createdAt: '2026-07-17T00:00:00.000Z',
    updatedAt,
    messages: [{
      id: `${id}-message`,
      role: 'user',
      content: `${id} question`,
      createdAt: updatedAt,
    }],
  };
}

function evidence(sourceUrl: string) {
  return {
    requestId: 'request-1',
    citations: [{
      citationId: 'citation-1',
      sourceId: 'property.ai_read',
      sourceName: 'Home Search 실거래',
      sourceUrl,
      evidenceGrade: 'A' as const,
      datasetVersion: 'property-2026-07-16',
      dataAsOf: '2026-07-16',
      observedAt: null,
      factIds: ['property-trade-1'],
    }],
    dataAsOf: '2026-07-16',
    limitations: [],
    evidenceSummary: {
      status: 'supported' as const,
      capabilities: ['recent_trade_lookup'],
      factCount: 1,
      citationCount: 1,
    },
  };
}
