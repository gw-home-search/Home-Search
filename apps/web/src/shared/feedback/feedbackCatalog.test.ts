import { describe, expect, it } from 'vitest';

import {
  USER_FEEDBACK_CATALOG,
  getUserFeedback,
  type UserFeedbackId,
} from './feedbackCatalog';

const EXPECTED_IDS: UserFeedbackId[] = [
  'MAP_MARKERS_UNAVAILABLE',
  'MAP_RUNTIME_UNAVAILABLE',
  'REGION_UNAVAILABLE',
  'REGION_REFRESH_UNAVAILABLE',
  'REGION_NOT_FOUND',
  'SEARCH_UNAVAILABLE',
  'COMPLEX_UNAVAILABLE',
  'COMPLEX_NOT_FOUND',
  'TRADES_UNAVAILABLE',
  'TRADES_MORE_UNAVAILABLE',
  'TREND_UNAVAILABLE',
  'PREDICTION_FAILED',
  'PREDICTION_UNAVAILABLE',
  'NEARBY_PARTIAL',
  'NEARBY_UNAVAILABLE',
  'AUTH_EXPIRED',
  'AUTH_UNAVAILABLE',
  'LOGOUT_FAILED',
  'FAVORITE_LIMIT_REACHED',
  'FAVORITE_STATUS_UNAVAILABLE',
  'FAVORITES_UNAVAILABLE',
  'FAVORITES_REFRESH_UNAVAILABLE',
  'FAVORITES_MORE_UNAVAILABLE',
  'FAVORITE_DETAIL_UNAVAILABLE',
  'FAVORITE_SAVE_FAILED',
  'FAVORITE_REMOVE_FAILED',
  'CHAT_TIMEOUT',
  'CHAT_RATE_LIMITED',
  'CHAT_UNAVAILABLE',
  'CHAT_INVALID_RESPONSE',
  'CHAT_STORAGE_UNAVAILABLE',
  'CHAT_HISTORY_UPDATE_FAILED',
  'CHAT_ARCHIVE_INVALID',
  'CHAT_EXPORT_FAILED',
  'ROADVIEW_UNAVAILABLE',
  'APP_RENDER_FAILED',
  'FEATURE_RENDER_FAILED',
  'UNEXPECTED_FAILURE',
];

const FORBIDDEN_COPY = [
  /HTTP/iu,
  /Internal server/iu,
  /Exception/iu,
  /Error:/iu,
  /Failed to/iu,
  /signal is aborted/iu,
  /localhost/iu,
  /\/api\//iu,
  /https?:\/\//iu,
  /java\./iu,
  /org\.springframework/iu,
  /\bstack\b/iu,
];

describe('사용자 feedback catalog', () => {
  it('모든 허용 ID를 빠짐없이 제공한다', () => {
    expect(Object.keys(USER_FEEDBACK_CATALOG).sort()).toEqual([...EXPECTED_IDS].sort());
  });

  it.each(EXPECTED_IDS)('%s 문구는 기술 정보를 포함하지 않는다', (id) => {
    const feedback = getUserFeedback(id);
    const visibleCopy = [feedback.title, feedback.description, feedback.actionLabel]
      .filter(Boolean)
      .join(' ');

    expect(feedback.title.trim().length).toBeGreaterThan(0);
    for (const pattern of FORBIDDEN_COPY) {
      expect(visibleCopy).not.toMatch(pattern);
    }
  });
});
