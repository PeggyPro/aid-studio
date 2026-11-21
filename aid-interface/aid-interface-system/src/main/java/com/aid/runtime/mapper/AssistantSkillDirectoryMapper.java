package com.aid.runtime.mapper;

import com.aid.runtime.domain.AssistantSkillDirectoryEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AssistantSkillDirectoryMapper {
    List<com.aid.runtime.domain.AssistantModuleDirectoryEntry> selectModules();
    List<Long> selectActiveAgentVersions(@Param("moduleCode") String moduleCode);
    List<AssistantSkillDirectoryEntry> selectPage(@Param("agentVersionId") Long agentVersionId,
            @Param("userId") Long userId, @Param("keyword") String keyword,
            @Param("code") String code, @Param("scope") String scope,
            @Param("allowOwned") boolean allowOwned, @Param("offset") int offset, @Param("limit") int limit,
            @Param("codes") List<String> codes);
}
