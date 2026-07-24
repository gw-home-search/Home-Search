export const PROPERTY_API_TIMEOUT_MS = 15_000;

export async function fetchWithTimeout(
  input: RequestInfo | URL,
  init: RequestInit = {},
  timeoutMs = PROPERTY_API_TIMEOUT_MS,
): Promise<Response> {
  const callerSignal = init.signal;
  if (callerSignal?.aborted) throw abortError();

  const controller = new AbortController();
  let timedOut = false;
  let callerAbortHandler: (() => void) | null = null;
  let timeoutId: number | null = null;

  const timeout = new Promise<never>((_, reject) => {
    timeoutId = window.setTimeout(() => {
      timedOut = true;
      controller.abort();
      reject(timeoutError());
    }, timeoutMs);
  });

  const callerAbort = new Promise<never>((_, reject) => {
    if (callerSignal == null) return;
    callerAbortHandler = () => {
      controller.abort();
      reject(abortError());
    };
    callerSignal.addEventListener('abort', callerAbortHandler, { once: true });
  });

  try {
    return await Promise.race([
      fetch(input, { ...init, signal: controller.signal }),
      timeout,
      callerAbort,
    ]);
  } catch (error) {
    if (timedOut) throw timeoutError();
    if (callerSignal?.aborted) throw abortError();
    throw error;
  } finally {
    if (timeoutId != null) window.clearTimeout(timeoutId);
    if (callerSignal && callerAbortHandler) {
      callerSignal.removeEventListener('abort', callerAbortHandler);
    }
  }
}

function timeoutError(): DOMException {
  return new DOMException('Request timed out', 'TimeoutError');
}

function abortError(): DOMException {
  return new DOMException('Request cancelled', 'AbortError');
}
