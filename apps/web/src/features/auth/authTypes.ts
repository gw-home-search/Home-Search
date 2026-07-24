export type AuthStatus = 'checking' | 'anonymous' | 'authenticated' | 'unavailable';

export type OAuthProvider = 'google' | 'kakao' | 'naver';

export type CurrentUser = {
  userId: number;
  provider: OAuthProvider;
  displayName: string;
  profileImage: string | null;
};

export const AUTH_LOGIN_SUCCESS_MESSAGE = '로그인되었습니다.';
