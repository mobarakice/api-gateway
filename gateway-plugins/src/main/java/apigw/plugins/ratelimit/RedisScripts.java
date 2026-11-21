package apigw.plugins.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Lua scripts that take up to {@code requested} units of quota atomically in Redis (ADR 0018). Each
 * returns {@code {granted, remaining, retryAfterMs, resetMs}} and uses the Redis server clock, so all
 * gateways agree on window boundaries. Keys are derived from {@code KEYS[1]}: standalone Redis (or a
 * single-shard deployment); Redis Cluster is not supported.
 */
final class RedisScripts {

    static final String TOKEN_BUCKET = """
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local cap = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local req = tonumber(ARGV[3])
            local s = redis.call('HMGET', KEYS[1], 'tk', 'ts')
            local tokens = tonumber(s[1])
            local ts = tonumber(s[2])
            if tokens == nil or ts == nil then tokens = cap; ts = now end
            if now > ts then tokens = math.min(cap, tokens + (now - ts) * rate) end
            local grant = math.min(req, math.floor(tokens))
            tokens = tokens - grant
            redis.call('HSET', KEYS[1], 'tk', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            local retry = 0
            if grant == 0 then retry = math.max(1, math.ceil((1 - tokens) / rate)) end
            return {grant, math.floor(tokens), retry, math.ceil((cap - tokens) / rate)}
            """;

    static final String FIXED_WINDOW = """
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local limit = tonumber(ARGV[1])
            local w = tonumber(ARGV[2])
            local req = tonumber(ARGV[3])
            local idx = math.floor(now / w)
            local k = KEYS[1] .. ':' .. idx
            local used = tonumber(redis.call('GET', k) or '0')
            local grant = math.max(0, math.min(req, limit - used))
            if grant > 0 then
              redis.call('INCRBY', k, grant)
              redis.call('PEXPIRE', k, w)
            end
            local reset = (idx + 1) * w - now
            local retry = 0
            if grant == 0 then retry = math.max(1, reset) end
            return {grant, math.max(0, limit - used - grant), retry, reset}
            """;

    static final String SLIDING_WINDOW = """
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local limit = tonumber(ARGV[1])
            local w = tonumber(ARGV[2])
            local req = tonumber(ARGV[3])
            local idx = math.floor(now / w)
            local k1 = KEYS[1] .. ':' .. idx
            local k0 = KEYS[1] .. ':' .. (idx - 1)
            local cur = tonumber(redis.call('GET', k1) or '0')
            local prev = tonumber(redis.call('GET', k0) or '0')
            local elapsed = now - idx * w
            local used = prev * ((w - elapsed) / w) + cur
            local grant = math.max(0, math.min(req, math.floor(limit - used)))
            if grant > 0 then
              redis.call('INCRBY', k1, grant)
              redis.call('PEXPIRE', k1, 2 * w)
            end
            local retry = 0
            if grant == 0 then
              retry = w - elapsed
              if prev > 0 then retry = math.min(retry, math.ceil((used + 1 - limit) / prev * w)) end
              retry = math.max(1, retry)
            end
            return {grant, math.max(0, math.floor(limit - used - grant)), retry, 2 * w - elapsed}
            """;

    private RedisScripts() {
    }

    static String script(Algorithm a) {
        return switch (a) {
            case TOKEN_BUCKET -> TOKEN_BUCKET;
            case FIXED_WINDOW -> FIXED_WINDOW;
            case SLIDING_WINDOW -> SLIDING_WINDOW;
        };
    }

    /** SHA-1 of the script text, as Redis computes it for EVALSHA (JDK digest; not a security use). */
    static String sha1(String script) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(script.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
