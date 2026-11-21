"""Replace configured Skill identities during an offline database maintenance window.

Published bodies remain immutable. New versions and Agent bindings are committed
atomically; runs, media tasks, user projects and accounting are never modified.
Requires Python 3 and PyMySQL. Credentials are supplied through AID_MIGRATION_DB_*.
"""
import argparse
import copy
import hashlib
import json
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NAMES = json.loads((Path(__file__).with_name('skill_identities.json')).read_text(encoding='utf8'))
CODES = {code: code.replace('-', '_') for code in NAMES}
AUDITOR = 'skill_identity_migration'


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


def digest(value):
    return hashlib.sha256((value or '').encode('utf8')).hexdigest()


def replace_codes(value):
    if not value:
        return value
    # Whole identity tokens only: unrelated words, file names and Agent codes stay intact.
    return re.sub(r'(?<![a-zA-Z0-9_-])(?:' + '|'.join(re.escape(k) for k in sorted(CODES, key=len, reverse=True))
                  + r')(?![a-zA-Z0-9_-])', lambda m: CODES[m.group()], value)


def package_digest(code, version, resources, relations):
    algorithm = json.loads(version['manifest_json'] or '{}').get('digestAlgorithm', 'aid-db-package-v1')
    if algorithm not in ('aid-db-package-v1', 'aid-db-package-v2', 'aid-db-package-v3'):
        raise ValueError('Unsupported database package digest algorithm')
    basis = dict(format=algorithm, skillCode=code, versionCode=version['version_code'],
                 visibility=version['visibility'], invocationScope=version['invocation_scope'],
                 executorType=version['executor_type'], modelCode=version['model_code'])
    if algorithm.endswith('v3'):
        basis['modelConfigJson'] = version['model_config_json'] or ''
    for key, column in [('systemPromptDigest', 'system_prompt'), ('inputSchemaDigest', 'input_schema_json'),
                        ('outputSchemaDigest', 'output_schema_json'), ('definitionDigest', 'definition_json')]:
        basis[key] = digest(version[column])
    for key, column in [('maxOutputTokens', 'max_output_tokens'), ('contextWindowTokens', 'context_window_tokens'),
                        ('safetyMarginTokens', 'safety_margin_tokens')]:
        basis[key] = version[column]
    if algorithm.endswith(('v2', 'v3')):
        basis['manifestDigest'] = digest(version['manifest_json'])
    basis['resources'] = [{k: v for k, v in dict(key=r['resource_key'], type=r['resource_type'],
        mimeType=r['mime_type'], digest=r['content_digest'], sizeBytes=r['size_bytes'],
        routeJson=r['route_json'] or '').items() if v is not None} for r in sorted(resources, key=lambda r:r['id'])]
    basis['relations'] = [{k: v for k, v in dict(type=r['relation_type'], key=r['relation_key'],
        childSkillId=r['child_skill_id'], childVersionId=r['child_version_id'], required=None if r['required_flag'] is None else bool(r['required_flag'])).items()
        if v is not None} for r in sorted(relations, key=lambda r:r['relation_key'])]
    return digest(encoded({k:v for k,v in basis.items() if v is not None}))


def agent_digest(version):
    return digest('\n'.join('null' if version[key] is None else version[key] for key in
                           ['instruction', 'model_config_json', 'input_schema_json', 'output_schema_json', 'definition_json']))


def next_version(current, occupied):
    match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)', current)
    if not match:
        raise ValueError('Current version must be a stable semantic version')
    major, minor, patch = map(int, match.groups())
    while True:
        patch += 1
        candidate = f'{major}.{minor}.{patch}'
        if candidate not in occupied:
            return candidate


def source_digest(code):
    folder = ROOT / 'aid-interface/aid-interface-main/src/main/resources/skills' / code / '1.0.0'
    manifest = folder / 'aid-skill.json'
    if not manifest.exists():
        return None
    text = manifest.read_text(encoding='utf8')
    data = json.loads(text)
    basis = text
    for item in data.get('resources', []):
        actual = digest((folder / item['path']).read_text(encoding='utf8'))
        if actual != item['digest']:
            raise ValueError('Bundled resource checksum mismatch')
        basis += '\n' + item['key'] + '\n' + item['path'] + '\n' + actual
    return digest(basis)


