package com.aid.discovery.util;

import com.aid.common.exception.ServiceException;

import lombok.extern.slf4j.Slf4j;

/** Applies service-layer defaults and bounds to discovery pagination. */
@Slf4j
public final class DiscoveryPagination
{
    private DiscoveryPagination()
    {
    }

    public static int pageNum(Integer value)
    {
        int resolved = value == null ? 1 : value;
        if (resolved < 1)
        {
            throw failure("页码必须大于0");
        }
        return resolved;
    }

    public static int pageSize(Integer value, int defaultValue)
    {
        int resolved = value == null ? defaultValue : value;
        if (resolved < 1 || resolved > 100)
        {
            throw failure("每页数量限1到100");
        }
        return resolved;
    }

    private static ServiceException failure(String message)
    {
        log.warn("发现内容分页参数无效: {}", message);
        return new ServiceException(message);
    }
}
