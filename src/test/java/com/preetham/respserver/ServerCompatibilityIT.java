package com.preetham.respserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.preetham.respserver.command.CommandExecutor;
import com.preetham.respserver.command.CommandRegistry;
import com.preetham.respserver.config.ServerConfig;
import com.preetham.respserver.server.RedisServer;
import com.preetham.respserver.server.ServerFactory;
import com.preetham.respserver.stats.ServerStats;
import com.preetham.respserver.store.Database;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;

/**
 * Drives the server with Jedis -- a real, third-party Redis client.
 *
 * <h2>Why this matters more than the unit tests</h2>
 *
 * Every other test in this project checks the server against my own understanding of
 * the protocol. If that understanding is wrong, the tests are wrong in exactly the same
 * way and agree with each other perfectly. Jedis was written by people who have never
 * seen this code, against the real Redis specification, and it fails loudly on anything
 * non-conforming -- wrong reply types, wrong error codes, a missing CRLF.
 *
 * <p>That makes this suite the actual claim of "Redis-compatible". It is the difference
 * between "my parser agrees with my writer" and "an independent implementation agrees
 * with both".
 *
 * <p>The server binds port 0 so the OS assigns a free port. Hard-coding one makes the
 * suite fail whenever a stray server is running, and scanning for a free port has an
 * inherent race between the check and the bind.
 */
class ServerCompatibilityIT {

    private RedisServer server;
    private Jedis jedis;

    @BeforeEach
    void startServer() throws IOException {
        ServerConfig config = ServerConfig.forTests(ServerConfig.ServerMode.VIRTUAL_THREADS);

        Database database = new Database();
        CommandRegistry registry = CommandRegistry.standard();
        ServerStats stats = new ServerStats();
        // No AOF writer: persistence has its own dedicated suite.
        CommandExecutor executor = new CommandExecutor(registry, database, stats, null);

        server = ServerFactory.create(config, executor, stats);
        server.start();

        jedis = new Jedis("127.0.0.1", server.port());
    }

