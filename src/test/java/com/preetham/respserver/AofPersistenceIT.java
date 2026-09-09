package com.preetham.respserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.preetham.respserver.command.CommandExecutor;
import com.preetham.respserver.command.CommandRegistry;
import com.preetham.respserver.config.ServerConfig;
import com.preetham.respserver.persistence.AofLoader;
import com.preetham.respserver.persistence.AofWriter;
import com.preetham.respserver.persistence.FsyncPolicy;
import com.preetham.respserver.server.RedisServer;
import com.preetham.respserver.server.ServerFactory;
import com.preetham.respserver.stats.ServerStats;
import com.preetham.respserver.store.Database;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;

/**
 * Durability: what survives a restart, and what happens when the process dies mid-write.
 *
 * <p>The happy path is the easy half. The half worth writing is the crash: a process
 * killed while appending leaves a partial record, and the loader has to replay everything
 * complete, discard the fragment, and leave the file on a clean boundary so the next
 * append is not corrupted by it.
 */
class AofPersistenceIT {

    @TempDir
    Path tempDir;

    /** Starts a server over the given AOF file, replaying it first. */
    private record Fixture(RedisServer server, Database database, AofWriter aof, int port)
            implements AutoCloseable {

        @Override
        public void close() {
            server.close();
            if (aof != null) {
                aof.close();
            }
        }
    }

    private Fixture start(Path aofPath, FsyncPolicy policy) throws IOException {
        Database database = new Database();
        CommandRegistry registry = CommandRegistry.standard();
        ServerStats stats = new ServerStats();

        AofLoader.load(aofPath, database, registry);
        AofWriter writer = new AofWriter(aofPath, policy);

        CommandExecutor executor = new CommandExecutor(registry, database, stats, writer);
        ServerConfig config = ServerConfig.forTests(ServerConfig.ServerMode.VIRTUAL_THREADS);
        RedisServer server = ServerFactory.create(config, executor, stats);
        server.start();

        return new Fixture(server, database, writer, server.port());
    }

    @Nested
    @DisplayName("restart")
    class Restart {

        @Test
        @DisplayName("every acknowledged write survives a clean restart")
        void dataSurvivesRestart() throws Exception {
            Path aof = tempDir.resolve("appendonly.aof");

            try (Fixture first = start(aof, FsyncPolicy.ALWAYS);
                 Jedis jedis = new Jedis("127.0.0.1", first.port())) {
                for (int i = 0; i < 1000; i++) {
                    jedis.set("key:" + i, "value:" + i);
                }
                jedis.hset("profile", "name", "preetham");
                jedis.rpush("queue", "a", "b", "c");
                jedis.sadd("tags", "java", "redis");
                jedis.zadd("leaderboard", 42, "player1");
                assertThat(jedis.dbSize()).isEqualTo(1004);
            }

            try (Fixture second = start(aof, FsyncPolicy.ALWAYS);
                 Jedis jedis = new Jedis("127.0.0.1", second.port())) {
                assertThat(jedis.dbSize()).isEqualTo(1004);
                assertThat(jedis.get("key:0")).isEqualTo("value:0");
                assertThat(jedis.get("key:999")).isEqualTo("value:999");
                assertThat(jedis.hget("profile", "name")).isEqualTo("preetham");
                assertThat(jedis.lrange("queue", 0, -1)).containsExactly("a", "b", "c");
                assertThat(jedis.smembers("tags")).containsExactlyInAnyOrder("java", "redis");
                assertThat(jedis.zscore("leaderboard", "player1")).isEqualTo(42.0);
            }
        }

