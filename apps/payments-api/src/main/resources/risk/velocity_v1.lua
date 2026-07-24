-- THAAP velocity_v1. Exact bytes are governed by ADR-0010.
local expected_version = 'velocity_v1'
if ARGV[1] ~= expected_version then
  return {'ERROR', expected_version, '0', '0', 'WRONG_VERSION'}
end

local member = ARGV[2]
local window_ms = tonumber(ARGV[3])
local ttl_ms = tonumber(ARGV[4])
local max_members = tonumber(ARGV[5])
local reservation_token = ARGV[6]
if not member or string.len(member) ~= 64 or not string.match(member, '^[0-9a-f]+$') then
  return {'ERROR', expected_version, '0', '0', 'INVALID_MEMBER'}
end
if not window_ms or not ttl_ms or not max_members or window_ms < 1000
    or ttl_ms < window_ms or ttl_ms > 86400000 or max_members < 1 then
  return {'ERROR', expected_version, '0', '0', 'INVALID_LIMIT'}
end
if not reservation_token or string.len(reservation_token) ~= 32
    or not string.match(reservation_token, '^[0-9a-f]+$') then
  return {'ERROR', expected_version, '0', '0', 'INVALID_MEMBER'}
end

local redis_time = redis.call('TIME')
local now_ms = (tonumber(redis_time[1]) * 1000) + math.floor(tonumber(redis_time[2]) / 1000)
local cutoff_ms = now_ms - window_ms
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff_ms)

local existing_score = redis.call('ZSCORE', KEYS[1], member)
local members_before = redis.call('ZCARD', KEYS[1])
if not existing_score and members_before >= max_members then
  redis.call('PEXPIRE', KEYS[1], ttl_ms)
  return {'LIMIT', expected_version, tostring(now_ms), tostring(members_before), 'CARDINALITY_LIMIT'}
end

local other_count = members_before
local mode = 'FIRST'
if existing_score then
  other_count = members_before - 1
  mode = 'RETRY'
else
  redis.call('ZADD', KEYS[1], 'NX', now_ms, member)
  redis.call('SET', KEYS[2], reservation_token, 'PX', ttl_ms)
end
redis.call('PEXPIRE', KEYS[1], ttl_ms)
return {'OK', expected_version, tostring(now_ms), tostring(other_count), mode}
