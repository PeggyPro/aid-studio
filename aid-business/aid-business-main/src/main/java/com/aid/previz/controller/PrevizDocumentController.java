package com.aid.previz.controller;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.previz.service.PrevizDocumentStore;
import com.alibaba.fastjson2.JSONObject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/user/previz/document")
@ConditionalOnProperty(prefix="aid.previz",name="enabled",havingValue="true")
public class PrevizDocumentController {
    private final PrevizDocumentStore store;
    @Data public static class Project {@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}") private String projectId;}
    @Data @EqualsAndHashCode(callSuper=true) public static class Read extends Project {@Pattern(regexp="[a-f0-9]{64}") private String version;}
    @Data @EqualsAndHashCode(callSuper=true) public static class Save extends Project {
        @Pattern(regexp="[a-f0-9]{64}") private String expectedVersion;
        @Pattern(regexp="[a-f0-9]{64}") private String expectedSourceVersion;
        @NotNull private JSONObject document;
    }
    @Data @EqualsAndHashCode(callSuper=true) public static class Restore extends Read {
        @Pattern(regexp="[a-f0-9]{64}") private String expectedVersion;
        @Pattern(regexp="[a-f0-9]{64}") private String expectedSourceVersion;
    }
    @Data @EqualsAndHashCode(callSuper=true) public static class History extends Project {
        @Pattern(regexp="[1-9][0-9]{0,18}") private String before;
        @Min(1) @Max(50) private int limit=20;
    }
    @PostMapping("/current") public AjaxResult current(@Valid @RequestBody Project r)throws IOException{return AjaxResult.success(store.current(SecurityUtils.getUserId(),r.getProjectId()));}
    @PostMapping("/read") public AjaxResult read(@Valid @RequestBody Read r)throws IOException{
        JSONObject result=store.read(SecurityUtils.getUserId(),r.getProjectId(),r.getVersion());
        // Keep intentional nulls (for example root parentId) independent of global response filtering.
        result.put("documentJson",com.alibaba.fastjson2.JSON.toJSONString(result.remove("document"),com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
        return AjaxResult.success(result);
    }
    @PostMapping("/save") public AjaxResult save(@Valid @RequestBody Save r)throws IOException{return AjaxResult.success(store.save(SecurityUtils.getUserId(),r.getProjectId(),r.getExpectedVersion(),r.getExpectedSourceVersion(),r.getDocument()));}
    @PostMapping("/history") public AjaxResult history(@Valid @RequestBody History r)throws IOException{return AjaxResult.success(store.history(SecurityUtils.getUserId(),r.getProjectId(),r.getBefore(),r.getLimit()));}
    @PostMapping("/restore") public AjaxResult restore(@Valid @RequestBody Restore r)throws IOException{return AjaxResult.success(store.restore(SecurityUtils.getUserId(),r.getProjectId(),r.getExpectedVersion(),r.getExpectedSourceVersion(),r.getVersion()));}
}
