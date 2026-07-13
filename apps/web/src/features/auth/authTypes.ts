export type AuthStatus = 'checking' | 'anonymous' | 'authenticated' | 'unavailable';

export type OAuthProvider = 'google' | 'kakao' | 'naver';

export type CurrentUser = {
  userId: number;
  provider: OAuthProvider;
  displayName: string;
  profileImage: string | null;
};

export const AUTH_MESSAGES = {
  callbackFailure: '로그인을 완료하지 못했습니다. 다시 시도해주세요.',
  loginSuccess: '로그인되었습니다.',
  logoutFailure: '로그아웃하지 못했습니다. 다시 시도해주세요.',
  serviceUnavailable: '로그인 서비스를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.',
  sessionExpired: '로그인이 만료되었습니다. 다시 로그인해주세요.',
} as const;
