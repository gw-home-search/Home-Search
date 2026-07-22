import { useCallback, useMemo, useRef, useState } from 'react';

import {
  createChatDraft,
  IndexedDbChatConversationStore,
  type ChatConversation,
} from './storage/chatConversationStore';

type ActiveConversation =
  | { kind: 'draft'; conversation: ChatConversation }
  | { kind: 'saved'; id: string };

export function useChatConversationWorkspace(providedStore?: IndexedDbChatConversationStore) {
  const storeRef = useRef(providedStore);
  const [conversations, setConversations] = useState<ChatConversation[]>([]);
  const [active, setActive] = useState<ActiveConversation>(
    () => ({ kind: 'draft', conversation: createChatDraft() }),
  );
  const selectedId = active.kind === 'saved' ? active.id : null;
  const selected = useMemo(
    () => active.kind === 'draft'
      ? active.conversation
      : conversations.find(({ id }) => id === active.id) ?? null,
    [active, conversations],
  );

  const requiredStore = useCallback(() => {
    storeRef.current ??= new IndexedDbChatConversationStore();
    return storeRef.current;
  }, []);

  const load = useCallback(async (selectLatest = false) => {
    const store = requiredStore();
    const stored = await store.list();
    const emptyIds = stored.filter(({ messages }) => messages.length === 0).map(({ id }) => id);
    await Promise.all(emptyIds.map((id) => store.delete(id)));
    const next = stored.filter(({ messages }) => messages.length > 0);
    setConversations(next);
    setActive((current) => {
      if (!selectLatest && current.kind === 'saved' && next.some(({ id }) => id === current.id)) return current;
      return next[0] == null
        ? { kind: 'draft', conversation: createChatDraft() }
        : { kind: 'saved', id: next[0].id };
    });
  }, [requiredStore]);

  const startDraft = useCallback(() => {
    setActive({ kind: 'draft', conversation: createChatDraft() });
  }, []);

  const select = useCallback((id: string) => {
    setActive({ kind: 'saved', id });
  }, []);

  const save = useCallback(async (conversation: ChatConversation, activate: boolean) => {
    const store = requiredStore();
    await store.save(conversation);
    setConversations((await store.list()).filter(({ messages }) => messages.length > 0));
    if (activate) setActive({ kind: 'saved', id: conversation.id });
  }, [requiredStore]);

  const deleteConversation = useCallback(async (id: string) => {
    await requiredStore().delete(id);
    await load();
  }, [load, requiredStore]);

  const deleteAll = useCallback(async () => {
    await requiredStore().clear();
    setConversations([]);
    setActive({ kind: 'draft', conversation: createChatDraft() });
  }, [requiredStore]);

  const exportArchive = useCallback(() => requiredStore().exportArchive(), [requiredStore]);

  const importArchive = useCallback(async (serialized: string) => {
    await requiredStore().importArchive(serialized, 'merge');
    await load();
  }, [load, requiredStore]);

  return {
    conversations,
    deleteAll,
    deleteConversation,
    exportArchive,
    importArchive,
    load,
    save,
    select,
    selected,
    selectedId,
    startDraft,
  };
}