def prepare(tables):
    state = copy.deepcopy(tables)
    current_versions = {v['id']:v for v in state['aid_skill_version']}
    old_roots = [s for s in state['aid_skill'] if s['skill_code'] in CODES and s['del_flag']=='0'
                 and CODES[s['skill_code']] != s['skill_code']]
    # The unchanged orchestrator code still needs a new package when its child identities change.
    child_ids = {s['id'] for s in old_roots}
    for root in state['aid_skill']:
        if root['skill_code'] != 'screenplay' or root['del_flag'] != '0': continue
        current = current_versions.get(root['current_version_id'])
        if current and (replace_codes(current['definition_json']) != current['definition_json']
                or any(r['parent_version_id']==current['id'] and r['child_skill_id'] in child_ids
                       and r['del_flag']=='0' for r in state['aid_skill_relation'])):
            old_roots.append(root)
    present = {s['skill_code'] for s in state['aid_skill']}
    if any(CODES[s['skill_code']] != s['skill_code'] and CODES[s['skill_code']] in present for s in old_roots):
        raise ValueError('Target Skill identity already exists; no merge or overwrite is allowed')
    # This migration applies only to the fixed platform inventory, never a similarly named user Skill.
    if any(s['owner_type'] != 'PLATFORM' for s in old_roots):
        raise ValueError('User-owned Skill cannot be replaced by platform maintenance')
    result = dict(skills=[], versions=[], resources=[], relations=[], agents=[], agentVersions=[], skillRefs=[], toolRefs=[], moduleBindings=[])
    for name in ['aid_skill_version','aid_skill_resource','aid_skill_relation','aid_runtime_agent_version','aid_runtime_agent_skill_ref','aid_runtime_agent_tool_ref','aid_runtime_module_agent_binding']:
        state[name + '_next'] = max((r['id'] for r in state[name]), default=0) + 1

    def allocate(table, body):
        row = dict(body)
        row['id'] = state[table + '_next']
        state[table + '_next'] += 1
        for key in ['create_by','update_by']:
            if key in row: row[key] = AUDITOR
        return row

    migrated = {}
    for root in old_roots:
        current = current_versions.get(root['current_version_id'])
        if current is None or current['skill_id'] != root['id'] or current['publish_status'] not in ('PRIVATE','PUBLIC') or current['status']!='0' or current['del_flag']!='0':
            raise ValueError('Skill current version is unavailable')
        resources = [r for r in state['aid_skill_resource'] if r['skill_version_id']==current['id'] and r['status']=='0' and r['del_flag']=='0']
        relations = [r for r in state['aid_skill_relation'] if r['parent_version_id']==current['id'] and r['del_flag']=='0']
        manifest = json.loads(current['manifest_json'] or '{}')
        if manifest.get('source') != 'DATABASE':
            raise ValueError('Materialize classpath packages in the supported admin publisher before migration')
        if package_digest(root['skill_code'], current, resources, relations) != current['package_digest']:
            raise ValueError('Original Skill package digest is invalid')
        for resource in resources:
            if resource['content_text'] is not None and (digest(resource['content_text']) != resource['content_digest']
                    or len(resource['content_text'].encode('utf8')) != resource['size_bytes']):
                raise ValueError('Original Skill resource is invalid')
        version = allocate('aid_skill_version', current)
        version['version_code'] = next_version(current['version_code'], {v['version_code'] for v in state['aid_skill_version'] if v['skill_id']==root['id']})
        code = CODES[root['skill_code']]
        for key in ['system_prompt','input_schema_json','output_schema_json','definition_json','manifest_json']:
            version[key] = replace_codes(version[key])
        manifest = json.loads(version['manifest_json'] or '{}')
        manifest.update(code=code, name=NAMES[root['skill_code']], version=version['version_code'])
        definition = json.loads(version['definition_json'] or '{}')
        if 'builtinSource' in definition:
            source = source_digest(code)
            if not source: raise ValueError('Built-in source package is absent')
            definition['builtinSource'].update(code=code, digest=source)
            version['definition_json'] = encoded(definition)
        cloned_resources = []
        for resource in sorted(resources, key=lambda r:r['id']):
            row = allocate('aid_skill_resource', resource)
            row['skill_version_id'] = version['id']
            for key in ['content_text','object_key','route_json']:
                row[key] = replace_codes(row[key])
            if row['content_text'] is not None:
                row['content_digest'] = digest(row['content_text'])
                row['size_bytes'] = len(row['content_text'].encode('utf8'))
            cloned_resources.append(row)
        by_key = {r['resource_key']:r for r in cloned_resources}
        for item in manifest.get('resources', []):
            if item.get('key') not in by_key: raise ValueError('Manifest resource binding is invalid')
            item['digest'] = by_key[item['key']]['content_digest']
        version['manifest_json'] = encoded(manifest)
        cloned_relations = []
        for relation in relations:
            row = allocate('aid_skill_relation', relation)
            row['parent_version_id'] = version['id']
            row['relation_key'] = replace_codes(row['relation_key'])
            cloned_relations.append(row)
        result['versions'].append(version)
        result['resources'].extend(cloned_resources)
        result['relations'].extend(cloned_relations)
        updated_root = dict(root, skill_code=code, name=NAMES[root['skill_code']], current_version_id=version['id'], update_by=AUDITOR)
        for key in ['executor_type','model_code','system_prompt','input_schema_json','output_schema_json','definition_json',
                    'max_output_tokens','context_window_tokens','safety_margin_tokens']:
            updated_root[key] = version[key]
        result['skills'].append(updated_root)
        migrated[root['id']] = version
    for relation in result['relations']:
        if relation['child_skill_id'] in migrated:
            relation['child_version_id'] = migrated[relation['child_skill_id']]['id']
    for root, version in zip(result['skills'], result['versions']):
        version['package_digest'] = package_digest(root['skill_code'], version,
            [r for r in result['resources'] if r['skill_version_id']==version['id']],
            [r for r in result['relations'] if r['parent_version_id']==version['id']])
        root['config_hash'] = digest(root['skill_code'] + '|' + version['version_code'] + '|' + version['package_digest'])

    for agent in state['aid_runtime_agent']:
        if agent['del_flag']!='0' or agent['status']!='0' or not agent['current_version_id']: continue
        current = next((v for v in state['aid_runtime_agent_version'] if v['id']==agent['current_version_id']), None)
        if current is None or current['agent_id'] != agent['id']:
            raise ValueError('Agent current version is unavailable')
        refs = [r for r in state['aid_runtime_agent_skill_ref'] if r['agent_version_id']==current['id']]
        changed = any(r['skill_id'] in migrated for r in refs) or replace_codes(current['instruction']) != current['instruction']
        if not changed: continue
        if current['publish_status']!='FROZEN' or current['status']!='0' or current['del_flag']!='0' or agent_digest(current)!=current['package_digest']:
            raise ValueError('Original Agent package is invalid')
        version = allocate('aid_runtime_agent_version', current)
        version['version_code'] = next_version(current['version_code'], {v['version_code'] for v in state['aid_runtime_agent_version'] if v['agent_id']==agent['id']})
        version['instruction'] = replace_codes(version['instruction'])
        definition = json.loads(replace_codes(version['definition_json']))
        definition['baseVersionId'] = current['id']
        for binding in definition.get('skillBindings', []):
            if binding['skillId'] in migrated:
                skill = next(s for s in result['skills'] if s['id']==binding['skillId'])
                target = migrated[binding['skillId']]
                binding.update(skillCode=skill['skill_code'], skillVersionId=target['id'], packageDigest=target['package_digest'])
        actual = {r['skill_id']:(r['skill_version_id'],r['skill_code'],r['package_digest']) for r in refs}
        original_declared = {b['skillId']:(b['skillVersionId'],b['skillCode'],b['packageDigest']) for b in json.loads(current['definition_json']).get('skillBindings',[])}
        if original_declared != actual or len(original_declared) != len(refs):
            raise ValueError('Original Agent Skill binding snapshot is invalid')
        original_tools = json.loads(current['definition_json']).get('toolBindings',[])
        frozen_tools = [r for r in state['aid_runtime_agent_tool_ref'] if r['agent_version_id']==current['id']]
        declared_tools = {b['toolCode']:(b['contractVersion'],b['schemaDigest']) for b in original_tools}
        actual_tools = {r['tool_code']:(r['contract_version'],r['schema_digest']) for r in frozen_tools}
        if declared_tools!=actual_tools or len(declared_tools)!=len(original_tools) or len(actual_tools)!=len(frozen_tools):
            raise ValueError('Original Agent Tool binding snapshot is invalid')
        version['definition_json'] = encoded(definition)
        version['package_digest'] = agent_digest(version)
        result['agentVersions'].append(version)
        result['agents'].append(dict(agent, current_version_id=version['id'], update_by=AUDITOR))
        for ref in refs:
            row = allocate('aid_runtime_agent_skill_ref', ref)
            row['agent_version_id'] = version['id']
            if row['skill_id'] in migrated:
                skill = next(s for s in result['skills'] if s['id']==row['skill_id'])
                target = migrated[row['skill_id']]
                row.update(skill_code=skill['skill_code'], skill_version_id=target['id'], package_digest=target['package_digest'])
            result['skillRefs'].append(row)
        for ref in state['aid_runtime_agent_tool_ref']:
            if ref['agent_version_id']==current['id']:
                result['toolRefs'].append(allocate('aid_runtime_agent_tool_ref', dict(ref, agent_version_id=version['id'])))
    by_agent = {a['id']:a for a in result['agents']}
    for binding in state['aid_runtime_module_agent_binding']:
        agent = by_agent.get(binding['runtime_agent_id'])
        if agent is None or binding['status']!='ACTIVE' or binding['del_flag']!='0': continue
        original = next(a for a in state['aid_runtime_agent'] if a['id']==agent['id'])
        if binding['active_version_id'] != original['current_version_id']:
            raise ValueError('Original module Agent binding is invalid')
        result['moduleBindings'].append(dict(binding, active_version_id=agent['current_version_id'],
                                             binding_revision=binding['binding_revision']+1, update_by=AUDITOR))
    return result


