import { StrictMode } from 'react';
import { createRoot, hydrateRoot } from 'react-dom/client';

import { App } from './app/App';
import { RootErrorBoundary } from './shared/RootErrorBoundary';
import { SeoLandingContent } from './seo/SeoLandingContent';
import { LegalPage, type LegalPageKind } from './legal/LegalPage';
import { GoogleAnalyticsConsent } from './shared/analytics/GoogleAnalyticsConsent';
import './seo/seo.css';

const rootElement = document.getElementById('root');
const seoPage = readSeoPage();
const legalPage = readLegalPage();

if (rootElement) {
  const content = seoPage ? <SeoLandingContent page={seoPage} /> : legalPage ? <><LegalPage kind={legalPage} /><GoogleAnalyticsConsent /></> : (
    <StrictMode>
      <RootErrorBoundary>
        <App />
      </RootErrorBoundary>
    </StrictMode>);
  if (seoPage || legalPage) hydrateRoot(rootElement, content);
  else createRoot(rootElement).render(content);
}

function readLegalPage(): LegalPageKind | undefined {
  const value = document.getElementById('home-legal-page')?.textContent;
  return value === 'privacy' || value === 'terms' || value === 'about' ? value : undefined;
}

function readSeoPage() {
  const payload = document.getElementById('home-seo-page')?.textContent;
  if (!payload) return undefined;
  try {
    return JSON.parse(payload) as import('./seo/types').SeoPage;
  } catch {
    return undefined;
  }
}
