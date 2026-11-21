package com.aid.canvas.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface CanvasTextImportService {
    Map<String, Object> parse(Long workspaceId, MultipartFile file, Long userId);
}
