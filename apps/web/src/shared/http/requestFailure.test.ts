import { describe, expect, it } from 'vitest';

import {
  RequestFailureError,
  requestFailureFromResponse,
  toRequestFailure,
} from './requestFailure';

const CONTEXT = {
  service: 'property-data' as const,
  operation: 'complex-detail',
};

describe('request failure 분류', () => {
  it('ProblemDetail 원문을 버리고 상태와 안전한 code만 보존한다', async () => {
    const response = new Response(JSON.stringify({
      title: 'C500',
      status: 500,
      detail: 'Internal server error. https://private.example/api/key',
      exception: 'org.springframework.jdbc.BadSqlGrammarException',
    }), {
      status: 500,
      headers: {
        'Content-Type': 'application/problem+json',
      },
    });

    const error = await requestFailureFromResponse(response, CONTEXT);

    expect(error).toBeInstanceOf(RequestFailureError);
    expect(error.failure).toEqual({
      kind: 'service-unavailable',
      service: 'property-data',
      operation: 'complex-detail',
      status: 500,
      code: 'C500',
    });
    expect(JSON.stringify(error.failure)).not.toContain('Internal server');
    expect(JSON.stringify(error.failure)).not.toContain('springframework');
  });

  it('caller abort는 cancelled로 분류하고 abort reason을 보존하지 않는다', () => {
    const controller = new AbortController();
    controller.abort(new Error('signal is aborted without reason'));

    const failure = toRequestFailure(
      new DOMException('signal is aborted without reason', 'AbortError'),
      CONTEXT,
      controller.signal,
    );

    expect(failure).toEqual({
      kind: 'cancelled',
      service: 'property-data',
      operation: 'complex-detail',
    });
    expect(JSON.stringify(failure)).not.toContain('signal is aborted');
  });

  it('TimeoutError는 timeout으로 분류한다', () => {
    const failure = toRequestFailure(
      new DOMException('The operation timed out', 'TimeoutError'),
      CONTEXT,
    );

    expect(failure.kind).toBe('timeout');
  });

  it.each([408, 504])('HTTP %s는 재시도 가능한 timeout으로 분류한다', async (status) => {
    const error = await requestFailureFromResponse(new Response(null, { status }), {
      service: 'chatbot',
      operation: 'chatbot-query',
    });

    expect(error.failure.kind).toBe('timeout');
  });
});
