package com.aid.runtime.service;

import com.aid.common.core.domain.ApiErrorDetails;
import com.aid.common.core.domain.ApiErrorDetails.DependencyReference;
import com.aid.common.exception.ServiceException;
import java.util.List;

public final class RuntimeReferenceErrors {
    private RuntimeReferenceErrors() { }

    public static DependencyReference reference(String type, Long id, String code, String name,
                                                String version, String purpose, String path) {
        DependencyReference ref = new DependencyReference();
        ref.setType(type);
        ref.setId(id == null ? null : String.valueOf(id));
        ref.setCode(code);
        ref.setName(name);
        ref.setVersion(version);
        ref.setPurpose(purpose);
        ref.setReason("当前有效引用须先处理，系统不会自动解绑");
        ref.setManagementPath(path);
        return ref;
    }

    public static void reject(List<DependencyReference> references, long total) {
        if (total <= 0) return;
        ApiErrorDetails details = new ApiErrorDetails();
        details.setErrorCode("RUNTIME_MEMBER_IN_USE");
        details.setTotalReferences(total);
        details.setReferences(references);
        details.setReason("仍有当前有效引用，请在对应管理页面处理后重试；系统不会自动解绑");
        throw new ServiceException("仍有有效引用，无法删除", 409).setClientErrorData(details);
    }
}
