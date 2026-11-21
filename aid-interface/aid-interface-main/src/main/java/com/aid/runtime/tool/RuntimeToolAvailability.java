package com.aid.runtime.tool;

public record RuntimeToolAvailability(boolean available, String reason)
{
    public static RuntimeToolAvailability enabled()
    {
        return new RuntimeToolAvailability(true, null);
    }

    public static RuntimeToolAvailability unavailable(String reason)
    {
        return new RuntimeToolAvailability(false, reason);
    }
}
