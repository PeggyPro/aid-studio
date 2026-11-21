package com.aid.assistant.creative.service;

import com.aid.assistant.creative.dto.CreativeRequests;
import com.aid.assistant.creative.vo.CreativeViews;

public interface CreativePlanningService {
    CreativeViews.Strategy plan(CreativeRequests.Strategy request);
}
