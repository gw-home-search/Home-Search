import { act, type ComponentProps } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { groupConversationsByDate } from './chatHistoryGroups';
import { ChatHistoryPopover } from './ChatHistoryPopover';
import type { ChatConversation } from './storage/chatConversationStore';

describe('챗봇 대화 기록', () => {
  let root: Root | null = null;
  let host: HTMLDivElement | null = null;

  afterEach(async () => {
    if (root != null) await act(async () => root?.unmount());
    host?.remove();
    root = null;
    host = null;
  });

  it('local date 기준으로 오늘·어제·최근 7일·이전을 나눈다', () => {
    const now = localTimestamp(2026, 7, 22, 12);
    const grouped = groupConversationsByDate([
      conversation('today', '오늘 대화', localTimestamp(2026, 7, 22, 0, 30).toISOString()),
      conversation('yesterday', '어제 대화', localTimestamp(2026, 7, 21, 23).toISOString()),
      conversation('week', '이번 주 대화', localTimestamp(2026, 7, 17, 12).toISOString()),
      conversation('older', '이전 대화', localTimestamp(2026, 7, 10, 12).toISOString()),
    ], now);

    expect(grouped.get('오늘')?.map(({ id }) => id)).toEqual(['today']);
    expect(grouped.get('어제')?.map(({ id }) => id)).toEqual(['yesterday']);
    expect(grouped.get('최근 7일')?.map(({ id }) => id)).toEqual(['week']);
    expect(grouped.get('이전')?.map(({ id }) => id)).toEqual(['older']);
  });

  it('행 메뉴에서 해당 대화만 확인 후 삭제하고 취소 시 유지한다', async () => {
    const onDelete = vi.fn().mockResolvedValue(undefined);
    ({ root, host } = await renderHistory({ onDelete }));

    await click(host.querySelector<HTMLButtonElement>('[aria-label="첫 대화 대화 관리"]'));
    await click(buttonByText(host, '대화 삭제'));
    expect(host.querySelector('[role="dialog"]')?.textContent).toContain('복구할 수 없습니다');
    await click(buttonByText(host, '취소'));
    expect(onDelete).not.toHaveBeenCalled();

    await click(buttonByText(host, '대화 삭제'));
    await click(buttonByText(host, '삭제'));
    expect(onDelete).toHaveBeenCalledTimes(1);
    expect(onDelete).toHaveBeenCalledWith('first');
  });

  it('Escape는 행 메뉴를 먼저 닫고 다시 누르면 기록을 닫아 trigger로 focus를 돌린다', async () => {
    const trigger = document.createElement('button');
    document.body.append(trigger);
    const onClose = vi.fn();
    ({ root, host } = await renderHistory({ onClose, trigger }));

    const rowTrigger = host.querySelector<HTMLButtonElement>('[aria-label="첫 대화 대화 관리"]');
    await click(rowTrigger);
    await keyDown(host.querySelector<HTMLElement>('.chatbot-history-popover'), 'Escape');
    expect(host.querySelector('[aria-label="첫 대화 메뉴"]')).toBeNull();
    expect(document.activeElement).toBe(rowTrigger);

    await keyDown(host.querySelector<HTMLElement>('.chatbot-history-popover'), 'Escape');
    expect(onClose).toHaveBeenCalledTimes(1);
    await act(async () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve())));
    expect(document.activeElement).toBe(trigger);
    trigger.remove();
  });
});

async function renderHistory(overrides: Partial<ComponentProps<typeof ChatHistoryPopover>> = {}) {
  const nextHost = document.createElement('div');
  document.body.append(nextHost);
  const nextRoot = createRoot(nextHost);
  await act(async () => nextRoot.render(
    <ChatHistoryPopover
      conversations={[conversation('first', '첫 대화', '2026-07-22T01:00:00.000Z')]}
      onClose={vi.fn()}
      onDelete={vi.fn().mockResolvedValue(undefined)}
      onDeleteAll={vi.fn().mockResolvedValue(undefined)}
      onExport={vi.fn()}
      onImport={vi.fn()}
      onSelect={vi.fn()}
      selectedId="first"
      trigger={null}
      {...overrides}
    />,
  ));
  return { root: nextRoot, host: nextHost };
}

function conversation(id: string, title: string, updatedAt: string): ChatConversation {
  return {
    id,
    title,
    createdAt: updatedAt,
    updatedAt,
    messages: [{ id: `${id}-message`, role: 'user', content: title, createdAt: updatedAt }],
  };
}

function localTimestamp(year: number, month: number, day: number, hour: number, minute = 0): Date {
  return new Date(year, month - 1, day, hour, minute);
}

async function click(button: HTMLButtonElement | null) {
  expect(button).not.toBeNull();
  await act(async () => button?.click());
  await act(async () => Promise.resolve());
}

async function keyDown(element: HTMLElement | null, key: string) {
  expect(element).not.toBeNull();
  await act(async () => element?.dispatchEvent(new KeyboardEvent('keydown', {
    bubbles: true,
    cancelable: true,
    key,
  })));
  await act(async () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve())));
}

function buttonByText(container: HTMLElement | null, text: string): HTMLButtonElement | null {
  return [...(container?.querySelectorAll<HTMLButtonElement>('button') ?? [])]
    .find((button) => button.textContent === text) ?? null;
}
