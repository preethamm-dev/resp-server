package com.preetham.respserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 0, ServerConfig.ServerMode.VIRTUAL_THREADS, 100, false);

        server = ServerFactory.create(config, new Database(), CommandRegistry.standard(),
                new ServerStats());
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