        @Test
        @DisplayName("deletions are replayed too, so a deleted key does not come back")
        void deletionsSurviveRestart() throws Exception {
            Path aof = tempDir.resolve("appendonly.aof");

            try (Fixture first = start(aof, FsyncPolicy.ALWAYS);
                 Jedis jedis = new Jedis("127.0.0.1", first.port())) {
                jedis.set("keep", "yes");
                jedis.set("remove", "no");
                jedis.del("remove");
            }

            try (Fixture second = start(aof, FsyncPolicy.ALWAYS);
                 Jedis jedis = new Jedis("127.0.0.1", second.port())) {
                assertThat(jedis.get("keep")).isEqualTo("yes");
                assertThat(jedis.exists("remove")).isFalse();
            }
        }

        @Test
        @DisplayName("a failed conditional write is not recorded, so replay does not invent a key")
        void failedConditionalWritesAreNotPersisted() throws Exception {
            Path aof = tempDir.resolve("appendonly.aof");

            try (Fixture first = start(aof, FsyncPolicy.ALWAYS);
                 Jedis jedis = new Jedis("127.0.0.1", first.port())) {
                jedis.set("k", "original");
                // Fails because the key exists, and changes nothing.
                assertThat(jedis.setnx("k", "replacement")).isEqualTo(0);
                jedis.del("k");
            }

            // If the failed SETNX had been appended, replaying SET, SETNX, DEL into an
            // empty keyspace would leave "k" alive with the value "replacement".
            try (Fixture second = start(aof, FsyncPolicy.ALWAYS);
                 Jedis jedis = new Jedis("127.0.0.1", second.port())) {
                assertThat(jedis.exists("k")).isFalse();
                assertThat(jedis.dbSize()).isZero();
            }
        }

        @Test
        void expiryTimesAreRestored() throws Exception {
            Path aof = tempDir.resolve("appendonly.aof");

            try (Fixture first = start(aof, FsyncPolicy.ALWAYS);
                 Jedis jedis = new Jedis("127.0.0.1", first.port())) {
                jedis.setex("session", 3600, "token");
            }

            try (Fixture second = start(aof, FsyncPolicy.ALWAYS);
                 Jedis jedis = new Jedis("127.0.0.1", second.port())) {
                assertThat(jedis.ttl("session")).isBetween(3500L, 3600L);
            }
        }

        @Test
        void anAbsentFileIsAFirstStartNotAnError() throws Exception {
            Path missing = tempDir.resolve("does-not-exist.aof");

            AofLoader.Result result =
                    AofLoader.load(missing, new Database(), CommandRegistry.standard());

            assertThat(result.commandsReplayed()).isZero();
            assertThat(result.truncated()).isFalse();
        }
    }

    @Nested
    @DisplayName("crash recovery")
    class CrashRecovery {

        @Test
        @DisplayName("a record cut off mid-write is discarded and the rest is kept")
        void truncatedTrailingRecordIsDiscarded() throws Exception {
            Path aof = tempDir.resolve("appendonly.aof");

            try (Fixture first = start(aof, FsyncPolicy.ALWAYS);
                 Jedis jedis = new Jedis("127.0.0.1", first.port())) {
                jedis.set("a", "1");
                jedis.set("b", "2");
                jedis.set("c", "3");
            }

            // Simulate the process dying part-way through writing a fourth command.
            byte[] partial = "*3\r\n$3\r\nSET\r\n$1\r\nd\r\n$1\r".getBytes(StandardCharsets.UTF_8);
            Files.write(aof, partial, StandardOpenOption.APPEND);
            long sizeWithFragment = Files.size(aof);

            Database database = new Database();
            AofLoader.Result result =
                    AofLoader.load(aof, database, CommandRegistry.standard());

            assertThat(result.truncated()).isTrue();
            assertThat(result.commandsReplayed()).isEqualTo(3);
            assertThat(database.getString("a")).isPresent();
            assertThat(database.getString("c")).isPresent();
            assertThat(database.exists("d")).as("the incomplete command was not applied").isFalse();

            // The fragment must also be removed, or the next append would be parsed as a
            // continuation of it and corrupt everything after.
            assertThat(Files.size(aof)).isLessThan(sizeWithFragment);
            assertThat(Files.size(aof)).isEqualTo(result.bytesRead());
        }

