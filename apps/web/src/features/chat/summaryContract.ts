export type GroundedSummaryText = { text: string; factIds: string[] };

export type AppliedCriterion = {
  key: string;
  label: string;
  value: string;
  factIds: string[];
};

export type SummaryInterpretation = {
  key: string;
  label: string;
  text: string;
  factIds: string[];
};

export type FragmentSummary = {
  fragmentId: string;
  capability: string;
  status: 'success' | 'failed';
  headline: string;
  factIds: string[];
};

export type ChatUiSummary = {
  version: 1;
  scopeNotice: GroundedSummaryText | null;
  headline: GroundedSummaryText;
  criteria: AppliedCriterion[];
  interpretations: SummaryInterpretation[];
  followUp: string | null;
  fragmentSummaries: FragmentSummary[];
};

export function readChatUiSummary(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): ChatUiSummary | null {
  if (!isRecord(value)
    || !hasExactKeys(value, [
      'version', 'scopeNotice', 'headline', 'criteria', 'interpretations',
      'followUp', 'fragmentSummaries',
    ])
    || value.version !== 1
    || (value.scopeNotice !== null && readGroundedText(value.scopeNotice, allowedFactIds) == null)
    || readGroundedText(value.headline, allowedFactIds) == null
    || !Array.isArray(value.criteria)
    || value.criteria.length > 8
    || !Array.isArray(value.interpretations)
    || value.interpretations.length > 4
    || (value.followUp !== null && !isDisplayText(value.followUp, 2_000))
    || !Array.isArray(value.fragmentSummaries)
    || value.fragmentSummaries.length > 4) return null;
  const criteria = value.criteria.flatMap((item) => {
    const parsed = readCriterion(item, allowedFactIds);
    return parsed == null ? [] : [parsed];
  });
  const interpretations = value.interpretations.flatMap((item) => {
    const parsed = readInterpretation(item, allowedFactIds);
    return parsed == null ? [] : [parsed];
  });
  const fragments = value.fragmentSummaries.flatMap((item) => {
    const parsed = readFragment(item, allowedFactIds);
    return parsed == null ? [] : [parsed];
  });
  if (criteria.length !== value.criteria.length
    || interpretations.length !== value.interpretations.length
    || fragments.length !== value.fragmentSummaries.length
    || new Set(criteria.map(({ key }) => key)).size !== criteria.length
    || new Set(interpretations.map(({ key }) => key)).size !== interpretations.length
    || new Set(fragments.map(({ fragmentId }) => fragmentId)).size !== fragments.length) return null;
  return {
    version: 1,
    scopeNotice: value.scopeNotice === null
      ? null : readGroundedText(value.scopeNotice, allowedFactIds),
    headline: readGroundedText(value.headline, allowedFactIds)!,
    criteria,
    interpretations,
    followUp: value.followUp === null ? null : value.followUp.trim(),
    fragmentSummaries: fragments,
  };
}

function readGroundedText(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): GroundedSummaryText | null {
  if (!isRecord(value)
    || !hasExactKeys(value, ['text', 'factIds'])
    || !isDisplayText(value.text, 2_000)
    || !isFactIds(value.factIds, allowedFactIds, false)) return null;
  return { text: value.text.trim(), factIds: value.factIds };
}

function readCriterion(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): AppliedCriterion | null {
  if (!isRecord(value)
    || !hasExactKeys(value, ['key', 'label', 'value', 'factIds'])
    || !isIdentifier(value.key)
    || !isDisplayText(value.label, 100)
    || !isDisplayText(value.value, 2_000)
    || !isFactIds(value.factIds, allowedFactIds, false)) return null;
  return {
    key: value.key, label: value.label.trim(), value: value.value.trim(), factIds: value.factIds,
  };
}

function readInterpretation(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): SummaryInterpretation | null {
  if (!isRecord(value)
    || !hasExactKeys(value, ['key', 'label', 'text', 'factIds'])
    || !isIdentifier(value.key)
    || !isDisplayText(value.label, 100)
    || !isDisplayText(value.text, 2_000)
    || !isFactIds(value.factIds, allowedFactIds, false)) return null;
  return {
    key: value.key, label: value.label.trim(), text: value.text.trim(), factIds: value.factIds,
  };
}

function readFragment(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): FragmentSummary | null {
  if (!isRecord(value)
    || !hasExactKeys(value, ['fragmentId', 'capability', 'status', 'headline', 'factIds'])
    || !isIdentifier(value.fragmentId)
    || !isIdentifier(value.capability)
    || (value.status !== 'success' && value.status !== 'failed')
    || !isDisplayText(value.headline, 2_000)
    || !isFactIds(value.factIds, allowedFactIds, value.status === 'failed')) return null;
  return {
    fragmentId: value.fragmentId,
    capability: value.capability,
    status: value.status,
    headline: value.headline.trim(),
    factIds: value.factIds,
  };
}

function isFactIds(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
  allowEmpty: boolean,
): value is string[] {
  return Array.isArray(value)
    && (allowEmpty || value.length > 0)
    && value.length <= 100
    && value.every((factId) => isIdentifier(factId) && allowedFactIds.has(factId))
    && new Set(value).size === value.length;
}

function isDisplayText(value: unknown, maximumLength: number): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= maximumLength;
}

function isIdentifier(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}

function hasExactKeys(value: Record<string, unknown>, keys: string[]): boolean {
  return Object.keys(value).length === keys.length && keys.every((key) => key in value);
}