SKILL_TABLES = ['aid_skill','aid_skill_version','aid_skill_resource','aid_skill_relation']
RUNTIME_TABLES = ['aid_runtime_agent','aid_runtime_agent_version','aid_runtime_agent_skill_ref',
                  'aid_runtime_agent_tool_ref','aid_runtime_module_agent_binding']
SKILL_REQUIRED_TABLES = SKILL_TABLES + ['aid_skill_draft']
RUNTIME_REQUIRED_TABLES = RUNTIME_TABLES + ['aid_runtime_agent_draft']
TABLES = SKILL_TABLES + RUNTIME_TABLES


def load(connection):
    with connection.cursor() as cursor:
        schema_tables = SKILL_REQUIRED_TABLES + RUNTIME_REQUIRED_TABLES
        cursor.execute('SELECT TABLE_NAME AS table_name FROM information_schema.TABLES '
                       'WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('
                       + ','.join(['%s'] * len(schema_tables)) + ')', schema_tables)
        present = {row['table_name'] for row in cursor.fetchall()}
        missing_skills = [table for table in SKILL_REQUIRED_TABLES if table not in present]
        if missing_skills:
            connection.rollback()
            raise RuntimeError('Required Skill tables are missing: ' + ', '.join(missing_skills))
        present_runtime = [table for table in RUNTIME_REQUIRED_TABLES if table in present]
        if present_runtime and len(present_runtime) != len(RUNTIME_REQUIRED_TABLES):
            missing_runtime = [table for table in RUNTIME_REQUIRED_TABLES if table not in present]
            connection.rollback()
            raise RuntimeError('Runtime Agent tables must be all present or all absent; missing: '
                               + ', '.join(missing_runtime))
        result = {table: [] for table in RUNTIME_TABLES}
        selected_tables = SKILL_TABLES + (RUNTIME_TABLES if present_runtime else [])
        for table in selected_tables:
            cursor.execute('SELECT * FROM ' + table + ' ORDER BY id FOR UPDATE')
            result[table] = cursor.fetchall()
        return result


