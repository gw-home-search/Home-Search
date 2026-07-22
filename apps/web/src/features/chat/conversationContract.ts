export type ConversationMemory = {
  version: 1;
  complexId?: number;
  regionCode?: string;
  scopeKind: 'COMPLEX' | 'ADMIN_REGION' | 'MAP_VIEWPORT';
} | {
  version: 2;
  complexIds: number[];
  regionCode?: string;
  scopeKind: 'RECOMMENDATION';
};

export type ChatConversationResolution = {
  version: 1;
  answerMode: 'COMPLETE' | 'BEST_EFFORT' | 'PARTIAL' | 'NO_RESULT';
  goals: Array<{
    capability: string;
    status: 'answered' | 'degraded' | 'unavailable';
  }>;
  assumptions: Array<{ code: string; text: string }>;
  omissions: string[];
};

export type ChatUiContext = {
  mapViewport?: {
    bounds: { swLat: number; swLng: number; neLat: number; neLng: number };
    level: number;
  };
  selectedComplex?: { complexId: number; parcelId: number };
};

export function readConversationResolution(value: unknown): ChatConversationResolution | null {
  if (!isRecord(value)
    || value.version !== 1
    || !['COMPLETE', 'BEST_EFFORT', 'PARTIAL', 'NO_RESULT'].includes(String(value.answerMode))
    || !Array.isArray(value.goals)
    || value.goals.length > 4
    || !value.goals.every(isGoal)
    || !Array.isArray(value.assumptions)
    || value.assumptions.length > 8
    || !value.assumptions.every(isAssumption)
    || !Array.isArray(value.omissions)
    || value.omissions.length > 8
    || !value.omissions.every((item) => isBoundedText(item, 2_000))) return null;
  return value as ChatConversationResolution;
}

export function readConversationMemory(value: unknown): ConversationMemory | null {
  if (!isRecord(value)) return null;
  if (value.version === 2) {
    if (value.scopeKind !== 'RECOMMENDATION'
      || !Array.isArray(value.complexIds)
      || value.complexIds.length < 2
      || value.complexIds.length > 5
      || !value.complexIds.every(isPositiveSafeInteger)
      || new Set(value.complexIds).size !== value.complexIds.length
      || (value.regionCode !== undefined
        && (typeof value.regionCode !== 'string'
          || !/^[0-9]{2,10}$/.test(value.regionCode)))) return null;
    return {
      version: 2,
      scopeKind: 'RECOMMENDATION',
      complexIds: value.complexIds,
      ...(typeof value.regionCode === 'string' ? { regionCode: value.regionCode } : {}),
    };
  }
  if (value.version !== 1
    || !['COMPLEX', 'ADMIN_REGION', 'MAP_VIEWPORT'].includes(String(value.scopeKind))
    || (value.complexId !== undefined && !isPositiveSafeInteger(value.complexId))
    || (value.regionCode !== undefined
      && (typeof value.regionCode !== 'string' || !/^[0-9]{2,10}$/.test(value.regionCode)))) return null;
  if (value.scopeKind === 'COMPLEX' && !isPositiveSafeInteger(value.complexId)) return null;
  if (value.scopeKind === 'ADMIN_REGION'
    && (typeof value.regionCode !== 'string' || !/^[0-9]{2,10}$/.test(value.regionCode))) return null;
  return value as ConversationMemory;
}

export function normalizeUiContext(value: ChatUiContext | undefined): ChatUiContext | undefined {
  if (value == null) return undefined;
  const mapViewport = isValidViewport(value.mapViewport) ? value.mapViewport : undefined;
  const selectedComplex = isValidSelectedComplex(value.selectedComplex)
    ? value.selectedComplex
    : undefined;
  if (mapViewport == null && selectedComplex == null) return undefined;
  return { ...(mapViewport ? { mapViewport } : {}), ...(selectedComplex ? { selectedComplex } : {}) };
}

function isGoal(value: unknown): boolean {
  return isRecord(value)
    && isIdentifier(value.capability)
    && ['answered', 'degraded', 'unavailable'].includes(String(value.status));
}

function isAssumption(value: unknown): boolean {
  return isRecord(value) && isIdentifier(value.code) && isBoundedText(value.text, 500);
}

function isValidViewport(value: ChatUiContext['mapViewport'] | undefined): boolean {
  if (value == null || !Number.isInteger(value.level) || value.level < 1 || value.level > 12) return false;
  const { swLat, swLng, neLat, neLng } = value.bounds;
  return [swLat, swLng, neLat, neLng].every(Number.isFinite)
    && swLat >= 33 && neLat <= 39 && swLng >= 124 && neLng <= 132
    && swLat < neLat && swLng < neLng && neLat - swLat <= 6 && neLng - swLng <= 8;
}

function isValidSelectedComplex(value: ChatUiContext['selectedComplex'] | undefined): boolean {
  return value != null
    && isPositiveSafeInteger(value.complexId)
    && isPositiveSafeInteger(value.parcelId);
}

function isPositiveSafeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) > 0;
}

function isIdentifier(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/.test(value);
}

function isBoundedText(value: unknown, maximum: number): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= maximum;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}
