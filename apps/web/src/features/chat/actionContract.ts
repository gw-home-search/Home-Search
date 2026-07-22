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

export type ChatAction = ShowNearbyCategoryAction;

const MAX_ACTIONS = 4;
const MAX_LABEL_LENGTH = 100;
const MAX_FACT_IDS = 10;

export function readChatActions(value: unknown, availableFactIds: ReadonlySet<string>): ChatAction[] {
  if (!Array.isArray(value)) return [];
  const actions: ChatAction[] = [];
  const seenIds = new Set<string>();
  for (const candidate of value.slice(0, MAX_ACTIONS)) {
    const action = readShowNearbyCategory(candidate, availableFactIds);
    if (action == null || seenIds.has(action.actionId)) continue;
    seenIds.add(action.actionId);
    actions.push(action);
  }
  return actions;
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
    || !Array.isArray(value.factIds)
    || value.factIds.length === 0
    || value.factIds.length > MAX_FACT_IDS
    || new Set(value.factIds).size !== value.factIds.length
    || !value.factIds.every((factId) => isIdentifier(factId) && availableFactIds.has(factId))) {
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
