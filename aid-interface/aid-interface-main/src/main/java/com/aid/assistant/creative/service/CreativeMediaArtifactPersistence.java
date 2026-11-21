package com.aid.assistant.creative.service;

import com.aid.assistant.artifact.vo.ArtifactRef;
import com.aid.assistant.creative.dto.CreativeMaterializationRequests;
import com.aid.runtime.assistant.RuntimeLease;

import java.util.List;

/** Transactional persistence boundary for already archived media task outputs. */
public interface CreativeMediaArtifactPersistence {
    List<ArtifactRef> materialize(CreativeMaterializationRequests.MaterializeMedia request,
                                  RuntimeLease runLease, RuntimeLease stepLease);
}