def apply(connection, plan):
    with connection.cursor() as cursor:
        for key, table in [('versions','aid_skill_version'),('resources','aid_skill_resource'),('relations','aid_skill_relation'),
                           ('agentVersions','aid_runtime_agent_version'),('skillRefs','aid_runtime_agent_skill_ref'),('toolRefs','aid_runtime_agent_tool_ref')]:
            for row in plan[key]:
                values = {k:v for k,v in row.items() if k not in ('create_time','update_time')}
                columns = ','.join('`'+k+'`' for k in values)
                placeholders = ','.join(['%s']*len(values))
                timestamps = ',create_time,update_time' if 'create_time' in row else ''
                timestamp_values = ',NOW(),NOW()' if 'create_time' in row else ''
                cursor.execute(f'INSERT INTO {table} ({columns}{timestamps}) VALUES ({placeholders}{timestamp_values})', list(values.values()))
        for key, table in [('skills','aid_skill'),('agents','aid_runtime_agent'),('moduleBindings','aid_runtime_module_agent_binding')]:
            for row in plan[key]:
                values = {k:v for k,v in row.items() if k not in ('id','create_by','create_time','update_time')}
                cursor.execute('UPDATE '+table+' SET '+','.join('`'+k+'`=%s' for k in values)+',update_time=NOW() WHERE id=%s', list(values.values())+[row['id']])
        for key, table, parent in [('skills','aid_skill_draft','skill_id'),('agents','aid_runtime_agent_draft','agent_id')]:
            ids = [row['id'] for row in plan[key]]
            if ids:
                receipt = encoded({'discarded':True,'reason':'Skill身份已统一，请从当前版本重新编辑'})
                cursor.execute('UPDATE '+table+" SET status='DISCARDED',active_key=NULL,draft_json=%s,draft_digest=%s,update_by=%s,update_time=NOW() WHERE status='EDITING' AND "+parent+' IN ('+','.join(['%s']*len(ids))+')', [receipt,digest(receipt),AUDITOR]+ids)


