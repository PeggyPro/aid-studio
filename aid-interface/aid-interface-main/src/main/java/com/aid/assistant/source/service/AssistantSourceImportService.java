package com.aid.assistant.source.service;

import com.aid.assistant.source.dto.SourceImportRequests;
import com.aid.assistant.source.vo.SourceImportViews;

public interface AssistantSourceImportService {
    SourceImportViews.Command upload(Long userId, SourceImportRequests.Upload request);

    SourceImportViews.Page page(Long userId, SourceImportRequests.Page request);

    SourceImportViews.Lookup lookup(Long userId, SourceImportRequests.Lookup request);

    com.aid.assistant.creative.vo.CreativeViews.SourceChunkPage content(Long userId, SourceImportRequests.Content request);

    SourceImportViews.Detail detail(Long userId, SourceImportRequests.Detail request);

    SourceImportViews.Command advance(Long userId, SourceImportRequests.Advance request);

    SourceImportViews.Command cancel(Long userId, SourceImportRequests.Cancel request);
    /** Resume only expired persisted parsing commands under the original identity and lease fence. */
    void recoverExpiredImports();
}
