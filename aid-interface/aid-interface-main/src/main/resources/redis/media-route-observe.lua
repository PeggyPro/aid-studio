if redis.replicate_commands then redis.replicate_commands() end
local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local attempt, outcome, reason = ARGV[1], ARGV[2], ARGV[3]
local epoch = redis.call('HGET', KEYS[1], 'epoch') or 'initial'
local retry = tonumber(redis.call('HGET', KEYS[1], 'retryAt') or '0')
local isProbe = string.sub(attempt, 1, 6) == 'probe:'
if isProbe then
    local probe = redis.call('HGET', KEYS[1], 'probe')
    if not probe or attempt ~= 'probe:' .. probe then return 0 end
    redis.call('HDEL', KEYS[1], 'probe', 'probeUntil')
else
    if attempt ~= 'closed:' .. epoch or retry ~= 0 then return 0 end
end
if outcome == 'healthy' then
    if isProbe then
        redis.call('DEL', KEYS[1])
        redis.call('HSET', KEYS[1], 'epoch', ARGV[4])
        redis.call('PEXPIRE', KEYS[1], ARGV[8])
        redis.call('ZREM', KEYS[2], KEYS[1])
        return 2
    end
    redis.call('HDEL', KEYS[1], 'streak', 'windowEnd')
    return 0
end
if outcome == 'neutral' then
    if isProbe then redis.call('HSET', KEYS[1], 'retryAt', now + tonumber(ARGV[6])) end
    return 0
end
if not isProbe then
    local window = tonumber(redis.call('HGET', KEYS[1], 'windowEnd') or '0')
    if window <= now then
        redis.call('HMSET', KEYS[1], 'streak', 0, 'windowEnd', now + tonumber(ARGV[5]))
    end
    local streak = redis.call('HINCRBY', KEYS[1], 'streak', 1)
    redis.call('PEXPIRE', KEYS[1], ARGV[8])
    if streak < tonumber(ARGV[7]) then return 0 end
end
local level = math.min(5, tonumber(redis.call('HGET', KEYS[1], 'level') or '0') + 1)
local cooldown = math.min(300000, tonumber(ARGV[6]) * 2 ^ (level - 1))
redis.call('HMSET', KEYS[1], 'epoch', ARGV[4], 'retryAt', now + cooldown,
    'level', level, 'reason', reason)
redis.call('PEXPIRE', KEYS[1], ARGV[8])
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
redis.call('ZADD', KEYS[2], now + tonumber(ARGV[8]), KEYS[1])
redis.call('PEXPIRE', KEYS[2], ARGV[8])
return 1
