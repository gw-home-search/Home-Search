import type { ChatAction, FocusComplexAction } from './actionContract';

export function focusActionForFacts(
  actions: ChatAction[],
  factIds: string[],
): FocusComplexAction | undefined {
  const facts = new Set(factIds);
  return actions.find((action): action is FocusComplexAction => (
    action.type === 'focusComplex' && action.factIds.some((factId) => facts.has(factId))
  ));
}
