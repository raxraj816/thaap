-- THAAP rate_limit_fixed_window_v1. Exact bytes are governed as an exact-byte production boundary.
local expected_version = 'rate_limit_fixed_window_v1'
if ARGV[1] ~= expected_version then
  return {'ERROR', expected_version, '0', '0', '0', '0', 'WRONG_VERSION', '0'}
end

local member = ARGV[2]
local window_ms = tonumber(ARGV[3])
local limit = tonumber(ARGV[4])
if not member or string.len(member) ~= 64 or not string.match(member, '^[0-9a-f]+$') then
  return {'ERROR', expected_version, '0', '0', '0', '0', 'INVALID_MEMBER', '0'}
end
if not window_ms or not limit or window_ms < 1000 or window_ms > 86400000
    or limit < 1 or limit > 100000 then
  return {'ERROR', expected_version, '0', '0', '0', '0', 'INVALID_LIMIT', '0'}
end

local redis_time = redis.call('TIME')
local now_ms = (tonumber(redis_time[1]) * 1000) + math.floor(tonumber(redis_time[2]) / 1000)
local window_start = now_ms - (now_ms % window_ms)
local stored_window = redis.call('HGET', KEYS[1], '__window')
if not stored_window or tonumber(stored_window) ~= window_start then
  redis.call('DEL', KEYS[1])
  redis.call('HSET', KEYS[1], '__window', tostring(window_start))
end

local retry_ms = window_start + window_ms - now_ms
local existing = redis.call('HEXISTS', KEYS[1], member)
local count = redis.call('HLEN', KEYS[1]) - 1
if existing == 1 then
  redis.call('PEXPIRE', KEYS[1], window_ms * 2)
  return {'ALLOWED', expected_version, tostring(now_ms), tostring(count), tostring(limit),
          tostring(retry_ms), 'RETRY', tostring(window_start)}
end
if count >= limit then
  redis.call('PEXPIRE', KEYS[1], window_ms * 2)
  return {'EXCEEDED', expected_version, tostring(now_ms), tostring(count), tostring(limit),
          tostring(retry_ms), 'REJECTED', tostring(window_start)}
end

redis.call('HSET', KEYS[1], member, '1')
count = count + 1
redis.call('PEXPIRE', KEYS[1], window_ms * 2)
return {'ALLOWED', expected_version, tostring(now_ms), tostring(count), tostring(limit),
        tostring(retry_ms), 'FIRST', tostring(window_start)}
