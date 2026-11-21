package com.aid.assistant.service;

import com.aid.assistant.error.AssistantServiceException;
import com.aid.runtime.domain.AssistantSkillDirectoryEntry;
import com.aid.runtime.mapper.AssistantSkillDirectoryMapper;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/** Bounded directory reads; execution readiness is checked only for the selected Skill. */
@Service
@RequiredArgsConstructor
public class AssistantSkillDirectoryService {
    private final AssistantSkillDirectoryMapper mapper;

    @Data
    public static class Request {
        @NotBlank @Pattern(regexp = "MAIN|CANVAS") private String transport;
        @NotBlank @Pattern(regexp = "UNIVERSAL|SCREENWRITER") private String moduleCode = "UNIVERSAL";
        @NotNull @Min(1) @Max(1000) private Integer pageNum = 1;
        @NotNull @Min(1) @Max(20) private Integer pageSize = 20;
        @Size(max = 100) private String keyword;
        @Size(max = 100) private String code;
        @Size(max = 20) private List<@NotBlank @Size(max = 100) String> codes;
        @NotBlank @Pattern(regexp = "OFFICIAL|OWNED") private String scope = "OFFICIAL";
    }
    public record Page(List<AssistantSkillDirectoryEntry> items, int pageNum, int pageSize, boolean hasMore) { }

    public Page page(Long userId, Request request) {
        if (userId == null || userId <= 0 || request == null
                || !AssistantEntryTransport.supported(request.getTransport()))
            throw new AssistantServiceException(403, "ASSISTANT_DIRECTORY_SCOPE_DENIED", "目录访问范围无效", Map.of());
        if (request.getModuleCode() == null
                || !List.of("UNIVERSAL", "SCREENWRITER").contains(request.getModuleCode())
                || request.getScope() == null || !List.of("OFFICIAL", "OWNED").contains(request.getScope())
                || request.getPageNum() == null || request.getPageNum() < 1 || request.getPageNum() > 1000
                || request.getPageSize() == null || request.getPageSize() < 1 || request.getPageSize() > 20
                || request.getKeyword() != null && request.getKeyword().length() > 100
                || request.getCode() != null && request.getCode().length() > 100)
            throw new AssistantServiceException(422, "ASSISTANT_DIRECTORY_INPUT_INVALID", "目录查询参数无效", Map.of());
        if ("OWNED".equals(request.getScope()) && (!"CANVAS".equals(request.getTransport())
                || !"UNIVERSAL".equals(request.getModuleCode())))
            throw new AssistantServiceException(403, "ASSISTANT_DIRECTORY_SCOPE_DENIED", "目录访问范围无效", Map.of());
        List<String> codes = request.getCodes();
        if (codes != null && (codes.size() > 20 || request.getCode() != null
                || codes.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 100)))
            throw new AssistantServiceException(422, "ASSISTANT_DIRECTORY_INPUT_INVALID", "目录查询参数无效", Map.of());
        if (codes != null && codes.isEmpty()) return new Page(List.of(), 1, request.getPageSize(), false);
        if (codes != null) codes = codes.stream().map(String::trim).distinct().toList();
        List<Long> versions = mapper.selectActiveAgentVersions(request.getModuleCode());
        if (versions.size() != 1)
            throw new AssistantServiceException(422, "ASSISTANT_DIRECTORY_BINDING_UNAVAILABLE", "当前助手绑定不可用", Map.of());
        String keyword = text(request.getKeyword());
        if (keyword != null) keyword = keyword.replace("=", "==").replace("%", "=%").replace("_", "=_");
        String code = text(request.getCode());
        int size = code == null ? request.getPageSize() : 1;
        int offset = code == null && codes == null ? Math.multiplyExact(request.getPageNum() - 1, size) : 0;
        List<AssistantSkillDirectoryEntry> rows = mapper.selectPage(versions.get(0), userId, keyword, code,
                request.getScope(), "CANVAS".equals(request.getTransport()) && "UNIVERSAL".equals(request.getModuleCode()),
                offset, size + 1, codes);
        return new Page(List.copyOf(rows.subList(0, Math.min(size, rows.size()))),
                code == null ? request.getPageNum() : 1, size, rows.size() > size);
    }
    private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
