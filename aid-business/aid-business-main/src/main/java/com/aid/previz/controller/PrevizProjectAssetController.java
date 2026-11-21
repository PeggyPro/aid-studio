package com.aid.previz.controller;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.previz.workspace.PrevizProjectBlobStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/user/previz/asset")
@ConditionalOnProperty(prefix="aid.previz",name="enabled",havingValue="true")
public class PrevizProjectAssetController {
    private final PrevizProjectBlobStore store;
    @Data public static class ProjectRequest {@NotBlank @Size(max=100) private String projectId;}
    @Data @EqualsAndHashCode(callSuper=true) public static class Begin extends ProjectRequest {
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}") private String operationId;
        @NotBlank @Pattern(regexp="[a-f0-9]{64}") private String sha256;
        @NotNull @Min(1) @Max(33554432) private Long sizeBytes;
        @NotBlank @Pattern(regexp="model/gltf-binary|image/png|image/jpeg|image/webp") private String mimeType;
    }
    @Data @EqualsAndHashCode(callSuper=true) public static class Upload extends ProjectRequest {@NotBlank @Pattern(regexp="[a-f0-9]{64}") private String uploadId;}
    @Data @EqualsAndHashCode(callSuper=true) public static class Chunk extends Upload {
        @NotNull @Min(0) @Max(127) private Integer index;
        @NotBlank @Size(max=349528) private String contentBase64;
        @NotBlank @Pattern(regexp="[a-f0-9]{64}") private String chunkSha256;
    }
    @Data @EqualsAndHashCode(callSuper=true) public static class Read extends ProjectRequest {
        @NotBlank @Pattern(regexp="[a-f0-9]{64}") private String sha256;
        @NotNull @Min(0) private Long offset;
        @NotNull @Min(1) @Max(262144) private Integer limit;
    }
    @PostMapping("/upload/begin") public AjaxResult begin(@Valid @RequestBody Begin r)throws IOException{return AjaxResult.success(store.begin(SecurityUtils.getUserId(),r.getProjectId(),r.getOperationId(),r.getSha256(),r.getSizeBytes(),r.getMimeType()));}
    @PostMapping("/upload/chunk") public AjaxResult chunk(@Valid @RequestBody Chunk r)throws IOException{return AjaxResult.success(store.putChunk(SecurityUtils.getUserId(),r.getProjectId(),r.getUploadId(),r.getIndex(),r.getContentBase64(),r.getChunkSha256()));}
    @PostMapping("/upload/complete") public AjaxResult complete(@Valid @RequestBody Upload r)throws IOException{return AjaxResult.success(store.complete(SecurityUtils.getUserId(),r.getProjectId(),r.getUploadId()));}
    @PostMapping("/upload/cancel") public AjaxResult cancel(@Valid @RequestBody Upload r)throws IOException{return AjaxResult.success(store.cancel(SecurityUtils.getUserId(),r.getProjectId(),r.getUploadId()));}
    @PostMapping("/read-chunk") public AjaxResult read(@Valid @RequestBody Read r)throws IOException{return AjaxResult.success(store.readChunk(SecurityUtils.getUserId(),r.getProjectId(),r.getSha256(),r.getOffset(),r.getLimit()));}
}
