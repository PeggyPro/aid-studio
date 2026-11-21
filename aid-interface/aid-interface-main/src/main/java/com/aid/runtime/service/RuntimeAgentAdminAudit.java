package com.aid.runtime.service;

import com.aid.common.enums.BusinessType;
import com.aid.common.enums.OperatorType;
import com.aid.core.domain.SysOperLog;
import com.aid.core.service.ISysOperLogService;
import org.springframework.stereotype.Component;

import java.util.Date;

/** Content-free audit trail for Runtime Agent administration. */
@Component
public class RuntimeAgentAdminAudit
{
    private final ISysOperLogService operLogService;

    public RuntimeAgentAdminAudit(ISysOperLogService operLogService)
    {
        this.operLogService = operLogService;
    }

    public void record(String action, Long agentId, Long versionId, String actor)
    {
        SysOperLog log = new SysOperLog();
        log.setTitle("Runtime Agent版本治理");
        log.setBusinessType(BusinessType.UPDATE.ordinal());
        log.setMethod("RuntimeAgentAdminService#" + action);
        log.setRequestMethod("POST");
        log.setOperatorType(OperatorType.MANAGE.ordinal());
        log.setOperName(actor);
        log.setOperUrl("/aid/runtime-agent/" + action);
        log.setOperParam("{\"action\":\"" + action + "\",\"agentId\":\"" + agentId
                + "\",\"versionId\":" + (versionId == null ? "null" : "\"" + versionId + "\"")
                + ",\"contentLogged\":false}");
        log.setJsonResult("{\"success\":true}");
        log.setStatus(0);
        log.setOperTime(new Date());
        log.setCostTime(0L);
        operLogService.insertOperlog(log);
    }
}
