import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { ChatPendingMessage, ChatThreadMessage } from './ChatThreadMessage';

describe('챗봇 메시지 레이아웃', () => {
  it('사용자와 AI 메시지에 보이지 않는 의미만 남기고 화자 이름과 avatar를 만들지 않는다', () => {
    const userHtml = renderToStaticMarkup(<ChatThreadMessage message={{
      id: 'user-message', role: 'user', content: '내 질문입니다.', createdAt: '2026-07-22T00:00:00Z',
    }} />);
    const assistantHtml = renderToStaticMarkup(<ChatThreadMessage message={{
      id: 'assistant-message', role: 'assistant', content: '확인한 답변입니다.', createdAt: '2026-07-22T00:00:01Z',
    }} />);

    expect(userHtml).toContain('aria-label="내 질문"');
    expect(assistantHtml).toContain('aria-label="홈서치 AI 답변"');
    expect(`${userHtml}${assistantHtml}`).not.toContain('chatbot-message-avatar');
    expect(userHtml).not.toContain('>나<');
    expect(assistantHtml).not.toContain('>홈서치 AI<');
  });

  it('답변 생성 중에는 평평한 상태 문구만 제공한다', () => {
    const html = renderToStaticMarkup(<ChatPendingMessage />);

    expect(html).toContain('aria-live="polite"');
    expect(html).toContain('데이터를 확인하고 있어요');
    expect(html).not.toContain('avatar');
  });
});
