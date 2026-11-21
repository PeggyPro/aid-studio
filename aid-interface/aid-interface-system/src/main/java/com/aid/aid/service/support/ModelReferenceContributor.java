package com.aid.aid.service.support;

import com.aid.common.core.domain.ApiErrorDetails.DependencyReference;
import java.util.List;
import java.util.Set;

/** 为模型生命周期提供当前有效的业务依赖。 */
public interface ModelReferenceContributor {
    /** capabilityCodes 为空表示查询模型整体依赖。 */
    List<DependencyReference> references(Set<Long> modelIds, Set<String> capabilityCodes);

    record ReferencePage(long total, List<DependencyReference> items) { }

    /** 高频或用户级依赖实现应覆盖此方法，在数据库分页并只查询必要字段。 */
    default ReferencePage page(Set<Long> modelIds, Set<String> capabilityCodes, long offset, int limit) {
        List<DependencyReference> refs = references(modelIds, capabilityCodes);
        return new ReferencePage(refs.size(), refs.stream().skip(offset).limit(limit).toList());
    }
}
