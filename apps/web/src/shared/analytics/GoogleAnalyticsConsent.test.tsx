import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, expect, it } from 'vitest';

import { GoogleAnalyticsConsent } from './GoogleAnalyticsConsent';

afterEach(() => {
  document.body.innerHTML = '';
  window.sessionStorage.clear();
});

it('사용자가 허용하기 전에는 Google tag를 로드하지 않는다', async () => {
  const element = document.createElement('div');
  document.body.append(element);
  const root = createRoot(element);

  await act(async () => root.render(<GoogleAnalyticsConsent />));

  expect(document.querySelector('script[src*="googletagmanager.com/gtag/js"]')).toBeNull();

  await act(async () => {
    element.querySelector<HTMLButtonElement>('button[data-consent="granted"]')?.click();
  });

  expect(document.querySelector('script[src="https://www.googletagmanager.com/gtag/js?id=G-8L85Z825PE"]')).not.toBeNull();
  expect(window.sessionStorage.getItem('home-search.analytics-consent')).toBe('granted');

  await act(async () => {
    window.dispatchEvent(new Event('home-search:open-analytics-consent'));
  });
  await act(async () => {
    element.querySelector<HTMLButtonElement>('button[data-consent="denied"]')?.click();
  });

  expect((window as Window & { 'ga-disable-G-8L85Z825PE'?: boolean })['ga-disable-G-8L85Z825PE']).toBe(true);
  expect(window.sessionStorage.getItem('home-search.analytics-consent')).toBe('denied');
  root.unmount();
});
