package com.aid.runtime.service;

import com.aid.aid.service.support.ModelReferenceContributor;
import com.aid.common.core.domain.ApiErrorDetails.DependencyReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only currently selectable package models and unfinished run models block model removal. */
@Component
@RequiredArgsConstructor
public class RuntimeModelReferences implements ModelReferenceContributor {
    private final JdbcTemplate jdbc;

    @Override
    public List<DependencyReference> references(Set<Long> modelIds, Set<String> capabilityCodes) {
        return page(modelIds, capabilityCodes, 0, 200).items();
    }

    @Override
    public ReferencePage page(Set<Long> modelIds, Set<String> capabilityCodes, long offset, int limit) {
        if (modelIds == null || modelIds.isEmpty()) return new ReferencePage(0, List.of());
        String in = modelIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Object[] args = modelIds.toArray();
        List<Query> sources = List.of(
                new Query("SELECT 'SKILL_VERSION' ref_type,v.id ref_id,s.skill_code ref_code,s.name ref_name,"
                        + "v.version_code ref_version,m.id model_id,"
                        + "CASE WHEN v.model_code=m.model_code COLLATE utf8mb4_unicode_ci "
                        + "THEN 'DEFAULT_MODEL' ELSE 'ALLOWED_MODEL' END purpose "
                        + "FROM aid_skill s JOIN aid_skill_version v ON v.id=s.current_version_id "
                        + "JOIN aid_ai_model m ON (v.model_code=m.model_code COLLATE utf8mb4_unicode_ci OR "
                        + "JSON_SEARCH(v.model_config_json,'one',m.model_code,NULL,'$.selectableModelCodes') IS NOT NULL) "
                        + "WHERE s.del_flag='0' AND s.status='0' AND v.del_flag='0' AND v.status='0' "
                        + "AND m.id IN (" + in + ")"),
                new Query("SELECT 'AGENT_VERSION' ref_type,v.id ref_id,a.agent_code ref_code,a.name ref_name,"
                        + "v.version_code ref_version,m.id model_id,"
                        + "CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(v.model_config_json,'$.defaultModelCode'))="
                        + "m.model_code COLLATE utf8mb4_unicode_ci "
                        + "THEN 'DEFAULT_MODEL' ELSE 'ALLOWED_MODEL' END purpose "
                        + "FROM aid_runtime_agent a JOIN aid_runtime_agent_version v ON v.id=a.current_version_id "
                        + "JOIN aid_ai_model m ON JSON_SEARCH(v.model_config_json,'one',m.model_code,NULL,"
                        + "'$.selectableModelCodes') IS NOT NULL "
                        + "WHERE a.del_flag='0' AND a.status='0' AND v.del_flag='0' AND v.status='0' "
                        + "AND m.id IN (" + in + ")"),
                new Query("SELECT 'ACTIVE_RUN' ref_type,r.id ref_id,CONCAT(r.member_type,':',r.member_id) ref_code,"
                        + "CONCAT('Run ',r.id) ref_name,NULL ref_version,m.id model_id,'EXECUTION_MODEL' purpose "
                        + "FROM aid_runtime_run r JOIN aid_ai_model m ON "
                        + "m.model_code COLLATE utf8mb4_unicode_ci=r.model_code "
                        + "WHERE r.del_flag='0' AND r.status IN ('CREATED','RUNNING','WAITING_INPUT') "
                        + "AND m.id IN (" + in + ")"));
        long total = 0;
        List<DependencyReference> items = new ArrayList<>();
        for (Query source : sources) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM (" + source.sql
                    + (TransactionSynchronizationManager.isActualTransactionActive()
                    ? " LOCK IN SHARE MODE" : "") + ") ref_count", Long.class, args);
            long size = count == null ? 0 : count;
            if (limit > items.size() && offset < total + size) {
                long localOffset = Math.max(0, offset - total);
                int take = limit - items.size();
                Object[] pageArgs = Arrays.copyOf(args, args.length + 2);
                pageArgs[args.length] = localOffset;
                pageArgs[args.length + 1] = take;
                items.addAll(jdbc.query(source.sql + " ORDER BY ref_id LIMIT ?,?"
                        + (TransactionSynchronizationManager.isActualTransactionActive()
                        ? " LOCK IN SHARE MODE" : ""), (rs, index) -> {
                    DependencyReference ref = new DependencyReference();
                    ref.setType(rs.getString("ref_type"));
                    ref.setId(rs.getString("ref_id"));
                    ref.setCode(rs.getString("ref_code"));
                    ref.setName(rs.getString("ref_name"));
                    ref.setVersion(rs.getString("ref_version"));
                    ref.setPurpose(rs.getString("purpose"));
                    ref.setModelId(rs.getString("model_id"));
                    ref.setReason("当前有效版本或运行仍使用此模型及其默认能力");
                    ref.setManagementPath("AGENT_VERSION".equals(ref.getType()) ? "aid/runtimeAgent/index"
                            : "SKILL_VERSION".equals(ref.getType()) ? "aid/skill/index" : null);
                    return ref;
                }, pageArgs));
            }
            total += size;
        }
        return new ReferencePage(total, items);
    }

    private record Query(String sql) { }
}
