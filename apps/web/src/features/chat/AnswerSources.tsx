import { useState } from 'react';

import type { ChatCitation } from './chatTypes';

export function AnswerSources({ citations }: { citations: readonly ChatCitation[] }) {
  const [isOpen, setIsOpen] = useState(false);
  const sources = deduplicateSources(citations);
  if (sources.length === 0) return null;
  const visible = sources.length >= 4 ? sources.slice(0, 2) : sources;
  return (
    <div aria-label="답변 출처" className="chatbot-answer-sources">
      <span className="chatbot-answer-sources-label">출처</span>
      {visible.map((source) => (
        <span className="chatbot-answer-source" key={source.key}>
          <span aria-hidden="true">·</span>
          {source.url ? (
            <a href={source.url} rel="noreferrer noopener" target="_blank">{source.name}</a>
          ) : <span>{source.name}</span>}
        </span>
      ))}
      {sources.length >= 4 ? (
        <span className="chatbot-sources-more">
          <button
            aria-expanded={isOpen}
            aria-haspopup="menu"
            onClick={() => setIsOpen((open) => !open)}
            type="button"
          >
            출처 전체 보기
          </button>
          {isOpen ? (
            <div aria-label="전체 답변 출처" className="chatbot-sources-popover" role="menu">
              {sources.map((source) => source.url ? (
                <a href={source.url} key={source.key} rel="noreferrer noopener" role="menuitem" target="_blank">{source.name}</a>
              ) : <span key={source.key} role="menuitem">{source.name}</span>)}
            </div>
          ) : null}
        </span>
      ) : null}
    </div>
  );
}

function deduplicateSources(citations: readonly ChatCitation[]) {
  const sources = new Map<string, { key: string; name: string; url: string | null }>();
  for (const citation of citations) {
    const url = safeSourceUrl(citation.sourceUrl);
    const key = `${citation.sourceId}\u0000${url ?? ''}`;
    if (!sources.has(key)) sources.set(key, { key, name: citation.sourceName, url });
  }
  return [...sources.values()];
}

function safeSourceUrl(value: string | null): string | null {
  if (value == null) return null;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && url.username === '' && url.password === '' ? value : null;
  } catch {
    return null;
  }
}
