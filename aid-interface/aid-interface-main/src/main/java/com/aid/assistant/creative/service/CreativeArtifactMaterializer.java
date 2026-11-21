package com.aid.assistant.creative.service;

import com.aid.assistant.artifact.vo.ArtifactRef;
import com.aid.assistant.creative.dto.CreativeMaterializationRequests;
import com.aid.assistant.creative.vo.CreativeViews;
import com.aid.runtime.assistant.RuntimeLease;

/** Materializes successful creative tasks into immutable runtime artifacts. */
public interface CreativeArtifactMaterializer {
    ArtifactRef materializeText(CreativeMaterializationRequests.MaterializeText request,
                                RuntimeLease runLease, RuntimeLease stepLease);

    CreativeViews.ReviewMaterialization materializeReview(
            CreativeMaterializationRequests.MaterializeReview request,
            RuntimeLease runLease, RuntimeLease stepLease);

    CreativeViews.MediaMaterialization materializeMedia(
            CreativeMaterializationRequests.MaterializeMedia request,
            RuntimeLease runLease, RuntimeLease stepLease);
}
