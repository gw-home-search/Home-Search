import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, expect, it } from 'vitest';

import { GoogleAnalyticsConsent } from './GoogleAnalyticsConsent';

type AnalyticsWindow = Window & {
  dataLayer?: unknown[][];
  gtag?: (...args: unknown[]) => void;
  'ga-disable-G-8L85Z825PE'?: boolean;
};

afterEach(() => {
  document.body.innerHTML = '';
  document.getElementById('home-search-google-tag')?.remove();
  window.sessionStorage.clear();
  const analyticsWindow = window as AnalyticsWindow;
  delete analyticsWindow.dataLayer;
  delete analyticsWindow.gtag;
  delete analyticsWindow['ga-disable-G-8L85Z825PE'];
});

it('동의 전에도 Google tag를 로드하고 Consent Mode 기본값을 config보다 먼저 거부로 설정한다', async () => {
  const element = document.createElement('div');
  document.body.append(element);
  const root = createRoot(element);

  await act(async () => root.render(<GoogleAnalyticsConsent />));

  expect(document.querySelector('script[src="https://www.googletagmanager.com/gtag/js?id=G-8L85Z825PE"]')).not.toBeNull();
  const commands = (window as AnalyticsWindow).dataLayer ?? [];
  expect(commands.slice(0, 3).map(([command]) => command)).toEqual(['consent', 'js', 'config']);
  expect(commands[0]).toEqual([
    'consent',
    'default',
    {
      ad_personalization: 'denied',
      ad_storage: 'denied',
      ad_user_data: 'denied',
      analytics_storage: 'denied',
      wait_for_update: 500,
    },
  ]);
  expect((window as AnalyticsWindow)['ga-disable-G-8L85Z825PE']).not.toBe(true);

  root.unmount();
});

it('쿠키 선택을 저장하고 분석 저장 동의만 갱신한다', async () => {
  const element = document.createElement('div');
  document.body.append(element);
  const root = createRoot(element);

  await act(async () => root.render(<GoogleAnalyticsConsent />));
  await act(async () => {
    element.querySelector<HTMLButtonElement>('button[data-consent="granted"]')?.click();
  });

  expect(window.sessionStorage.getItem('home-search.analytics-consent')).toBe('granted');
  expect((window as AnalyticsWindow).dataLayer?.at(-1)).toEqual([
    'consent',
    'update',
    {
      ad_personalization: 'denied',
      ad_storage: 'denied',
      ad_user_data: 'denied',
      analytics_storage: 'granted',
    },
  ]);

  await act(async () => {
    window.dispatchEvent(new Event('home-search:open-analytics-consent'));
  });
  await act(async () => {
    element.querySelector<HTMLButtonElement>('button[data-consent="denied"]')?.click();
  });

  expect(window.sessionStorage.getItem('home-search.analytics-consent')).toBe('denied');
  expect((window as AnalyticsWindow).dataLayer?.at(-1)).toEqual([
    'consent',
    'update',
    {
      ad_personalization: 'denied',
      ad_storage: 'denied',
      ad_user_data: 'denied',
      analytics_storage: 'denied',
    },
  ]);
  expect(document.getElementById('home-search-google-tag')).not.toBeNull();

  root.unmount();
});
