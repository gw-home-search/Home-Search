export function PublicFooter({ compact = false }: { compact?: boolean }) {
  return (
    <footer className={compact ? 'public-footer public-footer--compact' : 'public-footer'}>
      <span>© 2026 홈서치</span>
      <a href="/about">소개</a>
      <a href="/privacy">개인정보처리방침</a>
      <a href="/terms">이용약관</a>
      <button onClick={() => window.dispatchEvent(new Event('home-search:open-analytics-consent'))} type="button">쿠키 설정</button>
    </footer>
  );
}
