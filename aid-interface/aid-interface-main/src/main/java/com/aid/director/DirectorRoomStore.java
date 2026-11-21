package com.aid.director;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** 分布式会话、一次性票据及有界准入。 */
@Component
public class DirectorRoomStore {
    private static final String PREFIX = "aid:director:";
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private static final ThreadLocal<java.util.List<Runnable>> AFTER_COMMIT = new ThreadLocal<>();

    /** 信令只在房间CAS成功后投递，失败不泄露未提交状态。 */
    public static void afterCommit(Runnable action) {
        var pending = AFTER_COMMIT.get();
        if (pending == null) action.run();
        else {
            if (pending.size() >= 256) throw DirectorException.invalid();
            pending.add(action);
        }
    }
    private static final DefaultRedisScript<String> CONSUME = new DefaultRedisScript<>(
            "local v=redis.call('get',KEYS[1]);if v then redis.call('del',KEYS[1]) end;return v", String.class);
    private static final DefaultRedisScript<Long> COMPARE_DELETE = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) end;return 0", Long.class);
    private static final DefaultRedisScript<Long> CAS = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1])==ARGV[1] then redis.call('set',KEYS[1],ARGV[2],'EX',ARGV[3]);return 1 end;return 0", Long.class);
    private static final DefaultRedisScript<Long> QUOTA = new DefaultRedisScript<>(
            "redis.call('zremrangebyscore',KEYS[1],'-inf',ARGV[1]);if redis.call('zcard',KEYS[1])>=tonumber(ARGV[2]) then return 0 end;"
            + "redis.call('zadd',KEYS[1],ARGV[3],ARGV[4]);redis.call('expire',KEYS[1],ARGV[5]);return 1", Long.class);
    private static final DefaultRedisScript<Long> RATE = new DefaultRedisScript<>(
            "local n=redis.call('incr',KEYS[1]);if n==1 then redis.call('expire',KEYS[1],ARGV[1]) end;return n", Long.class);

    public DirectorRoomStore(RedisConnectionFactory factory, RedissonClient redisson) {
        this.redis = new StringRedisTemplate(factory);
        this.redisson = redisson;
    }

    public DirectorRoom get(String id) {
        String value = redis.opsForValue().get(key(id));
        if (value == null) throw new DirectorException("SESSION_UNAVAILABLE", 404, "会话不可用");
        return JSON.parseObject(value, DirectorRoom.class);
    }

    public void create(DirectorRoom room) {
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(room.getId()), JSON.toJSONString(room), ttl(room))))
            throw DirectorException.conflict("SESSION_CONFLICT");
    }

    public <T> T mutate(String id, Function<DirectorRoom, T> operation) {
        String key = key(id);
        RLock lock = redisson.getLock(key + ":guard");
        boolean held = false;
        boolean committed = false;
        java.util.List<Runnable> pending = new java.util.ArrayList<>();
        try {
            held = lock.tryLock(500, TimeUnit.MILLISECONDS);
            if (!held) throw new DirectorException("SESSION_BUSY", 429, "操作繁忙，请稍后");
            String previous = redis.opsForValue().get(key);
            if (previous == null) throw new DirectorException("SESSION_UNAVAILABLE", 404, "会话不可用");
            DirectorRoom room = JSON.parseObject(previous, DirectorRoom.class);
            long now = System.currentTimeMillis();
            boolean stale = false;
            if (room.getHostConnection() != null && now - room.getHostSeenAt() > 35000) {
                room.setHostConnection(null); room.setHostInstance(null); stale = true;
            }
            if (room.getGuestConnection() != null && now - room.getGuestSeenAt() > 35000) {
                room.setGuestConnection(null); room.setGuestInstance(null); stale = true;
            }
            if (stale) {
                room.setLeaseUntil(0); room.setHostReady(false); room.setGuestReady(false);
                if (!java.util.Set.of("CLOSED", "REVOKED", "EXPIRED").contains(room.getState())) room.setState("SUSPENDED");
                room.setVersion(room.getVersion() + 1);
            }
            if (AFTER_COMMIT.get() != null) throw new IllegalStateException("nested director mutation");
            AFTER_COMMIT.set(pending);
            T result = operation.apply(room);
            Long changed = redis.execute(CAS, List.of(key), previous, JSON.toJSONString(room), String.valueOf(ttl(room).toSeconds()));
            if (!Long.valueOf(1).equals(changed)) throw DirectorException.conflict("SESSION_CONFLICT");
            committed = true;
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DirectorException("DEPENDENCY_UNAVAILABLE", 503, "连接暂不可用");
        } finally {
            AFTER_COMMIT.remove();
            if (held && lock.isHeldByCurrentThread()) lock.unlock();
            if (committed) for (Runnable action : pending) {
                try { action.run(); } catch (RuntimeException ignored) { /* 客户端按ACK或状态查询恢复。 */ }
            }
        }
    }

    public String ticket(JSONObject payload) {
        String token = DirectorIds.token();
        redis.opsForValue().set(PREFIX + "ticket:" + DirectorIds.hash(token), payload.toJSONString(), Duration.ofSeconds(60));
        return token;
    }

    public JSONObject consumeTicket(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw DirectorException.denied();
        String value = redis.execute(CONSUME, List.of(PREFIX + "ticket:" + DirectorIds.hash(token)));
        if (value == null) throw new DirectorException("INVALID_TICKET", 401, "连接凭证已失效");
        return JSON.parseObject(value);
    }

    public String reserveRequest(Long user, String requestId, String value, int seconds) {
        String key = PREFIX + "request:" + user + ":" + DirectorIds.key(requestId);
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, value, Duration.ofSeconds(seconds)))) return value;
        return redis.opsForValue().get(key);
    }

    public void releaseRequest(Long user, String request, String value) {
        redis.execute(COMPARE_DELETE, List.of(PREFIX + "request:" + user + ":" + request), value);
    }

    public void reserveCapacity(DirectorRoom room, int userLimit, int globalLimit) {
        reserveQuota("global", room, globalLimit);
        try { reserveQuota("user:" + room.getHostUserId(), room, userLimit); }
        catch (RuntimeException e) { redis.opsForZSet().remove(PREFIX + "quota:global", room.getId()); throw e; }
    }

    private void reserveQuota(String scope, DirectorRoom room, int limit) {
        Long reserved = redis.execute(QUOTA, List.of(PREFIX + "quota:" + scope), String.valueOf(System.currentTimeMillis()),
                String.valueOf(limit), String.valueOf(room.getExpiresAt()), room.getId(), "2100");
        if (!Long.valueOf(1).equals(reserved)) throw new DirectorException("CAPACITY_REACHED", 429, "连接数量已达上限");
    }

    public void releaseCapacity(DirectorRoom room) {
        redis.opsForZSet().remove(PREFIX + "quota:global", room.getId());
        redis.opsForZSet().remove(PREFIX + "quota:user:" + room.getHostUserId(), room.getId());
    }

    public void rate(String scope, int limit, int seconds) {
        Long count = redis.execute(RATE, List.of(PREFIX + "rate:" + DirectorIds.hash(scope)), String.valueOf(seconds));
        if (count == null || count > limit) throw new DirectorException("RATE_LIMITED", 429, "操作过于频繁");
    }

    public StringRedisTemplate redis() { return redis; }
    private String key(String id) { return PREFIX + "room:{" + DirectorIds.key(id) + "}"; }
    private Duration ttl(DirectorRoom room) { return Duration.ofSeconds(Math.max(60, (room.getExpiresAt() - System.currentTimeMillis()) / 1000 + 300)); }
}
