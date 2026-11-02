-- 滚动 60 秒。先丢掉年龄大于等于 60 秒的成员，再按条数判断 RPM。
-- 60000 必须与 RateWindows.WINDOW_MILLIS 一致。
-- ARGV: nowMillis, rpmLimit, reservationId, ttlSeconds。
local key = KEYS[1]
local now = tonumber(ARGV[1])
local rpmLimit = tonumber(ARGV[2])
local reservationId = ARGV[3]
local ttl = tonumber(ARGV[4])

redis.call('ZREMRANGEBYSCORE', key, '-inf', now - 60000)

local count = redis.call('ZCARD', key)
if rpmLimit > 0 and (count + 1) > rpmLimit then
  return 0
end

redis.call('ZADD', key, now, reservationId)
redis.call('EXPIRE', key, ttl)
return 1
