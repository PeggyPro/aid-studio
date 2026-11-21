import type {
  RuntimeAgentSkillBinding,
  RuntimeAgentSkillOption,
  RuntimeAgentToolBinding,
  RuntimeAgentToolOption
} from '@/api/aid/assistantRuntimeAgent';

export interface RuntimeAgentBindingResolution {
  complete: boolean;
  skillBindings: RuntimeAgentSkillBinding[];
  toolBindings: RuntimeAgentToolBinding[];
  unresolvedSkillVersionIds: string[];
  unresolvedToolCodes: string[];
  duplicateCatalogSkillVersionIds: string[];
  duplicateCatalogToolCodes: string[];
  invalidCatalogSkillEntries: number;
  invalidCatalogToolEntries: number;
}

function unique(values: readonly string[]) {
  return Array.from(new Set(values));
}

export function resolveRuntimeAgentBindings(
  selectedSkillVersionIds: readonly string[],
  selectedToolCodes: readonly string[],
  skillCatalog: readonly RuntimeAgentSkillOption[],
  toolCatalog: readonly RuntimeAgentToolOption[]
): RuntimeAgentBindingResolution {
  const skillCounts = new Map<string, number>();
  const skillByVersionId = new Map<string, RuntimeAgentSkillOption>();
  let invalidCatalogSkillEntries = 0;
  skillCatalog.forEach((item) => {
    const id = item.skillVersionId == null ? '' : String(item.skillVersionId);
    if (!id || id !== id.trim()) {
      invalidCatalogSkillEntries += 1;
      return;
    }
    skillCounts.set(id, (skillCounts.get(id) || 0) + 1);
    skillByVersionId.set(id, item);
  });
  const toolCounts = new Map<string, number>();
  const toolByCode = new Map<string, RuntimeAgentToolOption>();
  let invalidCatalogToolEntries = 0;
  toolCatalog.forEach((item) => {
    if (!item.code || item.code !== item.code.trim()) {
      invalidCatalogToolEntries += 1;
      return;
    }
    toolCounts.set(item.code, (toolCounts.get(item.code) || 0) + 1);
    toolByCode.set(item.code, item);
  });

  const normalizedSkillIds = unique(selectedSkillVersionIds.map(String));
  const normalizedToolCodes = unique(selectedToolCodes);
  const unresolvedSkillVersionIds = normalizedSkillIds.filter((id) => skillCounts.get(id) !== 1);
  const unresolvedToolCodes = normalizedToolCodes.filter((code) => toolCounts.get(code) !== 1);
  const duplicateCatalogSkillVersionIds = Array.from(skillCounts)
    .filter(([, count]) => count > 1)
    .map(([id]) => id);
  const duplicateCatalogToolCodes = Array.from(toolCounts)
    .filter(([, count]) => count > 1)
    .map(([code]) => code);
  const complete = unresolvedSkillVersionIds.length === 0
    && unresolvedToolCodes.length === 0
    && duplicateCatalogSkillVersionIds.length === 0
    && duplicateCatalogToolCodes.length === 0
    && invalidCatalogSkillEntries === 0
    && invalidCatalogToolEntries === 0;

  return {
    complete,
    skillBindings: normalizedSkillIds
      .filter((id) => skillCounts.get(id) === 1)
      .map((id) => skillByVersionId.get(id)!)
      .map((item) => ({
        skillId: item.skillId,
        skillVersionId: item.skillVersionId,
        skillCode: item.skillCode,
        packageDigest: item.packageDigest
      })),
    toolBindings: normalizedToolCodes
      .filter((code) => toolCounts.get(code) === 1)
      .map((code) => toolByCode.get(code)!)
      .map((item) => ({
        toolCode: item.code,
        contractVersion: item.contractVersion,
        schemaDigest: item.schemaDigest
      })),
    unresolvedSkillVersionIds,
    unresolvedToolCodes,
    duplicateCatalogSkillVersionIds,
    duplicateCatalogToolCodes,
    invalidCatalogSkillEntries,
    invalidCatalogToolEntries
  };
}
