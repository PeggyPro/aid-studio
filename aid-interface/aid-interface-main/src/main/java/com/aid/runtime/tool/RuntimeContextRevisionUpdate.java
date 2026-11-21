package com.aid.runtime.tool;

import lombok.Data;

/**
 * A provider-produced, server-trusted context revision receipt. The coordinator
 * applies this receipt with the current Runtime lease and fence before it can
 * advance the run's bound context revision.
 */
@Data
public class RuntimeContextRevisionUpdate
{
    private String kind;
    private String objectId;
    private String oldRevision;
    private String newRevision;
    private String receiptId;
}
