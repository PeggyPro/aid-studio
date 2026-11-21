package com.aid.canvas.service;

import com.aid.aid.domain.dto.OssUploadResponse;
import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.canvas.dto.CanvasRequests;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** 画布引用素材登记与归属校验。 */
public interface CanvasResourceService {
    Map<String, Object> register(CanvasRequests.ResourceRegister request, Long userId);
    Map<String, Object> prepareMetadata(CanvasRequests.ResourceMetadataPrepare request, Long userId);
    Map<String, Object> transfer(CanvasRequests.ResourceTransfer request, Long userId);
    List<UploadMetadata> inspectUploads(List<String> mediaTypes, List<MultipartFile> files);
    List<Map<String, Object>> registerUploads(Long workspaceId, List<OssUploadResponse> uploads,
                                              List<UploadMetadata> metadata, String uploadRequestId,
                                              String requestHash, Long userId);
    List<Map<String, Object>> recoverUploads(Long workspaceId, String uploadRequestId,
                                             String requestHash, int fileCount, Long userId);
    List<CanvasResource> requireResources(Long workspaceId, List<Long> ids, Long userId);

    record UploadMetadata(String mediaType, String mimeType, int pageCount,
                          com.aid.media.service.VerifiedMediaMetadataService.Metadata verifiedMetadata) {
        public UploadMetadata(String mediaType, String mimeType, int pageCount) {
            this(mediaType, mimeType, pageCount, null);
        }
    }
}
