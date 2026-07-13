import type { OAuthProvider } from './authTypes';

// Marks follow the official Kakao, Naver, and Google login-button branding guides.
export function SocialProviderIcon({ provider }: { provider: OAuthProvider }) {
  if (provider === 'kakao') {
    return (
      <svg aria-hidden="true" className="auth-provider-icon" data-provider-icon="kakao" viewBox="0 0 24 24">
        <path d="M12 3.5c-5.25 0-9.5 3.33-9.5 7.44 0 2.65 1.77 4.97 4.46 6.3l-.9 3.34a.5.5 0 0 0 .75.54l4.05-2.68c.38.04.76.06 1.14.06 5.25 0 9.5-3.33 9.5-7.56S17.25 3.5 12 3.5Z" fill="currentColor" />
      </svg>
    );
  }

  if (provider === 'naver') {
    return (
      <svg aria-hidden="true" className="auth-provider-icon" data-provider-icon="naver" viewBox="0 0 24 24">
        <path d="M16.273 12.845 7.376 0H0v24h7.727V11.155L16.624 24H24V0h-7.727v12.845Z" fill="currentColor" />
      </svg>
    );
  }

  return (
    <svg aria-hidden="true" className="auth-provider-icon" data-provider-icon="google" viewBox="0 0 24 24">
      <path className="auth-google-blue" d="M21.6 12.23c0-.71-.06-1.4-.18-2.05H12v3.87h5.38a4.6 4.6 0 0 1-2 3.02v2.51h3.24c1.9-1.75 2.98-4.33 2.98-7.35Z" />
      <path className="auth-google-green" d="M12 22c2.7 0 4.96-.9 6.62-2.42l-3.24-2.51c-.9.6-2.05.96-3.38.96-2.6 0-4.81-1.76-5.73-4.2H3.05v2.59A10 10 0 0 0 12 22Z" />
      <path className="auth-google-yellow" d="M6.27 13.83A6.01 6.01 0 0 1 5.95 12c0-.64.11-1.26.32-1.83V7.58H3.05A10 10 0 0 0 2 12c0 1.61.38 3.14 1.05 4.42l3.22-2.59Z" />
      <path className="auth-google-red" d="M12 5.97c1.47 0 2.79.5 3.83 1.5L18.7 4.6A9.62 9.62 0 0 0 12 2a10 10 0 0 0-8.95 5.58l3.22 2.59C7.19 7.73 9.4 5.97 12 5.97Z" />
    </svg>
  );
}
