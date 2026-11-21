package com.aid.discovery.util;

import com.aid.common.exception.ServiceException;

import lombok.extern.slf4j.Slf4j;

/** Normalizes a declared media size into a stable aspect-ratio label. */
@Slf4j
public final class DiscoveryAspectRatioResolver
{
    private static final double TOLERANCE = 0.025D;

    private DiscoveryAspectRatioResolver()
    {
    }

    public static String resolve(Integer width, Integer height)
    {
        if (width == null || height == null || width <= 0 || height <= 0)
        {
            log.warn("发现内容媒体尺寸无效: width={}, height={}", width, height);
            throw new ServiceException("媒体宽高必须为正数");
        }
        double ratio = (double) width / height;
        String canonical = canonical(ratio);
        if (canonical != null)
        {
            return canonical;
        }
        int divisor = greatestCommonDivisor(width, height);
        return (width / divisor) + ":" + (height / divisor);
    }

    private static String canonical(double ratio)
    {
        if (near(ratio, 16D / 9D))
        {
            return "16:9";
        }
        if (near(ratio, 9D / 16D))
        {
            return "9:16";
        }
        if (near(ratio, 1D))
        {
            return "1:1";
        }
        if (near(ratio, 4D / 3D))
        {
            return "4:3";
        }
        if (near(ratio, 3D / 4D))
        {
            return "3:4";
        }
        if (near(ratio, 21D / 9D))
        {
            return "21:9";
        }
        return null;
    }

    private static boolean near(double actual, double expected)
    {
        return Math.abs(actual - expected) / expected <= TOLERANCE;
    }

    private static int greatestCommonDivisor(int left, int right)
    {
        int a = Math.abs(left);
        int b = Math.abs(right);
        while (b != 0)
        {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return Math.max(a, 1);
    }
}
