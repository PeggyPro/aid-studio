package com.aid.assistant.artifact.service;

import com.aid.assistant.artifact.dto.ArtifactRequests;
import com.aid.assistant.artifact.vo.ArtifactViews;
import com.aid.assistant.creative.vo.CreativeViews;

public interface AssistantArtifactService {
    ArtifactViews.Page<ArtifactViews.Summary> page(Long userId, ArtifactRequests.Page request);

    ArtifactViews.Detail detail(Long userId, ArtifactRequests.Detail request);

    ArtifactViews.Media media(Long userId, ArtifactRequests.Media request);

    /** Internal scoped read; the caller must authorize the source conversation before use. */
    ArchivedMedia conversationMedia(Long userId, Long artifactId, Long sourceRunId);

    record ArchivedMedia(ArtifactViews.Media media, Long resultId) { }

    ArtifactViews.ContentPage content(Long userId, ArtifactRequests.Content request);

    ArtifactViews.Page<ArtifactViews.Review> reviews(Long userId, ArtifactRequests.ReviewPage request);

    ArtifactViews.CoverageProgress coverage(Long userId, ArtifactRequests.CoveragePage request);

    ArtifactViews.Page<ArtifactViews.SourceRevision> sources(Long userId, ArtifactRequests.SourcePage request);

    ArtifactViews.SourceManifest sourceManifest(Long userId, ArtifactRequests.SourceManifest request);

    CreativeViews.SourceChunkPage sourceContent(Long userId, ArtifactRequests.SourceContent request);

    ArtifactViews.AdoptionCapabilities adoptionCapabilities(Long userId,
                                                            ArtifactRequests.AdoptionCapabilities request);

    ArtifactViews.Adoption adopt(Long userId, ArtifactRequests.Adopt request);
}
