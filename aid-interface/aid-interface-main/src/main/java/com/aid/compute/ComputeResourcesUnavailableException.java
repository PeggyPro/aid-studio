package com.aid.compute;

/** Definite local non-entry; never describes an already submitted worker job. */
public final class ComputeResourcesUnavailableException extends RuntimeException {
    public static final String CODE = "COMPUTE_RESOURCE_MAINTENANCE";
    public ComputeResourcesUnavailableException() { super("构建资源维护中，请稍后重试"); }
}
