export type UserFeedbackId =
  | 'MAP_MARKERS_UNAVAILABLE'
  | 'MAP_RUNTIME_UNAVAILABLE'
  | 'REGION_UNAVAILABLE'
  | 'REGION_REFRESH_UNAVAILABLE'
  | 'REGION_NOT_FOUND'
  | 'SEARCH_UNAVAILABLE'
  | 'COMPLEX_UNAVAILABLE'
  | 'COMPLEX_NOT_FOUND'
  | 'TRADES_UNAVAILABLE'
  | 'TRADES_MORE_UNAVAILABLE'
  | 'TREND_UNAVAILABLE'
  | 'PREDICTION_FAILED'
  | 'PREDICTION_UNAVAILABLE'
  | 'NEARBY_PARTIAL'
  | 'NEARBY_UNAVAILABLE'
  | 'AUTH_EXPIRED'
  | 'AUTH_UNAVAILABLE'
  | 'LOGOUT_FAILED'
  | 'FAVORITE_LIMIT_REACHED'
  | 'FAVORITE_STATUS_UNAVAILABLE'
  | 'FAVORITES_UNAVAILABLE'
  | 'FAVORITES_REFRESH_UNAVAILABLE'
  | 'FAVORITES_MORE_UNAVAILABLE'
  | 'FAVORITE_DETAIL_UNAVAILABLE'
  | 'FAVORITE_SAVE_FAILED'
  | 'FAVORITE_REMOVE_FAILED'
  | 'CHAT_TIMEOUT'
  | 'CHAT_RATE_LIMITED'
  | 'CHAT_UNAVAILABLE'
  | 'CHAT_INVALID_RESPONSE'
  | 'CHAT_STORAGE_UNAVAILABLE'
  | 'CHAT_HISTORY_UPDATE_FAILED'
  | 'CHAT_ARCHIVE_INVALID'
  | 'CHAT_EXPORT_FAILED'
  | 'ROADVIEW_UNAVAILABLE'
  | 'APP_RENDER_FAILED'
  | 'FEATURE_RENDER_FAILED'
  | 'UNEXPECTED_FAILURE';

export type UserFeedbackDefinition = Readonly<{
  tone: 'error' | 'warning' | 'info';
  title: string;
  description?: string;
  actionLabel?: string;
  announcement: 'alert' | 'status' | 'none';
}>;

