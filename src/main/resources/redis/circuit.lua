-- 模型熔断。命令在 ARGV[1]：acquire、release、success、failure。
-- 状态字段：state=closed|open|half，failures，openedAt，probes，probeUntil。
local key = KEYS[1]
local cmd = ARGV[1]

local function clearProbe()
  redis.call('HSET', key, 'probes', '0', 'probeUntil', '0')
end

if cmd == 'acquire' then
  local now = tonumber(ARGV[2])
  local openMs = tonumber(ARGV[3])
  local maxProbes = tonumber(ARGV[4])
  local leaseMs = tonumber(ARGV[5])
  local state = redis.call('HGET', key, 'state') or 'closed'
  if state == 'open' then
    local openedAt = tonumber(redis.call('HGET', key, 'openedAt') or '0')
    if now < openedAt + openMs then
      return 0
    end
    redis.call('HSET', key, 'state', 'half', 'probes', '0', 'probeUntil', '0')
    state = 'half'
  end
  if state == 'half' then
    local probes = tonumber(redis.call('HGET', key, 'probes') or '0')
    local probeUntil = tonumber(redis.call('HGET', key, 'probeUntil') or '0')
    if probes > 0 and now >= probeUntil then
      redis.call('HSET', key, 'probes', '0')
      probes = 0
    end
    if probes >= maxProbes then
      return 0
    end
    redis.call('HINCRBY', key, 'probes', 1)
    redis.call('HSET', key, 'probeUntil', tostring(now + leaseMs))
    return 2
  end
  return 1
end

if cmd == 'release' then
  local probes = tonumber(redis.call('HGET', key, 'probes') or '0')
  if probes > 0 then
    redis.call('HINCRBY', key, 'probes', -1)
  end
  return 1
end

if cmd == 'success' then
  redis.call('HSET', key, 'state', 'closed', 'failures', '0', 'openedAt', '0', 'probes', '0', 'probeUntil', '0')
  return 1
end

if cmd == 'failure' then
  local now = tonumber(ARGV[2])
  local threshold = tonumber(ARGV[3])
  local probing = tonumber(ARGV[4])
  if probing == 1 then
    redis.call('HSET', key, 'state', 'open', 'failures', '0', 'openedAt', tostring(now), 'probes', '0', 'probeUntil', '0')
    return 1
  end
  local failures = tonumber(redis.call('HGET', key, 'failures') or '0') + 1
  if failures >= threshold then
    redis.call('HSET', key, 'state', 'open', 'failures', '0', 'openedAt', tostring(now), 'probes', '0', 'probeUntil', '0')
  else
    redis.call('HSET', key, 'failures', tostring(failures))
    clearProbe()
  end
  return 1
end

return -1
