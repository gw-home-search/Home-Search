import { StrictMode } from 'react';
import { createRoot, hydrateRoot } from 'react-dom/client';

import { App } from './app/App';
import { RootErrorBoundary } from './shared/RootErrorBoundary';
import { LegalPage, type LegalPageKind } from './legal/LegalPage';
import { GoogleAnalyticsConsent } from './shared/analytics/GoogleAnalyticsConsent';
import './seo/seo.css';

const rootElement = document.getElementById('root');
const staticSeoPage = document.getElementById('home-seo-static') != null;
const legalPage = readLegalPage();

if (rootElement && !staticSeoPage) {
  const content = legalPage ? <><LegalPage kind={legalPage} /><GoogleAnalyticsConsent /></> : (
    <StrictMode>
      <RootErrorBoundary>
        <App />
      </RootErrorBoundary>
    </StrictMode>);
  if (legalPage) hydrateRoot(rootElement, content);
  else createRoot(rootElement).render(content);
}

function readLegalPage(): LegalPageKind | undefined {
  const value = document.getElementById('home-legal-page')?.textContent;
  return value === 'privacy' || value === 'terms' || value === 'about' ? value : undefined;
}
