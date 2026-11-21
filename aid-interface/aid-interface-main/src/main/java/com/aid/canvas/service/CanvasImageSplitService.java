package com.aid.canvas.service;

import com.aid.canvas.dto.CanvasRequests;
import java.util.Map;

/** 将工作区已登记图片按原图像素切分为新的素材。 */
public interface CanvasImageSplitService {
    Map<String, Object> split(CanvasRequests.ImageSplit request, Long userId);
}
