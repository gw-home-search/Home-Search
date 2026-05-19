export async function readProblemDetail(response: Response): Promise<string | null> {
  try {
    const payload: unknown = await response.json();
    if (!isRecord(payload)) {
      return null;
    }

    return toMessage(payload.detail) ?? toMessage(payload.title);
  } catch {
    return null;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function toMessage(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null;
  }

  const message = value.trim();
  return message.length > 0 ? message : null;
}
