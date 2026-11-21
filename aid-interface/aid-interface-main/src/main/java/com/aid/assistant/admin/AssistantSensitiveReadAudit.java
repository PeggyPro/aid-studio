package com.aid.assistant.admin;

import com.aid.common.enums.BusinessType;
import com.aid.common.enums.OperatorType;
import com.aid.common.utils.SecurityUtils;
import com.aid.core.domain.SysOperLog;
import com.aid.core.service.ISysOperLogService;
import org.springframework.stereotype.Component;

import java.util.Date;

/** Fail-closed audit writer for detail reads containing user or billing identifiers. */
@Component
public class AssistantSensitiveReadAudit
{
    private final ISysOperLogService operLogService;

    public AssistantSensitiveReadAudit(ISysOperLogService operLogService)
    {
        this.operLogService = operLogService;
    }

    public void record(String targetType, Long targetId)
    {
        SysOperLog log = new SysOperLog();
        log.setTitle("Assistant敏感详情读取");
        log.setBusinessType(BusinessType.OTHER.ordinal());
        log.setMethod("AssistantAdminQueryService#get" + targetType + "Detail");
        log.setRequestMethod("POST");
        log.setOperatorType(OperatorType.MANAGE.ordinal());
        log.setOperName(SecurityUtils.getUsername());
        log.setOperUrl("/aid/assistant/admin/" + targetType.toLowerCase() + "/detail");
        log.setOperParam("{\"targetType\":\"" + targetType + "\",\"targetId\":\"" + targetId + "\",\"redacted\":true}");
        log.setJsonResult("{\"detailReturned\":true,\"payloadLogged\":false}");
        log.setStatus(0);
        log.setOperTime(new Date());
        log.setCostTime(0L);
        operLogService.insertOperlog(log);
    }
}