    @AfterEach
    void stopServer() {
        if (jedis != null) {
            jedis.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Nested
    @DisplayName("connection")
    class Connection {

        @Test
        void pingAndEcho() {
            assertThat(jedis.ping()).isEqualTo("PONG");
            assertThat(jedis.echo("hello")).isEqualTo("hello");
        }

        @Test
        void selectDatabaseZeroSucceeds() {
            assertThat(jedis.select(0)).isEqualTo("OK");
        }

        @Test
        void selectingAnotherDatabaseIsRejected() {
            assertThatThrownBy(() -> jedis.select(3))
                    .isInstanceOf(JedisDataException.class)
                    .hasMessageContaining("out of range");
        }

        @Test
        void clientNameRoundTripsWithinOneConnection() {
            jedis.clientSetname("integration-test");

            assertThat(jedis.clientGetname()).isEqualTo("integration-test");
        }

        @Test
        void infoIsParseable() {
            String info = jedis.info();

            assertThat(info).contains("# Server").contains("server_name:resp-server");
            assertThat(info).contains("connected_clients:");
        }
    }

    @Nested
    @DisplayName("strings")
    class Strings {

        @Test
        void setAndGet() {
            assertThat(jedis.set("k", "v")).isEqualTo("OK");
            assertThat(jedis.get("k")).isEqualTo("v");
        }

        @Test
        void getOnMissingKeyIsNull() {
            assertThat(jedis.get("absent")).isNull();
        }

        @Test
        @DisplayName("values survive bytes that would break a naive parser")
        void valuesAreBinarySafe() {
            byte[] key = "binary".getBytes();
            byte[] value = {0, '\r', '\n', '$', '*', (byte) 0xFF, 'e', 'n', 'd'};

            jedis.set(key, value);

            assertThat(jedis.get(key)).isEqualTo(value);
        }

        @Test
        void emptyValueRoundTrips() {
            jedis.set("empty", "");

            assertThat(jedis.get("empty")).isEmpty();
        }

        @Test
        void largeValueRoundTrips() {
            String large = "x".repeat(1_000_000);

            jedis.set("large", large);

            assertThat(jedis.get("large")).isEqualTo(large);
        }

        @Test
        void unicodeRoundTrips() {
            jedis.set("unicode", "héllo — 世界 — 🎉");

            assertThat(jedis.get("unicode")).isEqualTo("héllo — 世界 — 🎉");
        }

        @Test
        void incrementFamily() {
            assertThat(jedis.incr("n")).isEqualTo(1);
            assertThat(jedis.incrBy("n", 9)).isEqualTo(10);
            assertThat(jedis.decr("n")).isEqualTo(9);
            assertThat(jedis.decrBy("n", 4)).isEqualTo(5);
            assertThat(jedis.get("n")).isEqualTo("5");
        }

        @Test
        void incrementingANonNumberRaisesTheStandardError() {
            jedis.set("word", "abc");

            assertThatThrownBy(() -> jedis.incr("word"))
                    .isInstanceOf(JedisDataException.class)
                    .hasMessageContaining("not an integer");
        }

        @Test
        void appendAndStrlen() {
            assertThat(jedis.append("s", "abc")).isEqualTo(3);
            assertThat(jedis.append("s", "de")).isEqualTo(5);
            assertThat(jedis.strlen("s")).isEqualTo(5);
            assertThat(jedis.get("s")).isEqualTo("abcde");
        }

        @Test
        void msetAndMget() {
            jedis.mset("a", "1", "b", "2", "c", "3");

            assertThat(jedis.mget("a", "b", "c", "missing"))
                    .containsExactly("1", "2", "3", null);
        }

        @Test
        void setWithNxOnlyWritesOnce() {
            assertThat(jedis.setnx("once", "first")).isEqualTo(1);
            assertThat(jedis.setnx("once", "second")).isEqualTo(0);
            assertThat(jedis.get("once")).isEqualTo("first");
        }

        @Test
        void getdelReturnsThenRemoves() {
            jedis.set("temp", "value");

            assertThat(jedis.getDel("temp")).isEqualTo("value");
            assertThat(jedis.exists("temp")).isFalse();
        }
    }

    @Nested
    @DisplayName("keys and expiry")
    class KeysAndExpiry {

        @Test
        void existsDeleteAndType() {
            jedis.set("k", "v");

            assertThat(jedis.exists("k")).isTrue();
            assertThat(jedis.type("k")).isEqualTo("string");
            assertThat(jedis.del("k")).isEqualTo(1);
            assertThat(jedis.exists("k")).isFalse();
            assertThat(jedis.type("k")).isEqualTo("none");
        }

        @Test
        void deleteReportsHowManyKeysActuallyWent() {
            jedis.mset("a", "1", "b", "2");

            assertThat(jedis.del("a", "b", "never-existed")).isEqualTo(2);
        }

        @Test
        void keysMatchesGlobPatterns() {
            jedis.mset("user:1", "a", "user:2", "b", "admin:1", "c");

            assertThat(jedis.keys("user:*")).containsExactlyInAnyOrder("user:1", "user:2");
            assertThat(jedis.keys("*")).hasSize(3);
        }

        @Test
        void dbsizeAndFlushall() {
            jedis.mset("a", "1", "b", "2");
            assertThat(jedis.dbSize()).isEqualTo(2);

            jedis.flushAll();

            assertThat(jedis.dbSize()).isZero();
        }

        @Test
        void ttlUsesRedisThreeWayConvention() {
            assertThat(jedis.ttl("missing")).isEqualTo(-2);

            jedis.set("persistent", "v");
            assertThat(jedis.ttl("persistent")).isEqualTo(-1);

            jedis.expire("persistent", 100);
            assertThat(jedis.ttl("persistent")).isBetween(99L, 100L);
        }

        @Test
        void persistRemovesTheExpiry() {
            jedis.set("k", "v");
            jedis.expire("k", 100);

            assertThat(jedis.persist("k")).isEqualTo(1);
            assertThat(jedis.ttl("k")).isEqualTo(-1);
        }

        @Test
        void setWithExpirySetsATtl() {
            jedis.setex("k", 100, "v");

            assertThat(jedis.get("k")).isEqualTo("v");
            assertThat(jedis.ttl("k")).isBetween(99L, 100L);
        }

        @Test
        @DisplayName("a key with a past expiry is deleted immediately")
        void expiringInThePastDeletesTheKey() {
            jedis.set("k", "v");

            assertThat(jedis.expire("k", -1)).isEqualTo(1);
            assertThat(jedis.exists("k")).isFalse();
        }
    }

    @Nested
    @DisplayName("lists")
    class Lists {

        @Test
        @DisplayName("LPUSH reverses, RPUSH appends -- together they make a FIFO queue")
        void pushOrderMatchesRedis() {
            jedis.lpush("stack", "a", "b", "c");
            assertThat(jedis.lrange("stack", 0, -1)).containsExactly("c", "b", "a");

            jedis.rpush("queue", "a", "b", "c");
            assertThat(jedis.lrange("queue", 0, -1)).containsExactly("a", "b", "c");
        }

        @Test
        void popFromBothEnds() {
            jedis.rpush("k", "first", "middle", "last");

            assertThat(jedis.lpop("k")).isEqualTo("first");
            assertThat(jedis.rpop("k")).isEqualTo("last");
            assertThat(jedis.llen("k")).isEqualTo(1);
        }

        @Test
        @DisplayName("popping the last element removes the key entirely")
        void emptyListsDoNotExist() {
            jedis.rpush("k", "only");
            jedis.lpop("k");

            assertThat(jedis.exists("k")).isFalse();
            assertThat(jedis.type("k")).isEqualTo("none");
        }

        @Test
        void lrangeClampsRatherThanFailing() {
            jedis.rpush("k", "a", "b", "c", "d", "e");

            assertThat(jedis.lrange("k", 0, -1)).hasSize(5);
            assertThat(jedis.lrange("k", 1, 3)).containsExactly("b", "c", "d");
            assertThat(jedis.lrange("k", -2, -1)).containsExactly("d", "e");
            assertThat(jedis.lrange("k", 100, 200)).isEmpty();
            assertThat(jedis.lrange("missing", 0, -1)).isEmpty();
        }

        @Test
        void lindexHonoursNegativeIndices() {
            jedis.rpush("k", "a", "b", "c");

            assertThat(jedis.lindex("k", 0)).isEqualTo("a");
            assertThat(jedis.lindex("k", -1)).isEqualTo("c");
            assertThat(jedis.lindex("k", 99)).isNull();
        }

        @Test
        void popWithCountReturnsAnArray() {
            jedis.rpush("k", "a", "b", "c", "d");

            assertThat(jedis.lpop("k", 2)).containsExactly("a", "b");
            assertThat(jedis.llen("k")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("hashes")
    class Hashes {

        @Test
        void setGetAndDelete() {
            assertThat(jedis.hset("h", "field", "value")).isEqualTo(1);
            assertThat(jedis.hset("h", "field", "updated"))
                    .as("overwriting an existing field counts as zero additions").isEqualTo(0);
            assertThat(jedis.hget("h", "field")).isEqualTo("updated");
            assertThat(jedis.hdel("h", "field")).isEqualTo(1);
            assertThat(jedis.exists("h")).as("last field removed, key goes too").isFalse();
        }

        @Test
        void getAllReturnsAMap() {
            jedis.hset("h", java.util.Map.of("a", "1", "b", "2"));

            assertThat(jedis.hgetAll("h")).containsEntry("a", "1").containsEntry("b", "2");
            assertThat(jedis.hlen("h")).isEqualTo(2);
            assertThat(jedis.hkeys("h")).containsExactlyInAnyOrder("a", "b");
            assertThat(jedis.hvals("h")).containsExactlyInAnyOrder("1", "2");
        }

        @Test
        void existsAndMissingFields() {
            jedis.hset("h", "present", "yes");

            assertThat(jedis.hexists("h", "present")).isTrue();
            assertThat(jedis.hexists("h", "absent")).isFalse();
            assertThat(jedis.hget("h", "absent")).isNull();
            assertThat(jedis.hgetAll("missing")).isEmpty();
        }

        @Test
        @DisplayName("the deprecated HMSET is still supported, because Jedis sends it")
        void hmsetWorks() {
            jedis.hmset("h", java.util.Map.of("x", "1", "y", "2"));

            assertThat(jedis.hgetAll("h")).hasSize(2);
        }
    }

    @Nested
    @DisplayName("sets")
    class Sets {

        @Test
        void addIsIdempotentAndReportsNewMembersOnly() {
            assertThat(jedis.sadd("s", "a", "b")).isEqualTo(2);
            assertThat(jedis.sadd("s", "b", "c")).as("only c is new").isEqualTo(1);
            assertThat(jedis.scard("s")).isEqualTo(3);
        }

        @Test
        void membershipAndRemoval() {
            jedis.sadd("s", "a", "b", "c");

            assertThat(jedis.sismember("s", "a")).isTrue();
            assertThat(jedis.sismember("s", "z")).isFalse();
            assertThat(jedis.smembers("s")).containsExactlyInAnyOrder("a", "b", "c");
            assertThat(jedis.srem("s", "a", "missing")).isEqualTo(1);
        }

        @Test
        void removingEveryMemberRemovesTheKey() {
            jedis.sadd("s", "only");
            jedis.srem("s", "only");

            assertThat(jedis.exists("s")).isFalse();
        }
    }

    @Nested
    @DisplayName("sorted sets")
    class SortedSets {

        @Test
        void ordersByScoreThenLexicographically() {
            jedis.zadd("z", 2, "charlie");
            jedis.zadd("z", 1, "alice");
            jedis.zadd("z", 2, "bob");

            assertThat(jedis.zrange("z", 0, -1)).containsExactly("alice", "bob", "charlie");
            assertThat(jedis.zrevrange("z", 0, -1)).containsExactly("charlie", "bob", "alice");
        }

        @Test
        void scoresAndRanks() {
            jedis.zadd("z", 10, "a");
            jedis.zadd("z", 20, "b");
            jedis.zadd("z", 30, "c");

            assertThat(jedis.zscore("z", "b")).isEqualTo(20.0);
            assertThat(jedis.zscore("z", "missing")).isNull();
            assertThat(jedis.zrank("z", "a")).isZero();
            assertThat(jedis.zrank("z", "c")).isEqualTo(2);
            assertThat(jedis.zrevrank("z", "c")).isZero();
            assertThat(jedis.zrank("z", "missing")).isNull();
            assertThat(jedis.zcard("z")).isEqualTo(3);
        }

        @Test
        void withScoresInterleavesMembersAndScores() {
            jedis.zadd("z", 1.5, "a");
            jedis.zadd("z", 2, "b");

            var withScores = jedis.zrangeWithScores("z", 0, -1);

            assertThat(withScores).hasSize(2);
            assertThat(withScores.get(0).getElement()).isEqualTo("a");
            assertThat(withScores.get(0).getScore()).isEqualTo(1.5);
            assertThat(withScores.get(1).getScore()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("a whole score comes back as \"3\", not \"3.0\"")
        void wholeScoresAreFormattedWithoutADecimalPoint() {
            jedis.zadd("z", 3, "member");

            Object raw = jedis.sendCommand(() -> "ZSCORE".getBytes(), "z", "member");

            assertThat(new String((byte[]) raw)).isEqualTo("3");
        }

        @Test
        void updatingAScoreReorders() {
            jedis.zadd("z", 1, "a");
            jedis.zadd("z", 2, "b");

            jedis.zadd("z", 99, "a");

            assertThat(jedis.zrange("z", 0, -1)).containsExactly("b", "a");
        }

        @Test
        void incrementAdjustsTheScore() {
            jedis.zadd("z", 10, "a");

            assertThat(jedis.zincrby("z", 5, "a")).isEqualTo(15.0);
            assertThat(jedis.zincrby("z", 1, "fresh")).isEqualTo(1.0);
        }

        @Test
        void removingEveryMemberRemovesTheKey() {
            jedis.zadd("z", 1, "only");
            jedis.zrem("z", "only");

            assertThat(jedis.exists("z")).isFalse();
        }
    }

    @Nested
    @DisplayName("type discipline across all five types")
    class TypeDiscipline {

        @Test
        @DisplayName("every type rejects commands meant for another")
        void mismatchedCommandsRaiseWrongType() {
            jedis.set("string", "v");
            jedis.rpush("list", "v");
            jedis.hset("hash", "f", "v");
            jedis.sadd("set", "v");
            jedis.zadd("zset", 1, "v");

            assertThat(jedis.type("string")).isEqualTo("string");
            assertThat(jedis.type("list")).isEqualTo("list");
            assertThat(jedis.type("hash")).isEqualTo("hash");
            assertThat(jedis.type("set")).isEqualTo("set");
            assertThat(jedis.type("zset")).isEqualTo("zset");

            assertThatThrownBy(() -> jedis.lpush("string", "x"))
                    .isInstanceOf(JedisDataException.class).hasMessageContaining("WRONGTYPE");
            assertThatThrownBy(() -> jedis.get("list"))
                    .isInstanceOf(JedisDataException.class).hasMessageContaining("WRONGTYPE");
            assertThatThrownBy(() -> jedis.sadd("hash", "x"))
                    .isInstanceOf(JedisDataException.class).hasMessageContaining("WRONGTYPE");
            assertThatThrownBy(() -> jedis.zadd("set", 1, "x"))
                    .isInstanceOf(JedisDataException.class).hasMessageContaining("WRONGTYPE");
            assertThatThrownBy(() -> jedis.incr("zset"))
                    .isInstanceOf(JedisDataException.class).hasMessageContaining("WRONGTYPE");

            // And the connection is still perfectly usable after all of that.
            assertThat(jedis.ping()).isEqualTo("PONG");
        }

        @Test
        @DisplayName("a TTL set on a collection is preserved by later mutations")
        void collectionsKeepTheirExpiry() {
            jedis.rpush("k", "a");
            jedis.expire("k", 100);

            jedis.rpush("k", "b");

            assertThat(jedis.ttl("k")).as("pushing must not make the key immortal")
                    .isBetween(99L, 100L);
        }
    }

    @Nested
    @DisplayName("errors keep the connection usable")
    class ErrorHandling {

        @Test
        void unknownCommandIsAnErrorNotADisconnect() {
            assertThatThrownBy(() -> jedis.sendCommand(() -> "NOSUCHCOMMAND".getBytes()))
                    .isInstanceOf(JedisDataException.class)
                    .hasMessageContaining("unknown command");

            // The connection must still work afterwards; a server that closed on error
            // would fail here.
            assertThat(jedis.ping()).isEqualTo("PONG");
        }

        @Test
        void wrongArityIsAnErrorNotADisconnect() {
            assertThatThrownBy(() -> jedis.sendCommand(() -> "GET".getBytes()))
                    .isInstanceOf(JedisDataException.class)
                    .hasMessageContaining("wrong number of arguments");

            assertThat(jedis.ping()).isEqualTo("PONG");
        }

        @Test
        void badSetSyntaxIsRejected() {
            assertThatThrownBy(() -> jedis.sendCommand(
                    () -> "SET".getBytes(), "k", "v", "BOGUS"))
                    .isInstanceOf(JedisDataException.class)
                    .hasMessageContaining("syntax error");

            assertThat(jedis.ping()).isEqualTo("PONG");
        }
    }

    @Nested
    @DisplayName("pipelining")
    class Pipelining {

        @Test
        @DisplayName("many commands in one write get many replies, in order")
        void pipelinedCommandsAllSucceed() {
            var pipeline = jedis.pipelined();
            for (int i = 0; i < 500; i++) {
                pipeline.set("key:" + i, "value:" + i);
            }
            List<Object> results = pipeline.syncAndReturnAll();

            assertThat(results).hasSize(500);
            assertThat(jedis.dbSize()).isEqualTo(500);
            assertThat(jedis.get("key:499")).isEqualTo("value:499");
        }

        @Test
        void repliesMatchTheOrderOfRequests() {
            jedis.mset("a", "1", "b", "2", "c", "3");

            var pipeline = jedis.pipelined();
            var a = pipeline.get("a");
            var b = pipeline.get("b");
            var c = pipeline.get("c");
            pipeline.sync();

            assertThat(a.get()).isEqualTo("1");
            assertThat(b.get()).isEqualTo("2");
            assertThat(c.get()).isEqualTo("3");
        }
    }
}
