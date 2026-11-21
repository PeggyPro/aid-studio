import { listSkillVersions, type SkillId, type SkillVersionSummary } from '@/api/aid/skill';

export async function allSkillVersions(skillId: SkillId) {
  const pageSize = 100;
  const rows: SkillVersionSummary[] = [];
  for (let pageNum = 1; ; pageNum += 1) {
    const response = await listSkillVersions({ skillId, pageNum, pageSize }, { force: true });
    const page = response.data || [];
    rows.push(...page);
    const total = Number(response.total);
    if (page.length === 0 || page.length < pageSize
      || (Number.isFinite(total) && total >= 0 && rows.length >= total)) break;
  }
  return rows;
}

export function suggestVersion(versions: ReadonlyArray<{ versionCode: string }>): string {
  const releases = versions.map(v => /^(\d+)\.(\d+)\.(\d+)$/.exec(v.versionCode))
    .filter(match => match !== null).map(match => match.slice(1).map(Number))
    .sort((a,b) => b[0]-a[0] || b[1]-a[1] || b[2]-a[2]);
  const latest = releases[0];
  return latest ? [latest[0], latest[1], latest[2]+1].join('.') : '1.0.0';
}
