package com.aid.assistant.story.service;

import com.aid.assistant.creative.vo.CreativeViews;
import com.aid.assistant.story.dto.StoryWorkspaceRequests;
import com.aid.assistant.story.vo.StoryWorkspaceViews;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;

public interface StoryWorkspaceService {
    StoryWorkspaceRequests.PreparedInitialize prepareInitialize(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.Initialize request);

    StoryWorkspaceViews.Workspace initialize(RuntimeToolPreparedInvocation invocation,
                                              StoryWorkspaceRequests.PreparedInitialize prepared,
                                              RuntimeOperationSubmissionClaim claim);

    StoryWorkspaceViews.Workspace inspect(RuntimeToolInvocation invocation);

    StoryWorkspaceViews.ArtifactContent read(RuntimeToolInvocation invocation,
                                             StoryWorkspaceRequests.ArtifactRead request);

    StoryWorkspaceRequests.PreparedWrite prepareWrite(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.ArtifactWrite request);

    StoryWorkspaceViews.ArtifactReceipt write(RuntimeToolPreparedInvocation invocation,
                                               StoryWorkspaceRequests.PreparedWrite prepared,
                                               RuntimeOperationSubmissionClaim claim);

    CreativeViews.SourceChunkPage readSource(RuntimeToolInvocation invocation,
                                             StoryWorkspaceRequests.SourceRead request);

    StoryWorkspaceRequests.PreparedReview prepareReview(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.ReviewRecord request);

    StoryWorkspaceRequests.PreparedReview prepareReviewCorrection(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.ReviewCorrection request);

    StoryWorkspaceViews.ReviewContent readReview(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.ArtifactRead request);

    StoryWorkspaceViews.ReviewReceipt recordReview(RuntimeToolPreparedInvocation invocation,
                                                   StoryWorkspaceRequests.PreparedReview prepared,
                                                   RuntimeOperationSubmissionClaim claim);

    StoryWorkspaceViews.Workspace reconcileInitialize(RuntimeToolPreparedInvocation invocation,
                                                      StoryWorkspaceRequests.PreparedInitialize prepared);

    StoryWorkspaceViews.ArtifactReceipt reconcileWrite(RuntimeToolPreparedInvocation invocation,
                                                        StoryWorkspaceRequests.PreparedWrite prepared);

    StoryWorkspaceViews.ReviewReceipt reconcileReview(RuntimeToolPreparedInvocation invocation,
                                                       StoryWorkspaceRequests.PreparedReview prepared);

    StoryWorkspaceRequests.PreparedOperation prepareOperation(
            RuntimeToolInvocation invocation, String operation, String argumentsJson);

    Object executeOperation(RuntimeToolPreparedInvocation invocation,
                            StoryWorkspaceRequests.PreparedOperation prepared,
                            RuntimeOperationSubmissionClaim claim);

    Object reconcileOperation(RuntimeToolPreparedInvocation invocation,
                              StoryWorkspaceRequests.PreparedOperation prepared);

    StoryWorkspaceViews.SourceRangePage readChapter(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.SourcePage request);

    StoryWorkspaceViews.PipelineInspection inspectPipeline(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.PipelineInspect request);

    StoryWorkspaceViews.SourceRangePage readEpisode(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.SourcePage request);

    StoryWorkspaceViews.LinePage readLines(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.ArtifactLinesRead request);
}
