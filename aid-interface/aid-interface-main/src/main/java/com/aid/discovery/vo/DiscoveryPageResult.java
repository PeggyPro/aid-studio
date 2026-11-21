package com.aid.discovery.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Stable total/data page contract used by discovery APIs. */
@Data
@AllArgsConstructor
public class DiscoveryPageResult<T>
{
    private long total;
    private List<T> data;
}
