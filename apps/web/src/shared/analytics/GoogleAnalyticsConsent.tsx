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

const DENIED_CONSENT = {
  ad_personalization: 'denied',
  ad_storage: 'denied',
  ad_user_data: 'denied',
  analytics_storage: 'denied',
} as const;

const ANALYTICS_GRANTED_CONSENT = {
  ...DENIED_CONSENT,
  analytics_storage: 'granted',
} as const;

export function GoogleAnalyticsConsent() {
  const [consent, setConsent] = useState<Consent | null>(null);
  const [isOpen, setIsOpen] = useState(false);

  useEffect(() => {
    const stored = readConsent();
    setConsent(stored);
    setIsOpen(stored == null);
    initializeGoogleAnalytics(stored);

    const reopen = () => setIsOpen(true);
    window.addEventListener(OPEN_CONSENT_EVENT, reopen);
    return () => window.removeEventListener(OPEN_CONSENT_EVENT, reopen);
  }, []);

  if (!isOpen) return null;

  const choose = (next: Consent) => {
    consentStorage().setItem(CONSENT_KEY, next);
    setConsent(next);
    setIsOpen(false);
    updateGoogleAnalyticsConsent(next);
  };

  return (
    <aside aria-label="분석 쿠키 선택" className="analytics-consent" role="dialog">
      <p>
        홈서치는 서비스 개선을 위해 선택적 Google Analytics 쿠키를 사용합니다.
        거부하면 분석 쿠키는 저장하지 않지만 쿠키 없는 제한적인 방문 신호는 전송될 수 있으며,
        모든 기본 기능은 그대로 이용할 수 있습니다.
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

function initializeGoogleAnalytics(consent: Consent | null): void {
  const analyticsWindow = window as AnalyticsWindow;
  analyticsWindow[`ga-disable-${MEASUREMENT_ID}`] = false;
  if (document.getElementById('home-search-google-tag')) {
    updateGoogleAnalyticsConsent(consent ?? 'denied');
    return;
  }
  analyticsWindow.dataLayer = analyticsWindow.dataLayer ?? [];
  analyticsWindow.gtag = (...args: unknown[]) => analyticsWindow.dataLayer?.push(args);
  analyticsWindow.gtag('consent', 'default', DENIED_CONSENT);
  if (consent === 'granted') {
    analyticsWindow.gtag('consent', 'update', ANALYTICS_GRANTED_CONSENT);
  }
  analyticsWindow.gtag('js', new Date());
  analyticsWindow.gtag('config', MEASUREMENT_ID);

  const script = document.createElement('script');
  script.async = true;
  script.id = 'home-search-google-tag';
  script.src = `https://www.googletagmanager.com/gtag/js?id=${MEASUREMENT_ID}`;
  document.head.append(script);
}

function updateGoogleAnalyticsConsent(consent: Consent): void {
  const analyticsWindow = window as AnalyticsWindow;
  analyticsWindow[`ga-disable-${MEASUREMENT_ID}`] = false;
  analyticsWindow.gtag?.(
    'consent',
    'update',
    consent === 'granted' ? ANALYTICS_GRANTED_CONSENT : DENIED_CONSENT,
  );
  if (consent === 'granted') return;
  for (const name of ['_ga', `_ga_${MEASUREMENT_ID.replace(/^G-/u, '')}`]) {
    document.cookie = `${name}=; Max-Age=0; Path=/; SameSite=Lax`;
  }
}
