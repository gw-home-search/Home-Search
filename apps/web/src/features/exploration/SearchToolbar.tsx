import type { FormEventHandler, Ref } from 'react';

import { CloseIcon, SearchIcon } from '../../shared/icons';

export function SearchToolbar({
  hidden,
  inputRef,
  onClose,
  onInputChange,
  onSubmit,
}: {
  hidden: boolean;
  inputRef?: Ref<HTMLInputElement>;
  onClose: () => void;
  onInputChange: (value: string) => void;
  onSubmit: FormEventHandler<HTMLFormElement>;
}) {
  return (
    <form aria-label="단지 검색" className="search-panel exploration-search-panel" hidden={hidden} onSubmit={onSubmit}>
      <label className="exploration-search-field">
        <span>단지</span>
        <SearchIcon aria-hidden="true" />
        <input
          ref={inputRef}
          aria-label="단지 검색"
          name="q"
          onInput={(event) => onInputChange(event.currentTarget.value)}
          placeholder="아파트명을 검색해보세요."
          type="search"
        />
      </label>
      <button type="submit" aria-label="단지 검색 실행" className="exploration-search-submit">검색</button>
      <button type="button" aria-label="검색 패널 닫기" className="exploration-mobile-close" onClick={onClose}>
        <CloseIcon aria-hidden="true" />
      </button>
    </form>
  );
}
