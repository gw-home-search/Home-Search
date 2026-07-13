import { AccountControl } from '../features/auth/AccountControl';

export function AppHeader() {
  return (
    <header aria-label="상단 앱 바" className="app-bar">
      <div className="app-brand">
        <img
          alt=""
          aria-hidden="true"
          className="app-brand-mark"
          height="38"
          src="/home-search-logo.png"
          width="38"
        />
        <span className="app-brand-copy">
          <h1>홈서치</h1>
          <span>HomeSearch · 실거래가 인사이트</span>
        </span>
      </div>
      <AccountControl />
    </header>
  );
}
