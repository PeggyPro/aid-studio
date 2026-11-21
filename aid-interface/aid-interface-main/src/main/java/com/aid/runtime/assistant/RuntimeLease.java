package com.aid.runtime.assistant;

import lombok.Data;

import java.util.Date;

/** Database lease and fencing identity held by a runtime worker. */
@Data
public class RuntimeLease
{
    private String owner;
    private Long fencingToken;
    private Date leaseUntil;
}
