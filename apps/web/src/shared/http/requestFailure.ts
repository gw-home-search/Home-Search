export type RequestFailureKind =
  | 'cancelled'
  | 'network'
  | 'timeout'
  | 'rate-limited'
  | 'authentication-required'
  | 'forbidden'
  | 'invalid-request'
  | 'not-found'
  | 'conflict'
  | 'service-unavailable'
  | 'invalid-response'
  | 'unexpected';

export type RequestFailureService =
  | 'property-data'
  | 'user-service'
  | 'chatbot'
  | 'kakao-map'
  | 'browser-storage'
  | 'client';

export type RequestFailureContext = Readonly<{
  service: RequestFailureService;
  operation: string;
}>;

export type RequestFailure = Readonly<{
  kind: RequestFailureKind;
  service: RequestFailureService;
  operation: string;
  status?: number;
  code?: string;
  retryAfterSeconds?: number;
}>;

export class RequestFailureError extends Error {
  readonly failure: RequestFailure;

  constructor(failure: RequestFailure) {
    super('Request failed');
    this.name = 'RequestFailureError';
    this.failure = failure;
  }
}

export async function requestFailureFromResponse(
  response: Response,
  context: RequestFailureContext,
): Promise<RequestFailureError> {
  const metadata = await readProblemMetadata(response);
  const retryAfterSeconds = readRetryAfterSeconds(response.headers?.get?.('Retry-After') ?? null);
  return new RequestFailureError({
    kind: failureKindForStatus(response.status),
    ...context,
    status: response.status,
    ...(metadata.code ? { code: metadata.code } : {}),
    ...(retryAfterSeconds == null ? {} : { retryAfterSeconds }),
  });
}

export function invalidResponseFailure(context: RequestFailureContext): RequestFailureError {
  return new RequestFailureError({ kind: 'invalid-response', ...context });
}

export async function readValidatedJson<T>(
  response: Response,
  context: RequestFailureContext,
  validate: (value: unknown) => T,
): Promise<T> {
  try {
    return validate(await response.json());
  } catch (error) {
    if (error instanceof RequestFailureError) throw error;
    throw invalidResponseFailure(context);
  }
}

export function toRequestFailure(
  error: unknown,
  context: RequestFailureContext,
  signal?: AbortSignal,
): RequestFailure {
  if (error instanceof RequestFailureError) {
    return error.failure;
  }
  if (signal?.aborted || isNamedError(error, 'AbortError')) {
    return { kind: 'cancelled', ...context };
  }
  if (isNamedError(error, 'TimeoutError')) {
    return { kind: 'timeout', ...context };
  }
  if (error instanceof TypeError) {
    return { kind: 'network', ...context };
  }
  return { kind: 'unexpected', ...context };
}

export function isCancelledFailure(failure: RequestFailure): boolean {
  return failure.kind === 'cancelled';
}

function failureKindForStatus(status: number): RequestFailureKind {
  if (status === 408 || status === 504) return 'timeout';
  if (status === 400 || status === 422) return 'invalid-request';
  if (status === 401) return 'authentication-required';
  if (status === 403) return 'forbidden';
  if (status === 404) return 'not-found';
  if (status === 409) return 'conflict';
  if (status === 429) return 'rate-limited';
  if (status >= 500) return 'service-unavailable';
  return 'unexpected';
}

async function readProblemMetadata(response: Response): Promise<{ code: string | null }> {
  try {
    const readable = typeof response.clone === 'function' ? response.clone() : response;
    const value: unknown = await readable.json();
    if (!isRecord(value)) return { code: null };
    return { code: readSafeCode(value.code) ?? readSafeCode(value.title) };
  } catch {
    return { code: null };
  }
}

function readSafeCode(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const code = value.trim();
  return /^[A-Z][A-Z0-9_:-]{0,63}$/u.test(code) ? code : null;
}

function readRetryAfterSeconds(value: string | null): number | null {
  if (value == null || !/^\d{1,6}$/u.test(value)) return null;
  const seconds = Number(value);
  return Number.isSafeInteger(seconds) ? seconds : null;
}

function isNamedError(error: unknown, name: string): boolean {
  return typeof error === 'object'
    && error != null
    && 'name' in error
    && error.name === name;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}
