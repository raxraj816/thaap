-- THAAP velocity compensation. Exact bytes are governed by ADR-0010.
local expected_version = 'velocity_compensation_v1'
if ARGV[1] ~= expected_version then
  return 'WRONG_VERSION'
end

local member = ARGV[2]
local reservation_token = ARGV[3]
if not member or string.len(member) ~= 64 or not string.match(member, '^[0-9a-f]+$') then
  return 'INVALID_ARGUMENT'
end
if not reservation_token or string.len(reservation_token) ~= 32
    or not string.match(reservation_token, '^[0-9a-f]+$') then
  return 'INVALID_ARGUMENT'
end

local current_token = redis.call('GET', KEYS[2])
if not current_token then
  return 'NOT_FOUND'
end
if current_token ~= reservation_token then
  return 'STALE'
end

local removed = redis.call('ZREM', KEYS[1], member)
redis.call('DEL', KEYS[2])
if removed == 1 then
  return 'REMOVED'
end
return 'NOT_FOUND'
