package com.aid.compute;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Capacity arithmetic shared by configuration validation and admission. All quantities are explicit units. */
public final class ComputeResourcePolicy {
    private ComputeResourcePolicy() { }

    public record Resources(long cpuMillis, long memoryBytes, long scratchBytes) {
        public Resources {
            if (cpuMillis < 0 || memoryBytes < 0 || scratchBytes < 0)
                throw new IllegalArgumentException("计算资源不能为负数");
        }
        public Resources plus(Resources other) {
            return new Resources(Math.addExact(cpuMillis, other.cpuMillis),
                    Math.addExact(memoryBytes, other.memoryBytes), Math.addExact(scratchBytes, other.scratchBytes));
        }
        public boolean accommodates(Resources other) {
            return cpuMillis >= other.cpuMillis && memoryBytes >= other.memoryBytes && scratchBytes >= other.scratchBytes;
        }
    }

    public record Limits(int maxConcurrent, Map<String, Integer> engineConcurrent,
                         Map<String, Integer> taskConcurrent, Resources capacity, long minimumFreeBytes) {
        public Limits {
            if (maxConcurrent < 1 || maxConcurrent > 10000)
                throw new IllegalArgumentException("单机最高并发必须在1至10000之间");
            Objects.requireNonNull(capacity, "资源额度不能为空");
            if (capacity.cpuMillis == 0 || capacity.memoryBytes == 0 || capacity.scratchBytes == 0 || minimumFreeBytes < 0)
                throw new IllegalArgumentException("CPU、内存、临时空间额度必须大于零，磁盘保留量不能为负数");
            engineConcurrent = validateLimits(engineConcurrent, maxConcurrent);
            taskConcurrent = validateLimits(taskConcurrent, maxConcurrent);
        }
    }

    /** Occupied includes execution with an unresolved receipt, not only healthy running processes. */
    public record Occupancy(int total, Map<String, Integer> engines, Map<String, Integer> tasks, Resources reserved) {
        public Occupancy {
            if (total < 0) throw new IllegalArgumentException("占用数量无效");
            Objects.requireNonNull(reserved);
            engines = Map.copyOf(engines); tasks = Map.copyOf(tasks);
            if (engines.values().stream().anyMatch(v -> v < 0 || v > total)
                    || tasks.values().stream().anyMatch(v -> v < 0 || v > total)
                    || engines.values().stream().mapToLong(Integer::longValue).sum() != total
                    || tasks.values().stream().mapToLong(Integer::longValue).sum() != total)
                throw new IllegalArgumentException("分类占用数量无效");
        }
    }

    public record Request(String engine, String taskKind, Resources resources) {
        public Request {
            key(engine); key(taskKind); Objects.requireNonNull(resources);
            if (resources.cpuMillis == 0 || resources.memoryBytes == 0 || resources.scratchBytes == 0)
                throw new IllegalArgumentException("任务必须声明CPU、内存和临时空间预算");
        }
    }

    public enum Decision { ACCEPT, NODE_UNAVAILABLE, ENGINE_UNAVAILABLE, TOTAL_CONCURRENCY,
        ENGINE_CONCURRENCY, TASK_CONCURRENCY, RESOURCE_CAPACITY, DISK_RESERVE }

    /** The caller must evaluate and reserve within the same database transaction/row lock. */
    public static Decision assess(Limits limits, Occupancy occupied, Request request,
                                  boolean accepting, Set<String> availableEngines, long freeDiskBytes) {
        if (!accepting) return Decision.NODE_UNAVAILABLE;
        if (!availableEngines.contains(request.engine)) return Decision.ENGINE_UNAVAILABLE;
        if (occupied.total >= limits.maxConcurrent) return Decision.TOTAL_CONCURRENCY;
        if (occupied.engines.getOrDefault(request.engine, 0) >= limits.engineConcurrent.getOrDefault(request.engine, limits.maxConcurrent))
            return Decision.ENGINE_CONCURRENCY;
        if (occupied.tasks.getOrDefault(request.taskKind, 0) >= limits.taskConcurrent.getOrDefault(request.taskKind, limits.maxConcurrent))
            return Decision.TASK_CONCURRENCY;
        Resources total;
        try { total = occupied.reserved.plus(request.resources); }
        catch (ArithmeticException overflow) { return Decision.RESOURCE_CAPACITY; }
        if (!limits.capacity.accommodates(total)) return Decision.RESOURCE_CAPACITY;
        // Conservatively reserve the entire budget of outstanding executions until their terminal receipts are verified.
        if (freeDiskBytes < limits.minimumFreeBytes || total.scratchBytes > freeDiskBytes - limits.minimumFreeBytes)
            return Decision.DISK_RESERVE;
        return Decision.ACCEPT;
    }

    private static Map<String, Integer> validateLimits(Map<String, Integer> values, int total) {
        if (values == null || values.size() > 100) throw new IllegalArgumentException("分类并发配置无效");
        values.forEach((name, count) -> {
            key(name);
            if (count == null || count < 0 || count > total) throw new IllegalArgumentException("分类并发必须在0和单机最高并发之间");
        });
        return Map.copyOf(values);
    }

    public static void key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))
            throw new IllegalArgumentException("计算能力编码无效");
    }
}
