if redis.replicate_commands then redis.replicate_commands() end
local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local retry = tonumber(redis.call('HGET', KEYS[1], 'retryAt') or '0')
local epoch = redis.call('HGET', KEYS[1], 'epoch') or 'initial'
if retry == 0 then return 'closed:' .. epoch end
local probeUntil = tonumber(redis.call('HGET', KEYS[1], 'probeUntil') or '0')
if retry > now or probeUntil > now then return 'blocked' end
redis.call('HMSET', KEYS[1], 'probe', ARGV[1], 'probeUntil', now + tonumber(ARGV[2]))
return 'probe:' .. ARGV[1]