export const USER_FEEDBACK_CATALOG: Readonly<Record<UserFeedbackId, UserFeedbackDefinition>> = {
  MAP_MARKERS_UNAVAILABLE: {
    tone: 'warning',
    title: '이 지역의 단지 정보를 새로 불러오지 못했어요',
    description: '지도 이동과 확대·축소는 계속 사용할 수 있어요.',
    actionLabel: '단지 다시 불러오기',
    announcement: 'status',
  },
  MAP_RUNTIME_UNAVAILABLE: {
    tone: 'error',
    title: '지도를 준비하지 못했어요',
    description: '단지 목록이나 대체 마커가 있으면 계속 탐색할 수 있어요.',
    actionLabel: '지도 다시 불러오기',
    announcement: 'status',
  },
  REGION_UNAVAILABLE: {
    tone: 'warning',
    title: '지역 정보를 불러오지 못했어요',
    description: '지도와 검색은 계속 사용할 수 있어요.',
    actionLabel: '지역 다시 불러오기',
    announcement: 'status',
  },
  REGION_REFRESH_UNAVAILABLE: {
    tone: 'warning',
    title: '지역 정보를 새로 불러오지 못했어요',
    description: '이전에 불러온 지역은 계속 볼 수 있어요.',
    actionLabel: '지역 다시 불러오기',
    announcement: 'status',
  },
  REGION_NOT_FOUND: {
    tone: 'info',
    title: '선택한 지역을 찾을 수 없어요',
    description: '다른 지역을 선택해주세요.',
    actionLabel: '시도 선택으로 돌아가기',
    announcement: 'status',
  },
  SEARCH_UNAVAILABLE: {
    tone: 'warning',
    title: '검색 결과를 불러오지 못했어요',
    description: '입력한 검색어는 그대로 유지돼요.',
    actionLabel: '검색 다시 실행',
    announcement: 'status',
  },
  COMPLEX_UNAVAILABLE: {
    tone: 'warning',
    title: '단지 정보를 불러오지 못했어요',
    description: '지도와 다른 단지는 계속 볼 수 있어요.',
    actionLabel: '단지 다시 불러오기',
    announcement: 'status',
  },
  COMPLEX_NOT_FOUND: {
    tone: 'info',
    title: '선택한 단지를 찾을 수 없어요',
    description: '삭제되었거나 현재 제공되지 않는 단지일 수 있어요.',
    actionLabel: '목록으로 돌아가기',
    announcement: 'status',
  },
  TRADES_UNAVAILABLE: {
    tone: 'warning',
    title: '최근 거래를 불러오지 못했어요',
    description: '단지 기본정보는 계속 확인할 수 있어요.',
    actionLabel: '거래 다시 불러오기',
    announcement: 'status',
  },
  TRADES_MORE_UNAVAILABLE: {
    tone: 'warning',
    title: '거래를 더 불러오지 못했어요',
    description: '이미 불러온 거래는 그대로 보여드리고 있어요.',
    actionLabel: '거래 이어서 불러오기',
    announcement: 'status',
  },
  TREND_UNAVAILABLE: {
    tone: 'warning',
    title: '가격 흐름을 불러오지 못했어요',
    description: '최근 거래와 단지 정보는 계속 볼 수 있어요.',
    actionLabel: '가격 흐름 다시 불러오기',
    announcement: 'status',
  },
  PREDICTION_FAILED: {
    tone: 'warning',
    title: 'AI 예상가를 준비하지 못했어요',
    description: '최근 거래와 가격 흐름은 계속 확인할 수 있어요.',
    announcement: 'status',
  },
  PREDICTION_UNAVAILABLE: {
    tone: 'info',
    title: 'AI 예상가를 계산할 거래 정보가 부족해요',
    description: '확인된 최근 거래와 가격 흐름은 계속 볼 수 있어요.',
    announcement: 'status',
  },
  NEARBY_PARTIAL: {
    tone: 'warning',
    title: '일부 주변시설은 새로 확인하지 못했어요',
    description: '확인된 시설 정보는 그대로 보여드리고 있어요.',
    actionLabel: '주변시설 다시 확인',
    announcement: 'status',
  },
  NEARBY_UNAVAILABLE: {
    tone: 'warning',
    title: '주변시설을 불러오지 못했어요',
    description: '지도와 단지 정보는 계속 사용할 수 있어요.',
    actionLabel: '주변시설 다시 확인',
    announcement: 'status',
  },
  AUTH_EXPIRED: {
    tone: 'error',
    title: '로그인이 만료되었어요',
    description: '관심 단지 기능을 계속 사용하려면 다시 로그인해주세요.',
    actionLabel: '다시 로그인',
    announcement: 'alert',
  },
  AUTH_UNAVAILABLE: {
    tone: 'error',
    title: '지금은 로그인을 연결하기 어려워요',
    description: '지도와 단지 검색은 계속 사용할 수 있어요.',
    actionLabel: '로그인 다시 시도',
    announcement: 'alert',
  },
  LOGOUT_FAILED: {
    tone: 'error',
    title: '로그아웃을 완료하지 못했어요',
    description: '화면의 로그인 정보는 닫았지만 계정 연결 상태를 다시 확인해주세요.',
    announcement: 'alert',
  },
  FAVORITE_LIMIT_REACHED: {
    tone: 'info',
    title: '관심 단지는 최대 200곳까지 저장할 수 있어요',
    description: '다른 단지를 저장하려면 기존 관심 단지를 해제해주세요.',
    announcement: 'status',
  },
  FAVORITE_STATUS_UNAVAILABLE: {
    tone: 'warning',
    title: '관심 상태를 확인하지 못했어요',
    description: '단지 정보는 계속 확인할 수 있어요.',
    actionLabel: '관심 상태 다시 확인',
    announcement: 'status',
  },
  FAVORITES_UNAVAILABLE: {
    tone: 'warning',
    title: '관심 단지를 불러오지 못했어요',
    description: '지도와 단지 검색은 계속 사용할 수 있어요.',
    actionLabel: '관심 단지 다시 불러오기',
    announcement: 'status',
  },
  FAVORITES_REFRESH_UNAVAILABLE: {
    tone: 'warning',
    title: '관심 단지를 새로 확인하지 못했어요',
    description: '이미 불러온 관심 단지는 그대로 보여드리고 있어요.',
    actionLabel: '관심 단지 새로 확인',
    announcement: 'status',
  },
  FAVORITES_MORE_UNAVAILABLE: {
    tone: 'warning',
    title: '관심 단지를 더 불러오지 못했어요',
    description: '이미 불러온 단지는 그대로 보여드리고 있어요.',
    actionLabel: '목록 이어서 불러오기',
    announcement: 'status',
  },
  FAVORITE_DETAIL_UNAVAILABLE: {
    tone: 'warning',
    title: '단지 정보를 확인하지 못했어요',
    description: '관심 등록 날짜와 해제 기능은 계속 사용할 수 있어요.',
    actionLabel: '단지 정보 다시 확인',
    announcement: 'status',
  },
  FAVORITE_SAVE_FAILED: {
    tone: 'error',
    title: '관심 단지에 저장하지 못했어요',
    description: '현재 관심 상태는 변경되지 않았어요.',
    actionLabel: '다시 시도',
    announcement: 'alert',
  },
  FAVORITE_REMOVE_FAILED: {
    tone: 'error',
    title: '관심 단지에서 해제하지 못했어요',
    description: '기존 관심 상태를 유지하고 있어요.',
    actionLabel: '다시 시도',
    announcement: 'alert',
  },
  CHAT_TIMEOUT: {
    tone: 'warning',
    title: '답변을 준비하는 데 시간이 더 걸리고 있어요',
    description: '작성한 질문은 그대로 유지돼요.',
    actionLabel: '다시 보내기',
    announcement: 'status',
  },
  CHAT_RATE_LIMITED: {
    tone: 'warning',
    title: '요청이 잠시 몰리고 있어요',
    description: '잠시 후 같은 질문을 다시 보낼 수 있어요.',
    actionLabel: '다시 보내기',
    announcement: 'status',
  },
  CHAT_UNAVAILABLE: {
    tone: 'warning',
    title: '지금은 답변을 준비하지 못했어요',
    description: '작성한 질문은 그대로 유지돼요.',
    actionLabel: '다시 보내기',
    announcement: 'status',
  },
  CHAT_INVALID_RESPONSE: {
    tone: 'warning',
    title: '답변 내용을 확인하지 못했어요',
    description: '작성한 질문은 그대로 유지돼요.',
    actionLabel: '다시 보내기',
    announcement: 'status',
  },
  CHAT_STORAGE_UNAVAILABLE: {
    tone: 'warning',
    title: '이 브라우저에서는 대화를 저장할 수 없어요',
    description: '현재 대화는 계속할 수 있지만 다시 열면 남지 않을 수 있어요.',
    announcement: 'status',
  },
  CHAT_HISTORY_UPDATE_FAILED: {
    tone: 'warning',
    title: '대화 기록을 변경하지 못했어요',
    description: '현재 대화는 그대로 유지하고 있어요.',
    announcement: 'status',
  },
  CHAT_ARCHIVE_INVALID: {
    tone: 'info',
    title: '가져올 대화 파일을 확인해주세요',
    description: '홈서치에서 내보낸 10MB 이하의 파일을 선택할 수 있어요.',
    announcement: 'status',
  },
  CHAT_EXPORT_FAILED: {
    tone: 'warning',
    title: '대화를 내보내지 못했어요',
    description: '현재 대화와 저장된 기록은 그대로 유지하고 있어요.',
    announcement: 'status',
  },
  ROADVIEW_UNAVAILABLE: {
    tone: 'warning',
    title: '거리뷰를 불러오지 못했어요',
    description: '지도와 단지 정보는 계속 사용할 수 있어요.',
    actionLabel: '거리뷰 닫기',
    announcement: 'status',
  },
  APP_RENDER_FAILED: {
    tone: 'error',
    title: '홈서치를 표시하지 못했어요',
    description: '페이지를 새로 불러오면 다시 시작할 수 있어요.',
    actionLabel: '페이지 새로고침',
    announcement: 'alert',
  },
  FEATURE_RENDER_FAILED: {
    tone: 'error',
    title: '이 영역을 표시하지 못했어요',
    description: '지도와 다른 기능은 계속 사용할 수 있어요.',
    actionLabel: '이 영역 다시 열기',
    announcement: 'alert',
  },
  UNEXPECTED_FAILURE: {
    tone: 'error',
    title: '요청을 완료하지 못했어요',
    description: '현재 화면의 다른 기능은 계속 사용할 수 있어요.',
    actionLabel: '다시 불러오기',
    announcement: 'status',
  },
};

export function getUserFeedback(id: UserFeedbackId): UserFeedbackDefinition {
  return USER_FEEDBACK_CATALOG[id];
}
