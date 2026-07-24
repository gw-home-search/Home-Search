import type { RequestFailure } from '../http/requestFailure';
import {
  getUserFeedback,
  type UserFeedbackDefinition,
  type UserFeedbackId,
} from './feedbackCatalog';

export function feedbackForFailure(
  failure: RequestFailure | null,
  fallbackId: UserFeedbackId,
  options: Readonly<{
    notFoundId?: UserFeedbackId;
    timeoutId?: UserFeedbackId;
    rateLimitedId?: UserFeedbackId;
    invalidResponseId?: UserFeedbackId;
  }> = {},
): UserFeedbackDefinition {
  if (failure?.kind === 'not-found' && options.notFoundId) {
    return getUserFeedback(options.notFoundId);
  }
  if (failure?.kind === 'timeout' && options.timeoutId) {
    return getUserFeedback(options.timeoutId);
  }
  if (failure?.kind === 'rate-limited' && options.rateLimitedId) {
    return getUserFeedback(options.rateLimitedId);
  }
  if (failure?.kind === 'invalid-response' && options.invalidResponseId) {
    return getUserFeedback(options.invalidResponseId);
  }
  return getUserFeedback(fallbackId);
}