        @Test
        @DisplayName("after recovery the file can be appended to and reloaded cleanly")
        void serverKeepsWorkingAfterRecoveringFromATruncatedFile() throws Exception {
            Path aof = tempDir.resolve("appendonly.aof");

            try (Fixture first = start(aof, FsyncPolicy.ALWAYS);
                 Jedis jedis = new Jedis("127.0.0.1", first.port())) {
                jedis.set("before", "crash");
            }

            Files.write(aof, "*2\r\n$3\r\nGET".getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);

            try (Fixture second = start(aof, FsyncPolicy.ALWAYS);
                 Jedis jedis = new Jedis("127.0.0.1", second.port())) {
                assertThat(jedis.get("before")).isEqualTo("crash");
                jedis.set("after", "recovery");
            }

            try (Fixture third = start(aof, FsyncPolicy.ALWAYS);
                 Jedis jedis = new Jedis("127.0.0.1", third.port())) {
                assertThat(jedis.get("before")).isEqualTo("crash");
                assertThat(jedis.get("after")).isEqualTo("recovery");
            }
        }

        @Test
        @DisplayName("garbage in the middle stops replay but keeps everything before it")
        void corruptRecordStopsReplayWithoutLosingEarlierData() throws Exception {
            Path aof = tempDir.resolve("appendonly.aof");

            try (Fixture first = start(aof, FsyncPolicy.ALWAYS);
                 Jedis jedis = new Jedis("127.0.0.1", first.port())) {
                jedis.set("good", "value");
            }

            Files.write(aof, "%%%not resp at all%%%\r\n".getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);

            Database database = new Database();
            AofLoader.Result result = AofLoader.load(aof, database, CommandRegistry.standard());

            assertThat(result.truncated()).isTrue();
            assertThat(database.getString("good")).isPresent();
        }
    }

    @Nested
    @DisplayName("fsync policies")
    class Policies {

        @Test
        void everyPolicyProducesAReplayableFile() throws Exception {
            for (FsyncPolicy policy : List.of(
                    FsyncPolicy.ALWAYS, FsyncPolicy.EVERYSEC, FsyncPolicy.NO)) {

                Path aof = tempDir.resolve("policy-" + policy + ".aof");

                try (Fixture first = start(aof, policy);
                     Jedis jedis = new Jedis("127.0.0.1", first.port())) {
                    jedis.set("k", policy.name());
                }

                Database database = new Database();
                AofLoader.load(aof, database, CommandRegistry.standard());

                assertThat(database.getString("k").orElseThrow().asString())
                        .as("policy %s", policy)
                        .isEqualTo(policy.name());
            }
        }

        @Test
        @DisplayName("ALWAYS forces a sync per write; NO does not sync at all")
        void policiesDifferInHowOftenTheyForceTheDisk() throws Exception {
            Path always = tempDir.resolve("always.aof");
            try (AofWriter writer = new AofWriter(always, FsyncPolicy.ALWAYS)) {
                for (int i = 0; i < 10; i++) {
                    writer.append(command("SET", "k" + i, "v"));
                }
                assertThat(writer.syncs()).isEqualTo(10);
                assertThat(writer.commandsAppended()).isEqualTo(10);
            }

            Path none = tempDir.resolve("none.aof");
            AofWriter writer = new AofWriter(none, FsyncPolicy.NO);
            for (int i = 0; i < 10; i++) {
                writer.append(command("SET", "k" + i, "v"));
            }
            assertThat(writer.syncs()).as("no explicit syncs before close").isZero();
            writer.close();
        }

        private List<com.preetham.respserver.protocol.RespValue.BulkString> command(String... parts) {
            return List.of(parts).stream()
                    .map(part -> new com.preetham.respserver.protocol.RespValue.BulkString(
                            part.getBytes(StandardCharsets.UTF_8)))
                    .toList();
        }
    }
}
