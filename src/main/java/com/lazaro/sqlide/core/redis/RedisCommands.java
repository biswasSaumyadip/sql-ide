package com.lazaro.sqlide.core.redis;

/**
 * Redis command names used for highlighting and autocomplete when a Redis
 * connection is active.
 */
public final class RedisCommands {

    public static final String[] KEYWORDS = {
            "APPEND", "AUTH", "BGREWRITEAOF", "BGSAVE",
            "BITCOUNT", "BITOP", "BITPOS",
            "BLMOVE", "BLPOP", "BRPOP", "BRPOPLPUSH",
            "BZMPOP", "BZPOPMAX", "BZPOPMIN",
            "CLIENT", "CLUSTER", "COMMAND", "CONFIG", "COPY",
            "DBSIZE", "DECR", "DECRBY", "DEL", "DISCARD", "DUMP",
            "ECHO", "EVAL", "EVALSHA", "EXEC", "EXISTS", "EXPIRE", "EXPIREAT", "EXPIRETIME",
            "FLUSHALL", "FLUSHDB",
            "GEOADD", "GEODIST", "GEOHASH", "GEOPOS", "GEORADIUS", "GEOSEARCH",
            "GET", "GETBIT", "GETDEL", "GETEX", "GETRANGE", "GETSET",
            "HDEL", "HELLO", "HEXISTS", "HGET", "HGETALL", "HINCRBY", "HINCRBYFLOAT",
            "HKEYS", "HLEN", "HMGET", "HMSET", "HRANDFIELD", "HSCAN",
            "HSET", "HSETNX", "HSTRLEN", "HVALS",
            "INCR", "INCRBY", "INCRBYFLOAT", "INFO",
            "KEYS",
            "LASTSAVE", "LINDEX", "LINSERT", "LLEN", "LMOVE", "LMPOP", "LPOP", "LPOS",
            "LPUSH", "LPUSHX", "LRANGE", "LREM", "LSET", "LTRIM",
            "MEMORY", "MGET", "MIGRATE", "MONITOR", "MOVE", "MSET", "MSETNX", "MULTI",
            "OBJECT",
            "PERSIST", "PEXPIRE", "PEXPIREAT", "PEXPIRETIME", "PFADD", "PFCOUNT", "PFMERGE",
            "PING", "PSETEX", "PSUBSCRIBE", "PTTL", "PUBLISH", "PUBSUB", "PUNSUBSCRIBE",
            "QUIT",
            "RANDOMKEY", "RENAME", "RENAMENX", "REPLICAOF", "RESTORE", "ROLE", "RPOP",
            "RPOPLPUSH", "RPUSH", "RPUSHX",
            "SADD", "SAVE", "SCAN", "SCARD", "SCRIPT", "SDIFF", "SDIFFSTORE", "SELECT",
            "SET", "SETBIT", "SETEX", "SETNX", "SETRANGE", "SHUTDOWN", "SINTER", "SINTERSTORE",
            "SISMEMBER", "SLAVEOF", "SLOWLOG", "SMEMBERS", "SMOVE", "SORT", "SPOP", "SRANDMEMBER",
            "SREM", "SSCAN", "STRLEN", "SUBSCRIBE", "SUNION", "SUNIONSTORE", "SWAPDB", "SYNC",
            "TIME", "TOUCH", "TTL", "TYPE",
            "UNLINK", "UNSUBSCRIBE", "UNWATCH",
            "WAIT", "WATCH",
            "XACK", "XADD", "XAUTOCLAIM", "XCLAIM", "XDEL", "XGROUP", "XINFO", "XLEN",
            "XPENDING", "XRANGE", "XREAD", "XREADGROUP", "XREVRANGE", "XTRIM",
            "ZADD", "ZCARD", "ZCOUNT", "ZDIFF", "ZINCRBY", "ZINTER", "ZINTERSTORE",
            "ZLEXCOUNT", "ZMPOP", "ZMSCORE", "ZPOPMAX", "ZPOPMIN", "ZRANDMEMBER",
            "ZRANGE", "ZRANGEBYLEX", "ZRANGEBYSCORE", "ZRANK", "ZREM", "ZREMRANGEBYLEX",
            "ZREMRANGEBYRANK", "ZREMRANGEBYSCORE", "ZREVRANGE", "ZREVRANGEBYLEX",
            "ZREVRANGEBYSCORE", "ZREVRANK", "ZSCAN", "ZSCORE", "ZUNION", "ZUNIONSTORE"
    };

    private RedisCommands() {
    }
}
