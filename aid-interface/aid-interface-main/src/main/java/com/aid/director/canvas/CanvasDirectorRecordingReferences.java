package com.aid.director.canvas;

import com.aid.canvas.publication.service.CanvasSceneRecordingPort;
import com.aid.common.exception.ServiceException;
import com.aid.director.mapper.DirectorTakeMapper;
import com.aid.director.mapper.DirectorTakeMapper.AppliedReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 核对完整场景中已应用录制的共享引用。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasDirectorRecordingReferences implements CanvasSceneRecordingPort {
    private static final int QUERY_BATCH_SIZE = 500;
    private final DirectorTakeMapper takes;

    @Override
    public void requireAppliedTakes(Long projectId, Long workspaceId, List<Reference> references) {
        if (references == null || references.isEmpty()) return;
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Reference reference : references) {
            if (reference == null || reference.takeId() == null || reference.takeId() <= 0
                    || !Objects.equals(reference.clipId(), "take_" + reference.takeId())
                    || reference.sceneKey() == null || reference.cameraId() == null) {
                reject(projectId, workspaceId);
            }
            uniqueIds.add(reference.takeId());
        }
        List<Long> ids = new ArrayList<>(uniqueIds);
        Map<Long, AppliedReference> authority = new HashMap<>();
        // APPLIED 录制属于项目；不得按最初主持人过滤，否则共享编辑者无法保存历史场景。
        for (int offset = 0; offset < ids.size(); offset += QUERY_BATCH_SIZE) {
            List<Long> batch = ids.subList(offset, Math.min(ids.size(), offset + QUERY_BATCH_SIZE));
            for (AppliedReference row : takes.selectAppliedReferences(projectId, workspaceId, batch)) {
                authority.put(row.getTakeId(), row);
            }
        }
        for (Reference reference : references) {
            AppliedReference row = authority.get(reference.takeId());
            if (row == null || !Objects.equals(row.getAppliedClipId(), reference.clipId())
                    || !Objects.equals(row.getSceneKey(), reference.sceneKey())
                    || !Objects.equals(row.getCameraKey(), reference.cameraId())) {
                reject(projectId, workspaceId);
            }
        }
    }

    private static void reject(Long projectId, Long workspaceId) {
        log.info("拒绝无效录制引用 projectId={} workspaceId={}", projectId, workspaceId);
        throw new ServiceException("录制引用已失效");
    }
}
