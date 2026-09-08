package com.preetham.respserver.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves the parser copes with arbitrary fragmentation.
 *
 * <h2>Why this is the most important test in the project</h2>
 *
 * TCP gives no guarantee about how a message is split across reads. The kernel may
 * deliver {@code SET foo bar} in one read, or in nineteen. A parser that quietly
 * assumes otherwise passes every test written with whole commands and then fails in
 * production under load or across a slow link -- precisely when it is hardest to debug.
 *
 * <p>Feeding one byte at a time is the strongest possible version of that scenario. If
 * the parser survives it, every real fragmentation pattern is a weaker case.
 *
 * <p>These tests also pin down the contract the event-loop server depends on: on an
 * incomplete frame the buffer position must be <em>exactly</em> where it started.
 * Consuming even one byte of a partial frame would corrupt the stream in a way that
 * only appears under fragmentation.
 */
class IncrementalParseTest {

    private final RespReader reader = RespReader.strict();

    private static byte[] wire(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "+OK\r\n",
            ":12345\r\n",
            "-ERR something failed\r\n",
            "$5\r\nhello\r\n",
            "$0\r\n\r\n",
            "$-1\r\n",
            "*0\r\n",
            "*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n",
            "*2\r\n*1\r\n:1\r\n$4\r\nabcd\r\n",
    })
    @DisplayName("returns empty until the very last byte arrives, then the whole value")
    void parsesCorrectlyWhenFedOneByteAtATime(String frame) throws Exception {
        byte[] bytes = wire(frame);
        RespValue expected = reader.tryParse(ByteBuffer.wrap(bytes)).orElseThrow();

        ReadBuffer buffer = new ReadBuffer();

        for (int i = 0; i < bytes.length - 1; i++) {
            buffer.append(bytes, i, 1);
            ByteBuffer parsing = buffer.forParsing();

            Optional<RespValue> parsed = reader.tryParse(parsing);

            assertThat(parsed)
                    .as("after %d of %d bytes the frame is still incomplete", i + 1, bytes.length)
                    .isEmpty();
            assertThat(parsing.position())
                    .as("an incomplete parse must consume nothing")
                    .isZero();
        }

        buffer.append(bytes, bytes.length - 1, 1);
        ByteBuffer parsing = buffer.forParsing();

        assertThat(reader.tryParse(parsing))
                .as("the final byte completes the frame")
                .contains(expected);
        assertThat(parsing.position()).isEqualTo(bytes.length);
    }

    @Test
    @DisplayName("a command split across two reads is reassembled")
    void handlesSplitAcrossTwoReads() throws Exception {
        byte[] bytes = wire("*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n");

        for (int split = 1; split < bytes.length; split++) {
            ReadBuffer buffer = new ReadBuffer();
            buffer.append(bytes, 0, split);

            ByteBuffer first = buffer.forParsing();
            assertThat(reader.tryParse(first))
                    .as("split at %d: incomplete on the first read", split)
                    .isEmpty();
            buffer.consume(first.position());

            buffer.append(bytes, split, bytes.length - split);
            ByteBuffer second = buffer.forParsing();

            assertThat(reader.tryParse(second))
                    .as("split at %d: complete after the second read", split)
                    .contains(RespValue.array(List.of(
                            RespValue.bulk("SET"), RespValue.bulk("foo"), RespValue.bulk("bar"))));
        }
    }

    @Test
    @DisplayName("several commands in one read are all drained -- this is pipelining")
    void drainsEveryCompleteCommandFromASingleRead() throws Exception {
        String batch = "*1\r\n$4\r\nPING\r\n".repeat(100);
        ReadBuffer buffer = new ReadBuffer();
        byte[] bytes = wire(batch);
        buffer.append(bytes, 0, bytes.length);

        ByteBuffer parsing = buffer.forParsing();
        List<RespValue> drained = new ArrayList<>();
        while (true) {
            Optional<RespValue> parsed = reader.tryParse(parsing);
            if (parsed.isEmpty()) {
                break;
            }
            drained.add(parsed.get());
        }
        buffer.consume(parsing.position());

        assertThat(drained).hasSize(100);
        assertThat(drained).allSatisfy(v ->
                assertThat(v).isEqualTo(RespValue.array(List.of(RespValue.bulk("PING")))));
        assertThat(buffer.isEmpty()).as("everything consumed").isTrue();
    }

    @Test
    @DisplayName("a trailing partial command survives to the next read")
    void keepsPartialTailAfterDrainingCompleteCommands() throws Exception {
        byte[] bytes = wire("*1\r\n$4\r\nPING\r\n*1\r\n$4\r\nPI");
        ReadBuffer buffer = new ReadBuffer();
        buffer.append(bytes, 0, bytes.length);

        ByteBuffer parsing = buffer.forParsing();
        assertThat(reader.tryParse(parsing)).isPresent();
        assertThat(reader.tryParse(parsing)).as("second command is truncated").isEmpty();
        buffer.consume(parsing.position());

        // The retained tail is "*1\r\n$4\r\nPI": 4 + 4 + 2 = 10 bytes.
        assertThat(buffer.length()).as("the partial second command is retained").isEqualTo(10);

        byte[] rest = wire("NG\r\n");
        buffer.append(rest, 0, rest.length);
        ByteBuffer resumed = buffer.forParsing();

        assertThat(reader.tryParse(resumed))
                .contains(RespValue.array(List.of(RespValue.bulk("PING"))));
    }

    @Test
    @DisplayName("inline commands are accepted from a raw socket and look like arrays")
    void parsesInlineCommands() throws Exception {
        RespReader serverReader = RespReader.forServer();

        Optional<RespValue> parsed =
                serverReader.tryParse(ByteBuffer.wrap(wire("SET foo bar\r\n")));

        assertThat(parsed).contains(RespValue.array(List.of(
                RespValue.bulk("SET"), RespValue.bulk("foo"), RespValue.bulk("bar"))));
    }

    @Test
    void inlineCommandIsAlsoIncremental() throws Exception {
        RespReader serverReader = RespReader.forServer();
        byte[] bytes = wire("PING\r\n");
        ReadBuffer buffer = new ReadBuffer();

        for (int i = 0; i < bytes.length - 1; i++) {
            buffer.append(bytes, i, 1);
            assertThat(serverReader.tryParse(buffer.forParsing())).isEmpty();
        }
        buffer.append(bytes, bytes.length - 1, 1);

        assertThat(serverReader.tryParse(buffer.forParsing()))
                .contains(RespValue.array(List.of(RespValue.bulk("PING"))));
    }

    @Test
    @DisplayName("the read buffer grows, compacts, and refuses to grow past its cap")
    void readBufferManagesCapacity() throws Exception {
        ReadBuffer buffer = new ReadBuffer(1024);
        byte[] chunk = new byte[500];

        buffer.append(chunk, 0, 500);
        buffer.append(chunk, 0, 500);
        assertThat(buffer.length()).isEqualTo(1000);

        buffer.consume(600);
        assertThat(buffer.length()).as("compaction keeps only the tail").isEqualTo(400);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> buffer.append(new byte[2000], 0, 2000))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("exceed");
    }
}
