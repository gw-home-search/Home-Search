export type ShowNearbyCategoryAction = {
  type: 'showNearbyCategory';
  version: 1;
  actionId: string;
  label: string;
  category: 'HOSPITAL' | 'DAYCARE_KINDERGARTEN';
  center: { lat: number; lng: number };
  level: 4;
  factIds: string[];
};

export type FocusComplexAction = {
  type: 'focusComplex';
  version: 1;
  actionId: string;
  label: string;
  parcelId: number;
  complexId: number;
  center: { lat: number; lng: number };
  level: 4;
  openDetail: true;
  autoRun: boolean;
  factIds: string[];
};

export type ChatAction = ShowNearbyCategoryAction | FocusComplexAction;

const MAX_ACTIONS = 10;
const MAX_FOCUS_ACTIONS = 6;
const MAX_NEARBY_ACTIONS = 4;
const MAX_LABEL_LENGTH = 100;
const MAX_FACT_IDS = 10;

export function readChatActions(value: unknown, availableFactIds: ReadonlySet<string>): ChatAction[] {
  if (!Array.isArray(value)) return [];
  const actions: ChatAction[] = [];
  const seenIds = new Set<string>();
  const seenComplexIds = new Set<number>();
  let focusCount = 0;
  let nearbyCount = 0;
  let hasAutoRun = false;
  for (const candidate of value) {
    if (actions.length === MAX_ACTIONS) break;
    const focusAction = readFocusComplex(candidate, availableFactIds);
    const action = focusAction ?? readShowNearbyCategory(candidate, availableFactIds);
    if (action == null || seenIds.has(action.actionId)) continue;
    if (action.type === 'focusComplex') {
      if (focusCount === MAX_FOCUS_ACTIONS
        || seenComplexIds.has(action.complexId)
        || (action.autoRun && hasAutoRun)) continue;
      focusCount += 1;
      seenComplexIds.add(action.complexId);
      hasAutoRun ||= action.autoRun;
    } else {
      if (nearbyCount === MAX_NEARBY_ACTIONS) continue;
      nearbyCount += 1;
    }
    seenIds.add(action.actionId);
    actions.push(action);
  }
  return actions;
}

function readFocusComplex(
  value: unknown,
  availableFactIds: ReadonlySet<string>,
): FocusComplexAction | null {
  if (!isRecord(value)
    || !hasExactKeys(value, [
      'type', 'version', 'actionId', 'label', 'parcelId', 'complexId', 'center',
      'level', 'openDetail', 'autoRun', 'factIds',
    ])
    || value.type !== 'focusComplex'
    || value.version !== 1
    || !isIdentifier(value.actionId)
    || !isDisplayText(value.label, MAX_LABEL_LENGTH)
    || !isPositiveSafeInteger(value.parcelId)
    || !isPositiveSafeInteger(value.complexId)
    || value.level !== 4
    || value.openDetail !== true
    || typeof value.autoRun !== 'boolean'
    || !isRecord(value.center)
    || !hasExactKeys(value.center, ['lat', 'lng'])
    || !isMarkerSafeCoordinate(value.center.lat, value.center.lng)
    || !isFactIds(value.factIds, availableFactIds)) {
    return null;
  }
  return {
    type: 'focusComplex',
    version: 1,
    actionId: value.actionId,
    label: value.label.trim(),
    parcelId: value.parcelId,
    complexId: value.complexId,
    center: { lat: value.center.lat as number, lng: value.center.lng as number },
    level: 4,
    openDetail: true,
    autoRun: value.autoRun,
    factIds: [...value.factIds] as string[],
  };
}

function readShowNearbyCategory(
  value: unknown,
  availableFactIds: ReadonlySet<string>,
): ShowNearbyCategoryAction | null {
  if (!isRecord(value)
    || !hasExactKeys(value, [
      'type', 'version', 'actionId', 'label', 'category', 'center', 'level', 'factIds',
    ])
    || value.type !== 'showNearbyCategory'
    || value.version !== 1
    || !isIdentifier(value.actionId)
    || !isDisplayText(value.label, MAX_LABEL_LENGTH)
    || (value.category !== 'HOSPITAL' && value.category !== 'DAYCARE_KINDERGARTEN')
    || value.level !== 4
    || !isRecord(value.center)
    || !hasExactKeys(value.center, ['lat', 'lng'])
    || !isKoreaCoordinate(value.center.lat, value.center.lng)
    || !isFactIds(value.factIds, availableFactIds)) {
    return null;
  }
  return {
    type: 'showNearbyCategory',
    version: 1,
    actionId: value.actionId,
    label: value.label.trim(),
    category: value.category,
    center: { lat: value.center.lat as number, lng: value.center.lng as number },
    level: 4,
    factIds: [...value.factIds] as string[],
  };
}

function isFactIds(value: unknown, availableFactIds: ReadonlySet<string>): value is string[] {
  return Array.isArray(value)
    && value.length > 0
    && value.length <= MAX_FACT_IDS
    && new Set(value).size === value.length
    && value.every((factId) => isIdentifier(factId) && availableFactIds.has(factId));
}

function isPositiveSafeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
}

function isMarkerSafeCoordinate(lat: unknown, lng: unknown): boolean {
  return typeof lat === 'number'
    && Number.isFinite(lat)
    && typeof lng === 'number'
    && Number.isFinite(lng)
    && lat >= 33
    && lat <= 39
    && lng >= 124
    && lng <= 132;
}

function isKoreaCoordinate(lat: unknown, lng: unknown): boolean {
  return typeof lat === 'number'
    && Number.isFinite(lat)
    && typeof lng === 'number'
    && Number.isFinite(lng)
    && lat >= 32
    && lat <= 39.5
    && lng >= 123
    && lng <= 132;
}

function isIdentifier(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/.test(value);
}

function isDisplayText(value: unknown, maximum: number): value is string {
  return typeof value === 'string'
    && value === value.trim()
    && value.length > 0
    && value.length <= maximum;
}

function hasExactKeys(value: Record<string, unknown>, keys: string[]): boolean {
  return Object.keys(value).length === keys.length && keys.every((key) => key in value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}
