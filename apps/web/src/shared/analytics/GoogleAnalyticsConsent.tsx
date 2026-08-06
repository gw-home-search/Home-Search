import { useEffect, useState } from 'react';

const CONSENT_KEY = 'home-search.analytics-consent';
const MEASUREMENT_ID = 'G-8L85Z825PE';
const OPEN_CONSENT_EVENT = 'home-search:open-analytics-consent';

type Consent = 'granted' | 'denied';
type AnalyticsWindow = Window & {
  dataLayer?: unknown[][];
  gtag?: (...args: unknown[]) => void;
  'ga-disable-G-8L85Z825PE'?: boolean;
};

export function GoogleAnalyticsConsent() {
  const [consent, setConsent] = useState<Consent | null>(null);
  const [isOpen, setIsOpen] = useState(false);

  useEffect(() => {
    const stored = readConsent();
    setConsent(stored);
    setIsOpen(stored == null);
    if (stored === 'granted') loadGoogleAnalytics();
    else disableGoogleAnalytics();

    const reopen = () => setIsOpen(true);
    window.addEventListener(OPEN_CONSENT_EVENT, reopen);
    return () => window.removeEventListener(OPEN_CONSENT_EVENT, reopen);
  }, []);

  if (!isOpen) return null;

  const choose = (next: Consent) => {
    consentStorage().setItem(CONSENT_KEY, next);
    setConsent(next);
    setIsOpen(false);
    if (next === 'granted') loadGoogleAnalytics();
    else disableGoogleAnalytics();
  };

  return (
    <aside aria-label="분석 쿠키 선택" className="analytics-consent" role="dialog">
      <p>
        홈서치는 서비스 개선을 위해 선택적 Google Analytics 쿠키를 사용합니다.
        거부해도 모든 기본 기능을 이용할 수 있습니다.
      </p>
      <div>
        <button data-consent="denied" onClick={() => choose('denied')} type="button">거부</button>
        <button data-consent="granted" onClick={() => choose('granted')} type="button">허용</button>
      </div>
      <span className="sr-only" aria-live="polite">{consent == null ? '' : `분석 쿠키 ${consent}`}</span>
    </aside>
  );
}

function readConsent(): Consent | null {
  const value = consentStorage().getItem(CONSENT_KEY);
  return value === 'granted' || value === 'denied' ? value : null;
}

function consentStorage(): Storage {
  try {
    return window.localStorage ?? window.sessionStorage;
  } catch {
    return window.sessionStorage;
  }
}

function loadGoogleAnalytics(): void {
  const analyticsWindow = window as AnalyticsWindow;
  analyticsWindow[`ga-disable-${MEASUREMENT_ID}`] = false;
  if (document.getElementById('home-search-google-tag')) {
    analyticsWindow.gtag?.('consent', 'update', { analytics_storage: 'granted' });
    return;
  }
  analyticsWindow.dataLayer = analyticsWindow.dataLayer ?? [];
  analyticsWindow.gtag = (...args: unknown[]) => analyticsWindow.dataLayer?.push(args);
  analyticsWindow.gtag('js', new Date());
  analyticsWindow.gtag('config', MEASUREMENT_ID);

  const script = document.createElement('script');
  script.async = true;
  script.id = 'home-search-google-tag';
  script.src = `https://www.googletagmanager.com/gtag/js?id=${MEASUREMENT_ID}`;
  document.head.append(script);
}

function disableGoogleAnalytics(): void {
  const analyticsWindow = window as AnalyticsWindow;
  analyticsWindow[`ga-disable-${MEASUREMENT_ID}`] = true;
  analyticsWindow.gtag?.('consent', 'update', { analytics_storage: 'denied' });
  for (const name of ['_ga', `_ga_${MEASUREMENT_ID.replace(/^G-/u, '')}`]) {
    document.cookie = `${name}=; Max-Age=0; Path=/; SameSite=Lax`;
  }
}