def main():
    import pymysql
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Commit replacement; default validates and rolls back')
    parser.add_argument('--service-stopped', action='store_true', help='Confirm the backend is stopped and new requests are blocked')
    args = parser.parse_args()
    if args.apply and not args.service_stopped: parser.error('--apply requires an offline maintenance window')
    settings = {k:os.environ['AID_MIGRATION_DB_'+k.upper()] for k in ['host','port','user','password','database']}
    settings['port'] = int(settings['port'])
    db = pymysql.connect(**settings, charset='utf8mb4', autocommit=False, cursorclass=pymysql.cursors.DictCursor,
                         read_timeout=30, write_timeout=30)
    try:
        with db.cursor() as cursor:
            cursor.execute("SELECT GET_LOCK('aid.skill.identity.maintenance',10) acquired")
            if cursor.fetchone()['acquired']!=1: raise RuntimeError('Another Skill identity maintenance is running')
        plan = prepare(load(db))
        if args.apply:
            apply(db, plan)
            # Verify the entire replacement before committing any root or binding change.
            verified = load(db)
            for root in plan['skills']:
                version = next(v for v in verified['aid_skill_version'] if v['id']==root['current_version_id'])
                actual = package_digest(root['skill_code'], version,
                    [r for r in verified['aid_skill_resource'] if r['skill_version_id']==version['id'] and r['status']=='0' and r['del_flag']=='0'],
                    [r for r in verified['aid_skill_relation'] if r['parent_version_id']==version['id'] and r['del_flag']=='0'])
                if actual!=version['package_digest']: raise ValueError('Replacement Skill package validation failed')
            if prepare(verified)['skills']: raise ValueError('Replacement is not idempotent')
            for expected in plan['agentVersions']:
                version = next(v for v in verified['aid_runtime_agent_version'] if v['id']==expected['id'])
                if agent_digest(version)!=version['package_digest']: raise ValueError('Replacement Agent package validation failed')
                declared = {b['skillId']:(b['skillVersionId'],b['skillCode'],b['packageDigest']) for b in json.loads(version['definition_json']).get('skillBindings',[])}
                refs = [r for r in verified['aid_runtime_agent_skill_ref'] if r['agent_version_id']==version['id']]
                actual = {r['skill_id']:(r['skill_version_id'],r['skill_code'],r['package_digest']) for r in refs}
                if declared!=actual or len(declared)!=len(refs): raise ValueError('Replacement Agent bindings are invalid')
                tools = [r for r in verified['aid_runtime_agent_tool_ref'] if r['agent_version_id']==version['id']]
                declared_tools = {b['toolCode']:(b['contractVersion'],b['schemaDigest']) for b in json.loads(version['definition_json']).get('toolBindings',[])}
                actual_tools = {r['tool_code']:(r['contract_version'],r['schema_digest']) for r in tools}
                if declared_tools!=actual_tools or len(declared_tools)!=len(tools): raise ValueError('Replacement Agent Tool bindings are invalid')
            for expected in plan['moduleBindings']:
                actual = next(b for b in verified['aid_runtime_module_agent_binding'] if b['id']==expected['id'])
                if (actual['runtime_agent_id'],actual['active_version_id'],actual['binding_revision']) != (expected['runtime_agent_id'],expected['active_version_id'],expected['binding_revision']):
                    raise ValueError('Replacement module Agent binding is invalid')
            db.commit()
        else:
            db.rollback()
        print(encoded({'applied':args.apply,'skills':len(plan['skills']),'agents':len(plan['agents']),
                       'identities':[{'name':s['name'],'code':s['skill_code']} for s in plan['skills']]}))
    finally:
        db.rollback()
        with db.cursor() as cursor: cursor.execute("SELECT RELEASE_LOCK('aid.skill.identity.maintenance')")
        db.close()


if __name__=='__main__':
    main()
