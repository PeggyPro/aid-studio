package com.aid.previz.controller;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.previz.service.PrevizMembershipService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/user/previz/project")
@ConditionalOnProperty(prefix="aid.previz",name="enabled",havingValue="true")
public class PrevizMembershipController {
 private final PrevizMembershipService service;
 private final com.aid.previz.service.PrevizProjectLifecycleService lifecycle;
 @Data public static class Page {@Pattern(regexp="[A-Za-z0-9_-]{1,100}") private String before;@Min(1)@Max(50)private int limit=20;}
 @Data public static class Project {@NotBlank@Pattern(regexp="[A-Za-z0-9_-]{1,100}")private String projectId;}
 @Data @EqualsAndHashCode(callSuper=true)public static class Change extends Project {@NotNull@Positive private Long userId;@NotBlank@Pattern(regexp="EDITOR|VIEWER|REMOVE")private String role;@NotNull@Min(0)private Long expectedRevision;}
 @Data @EqualsAndHashCode(callSuper=true)public static class Lifecycle extends Project {@NotBlank@Pattern(regexp="DELETE|RESTORE")private String operation;@NotNull@Min(0)private Long expectedRevision;@NotBlank@Pattern(regexp="[A-Za-z0-9_-]{1,100}")private String clientRequestId;}
 @PostMapping("/page")public AjaxResult page(@Valid@RequestBody Page r){return AjaxResult.success(service.page(SecurityUtils.getUserId(),r.getBefore(),r.getLimit()));}
 @PostMapping("/trash/page")public AjaxResult trash(@Valid@RequestBody Page r){return AjaxResult.success(lifecycle.trash(SecurityUtils.getUserId(),r.getBefore(),r.getLimit()));}
 @PostMapping("/lifecycle")public AjaxResult lifecycle(@Valid@RequestBody Lifecycle r){return AjaxResult.success(lifecycle.change(SecurityUtils.getUserId(),r.getProjectId(),r.getOperation(),r.getExpectedRevision(),r.getClientRequestId()));}
 @PostMapping("/members")public AjaxResult members(@Valid@RequestBody Project r){return AjaxResult.success(service.list(SecurityUtils.getUserId(),r.getProjectId()));}
 @PostMapping("/member/change")public AjaxResult change(@Valid@RequestBody Change r){return AjaxResult.success(service.change(SecurityUtils.getUserId(),r.getProjectId(),r.getUserId(),r.getRole(),r.getExpectedRevision()));}
}
