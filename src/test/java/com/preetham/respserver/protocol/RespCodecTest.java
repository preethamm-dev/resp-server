package com.preetham.respserver.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Encoding and decoding of every RESP2 type.
 *
 * <p>The round-trip test is the one that carries weight: encode a value, parse the
 * bytes back, and require an equal value. It catches mismatches between the two halves
 * of the codec that testing either half alone would miss -- for example a writer that
 * emits a length in characters while the reader counts bytes, which only shows up on
 * non-ASCII input.
 */
class RespCodecTest {

    private final RespReader reader = RespReader.strict();

    private static ByteBuffer bytes(String wire) {
        return ByteBuffer.wrap(wire.getBytes(StandardCharsets.UTF_8));
    }

    private RespValue parseFully(String wire) throws ProtocolException {
        ByteBuffer buf = bytes(wire);
        Optional<RespValue> parsed = reader.tryParse(buf);
        assertThat(parsed).as("expected a complete frame in %s", wire).isPresent();
        assertThat(buf.hasRemaining()).as("expected the whole buffer to be consumed").isFalse();
        return parsed.get();
    }

    @Nested
    @DisplayName("decoding")
    class Decoding {

        @Test
        void parsesSimpleString() throws Exception {
            assertThat(parseFully("+OK\r\n")).isEqualTo(RespValue.simple("OK"));
        }

        @Test
        void parsesError() throws Exception {
            assertThat(parseFully("-ERR unknown command\r\n"))
                    .isEqualTo(RespValue.error("ERR unknown command"));
        }

        @Test
        void parsesPositiveAndNegativeIntegers() throws Exception {
            assertThat(parseFully(":1000\r\n")).isEqualTo(RespValue.integer(1000));
            assertThat(parseFully(":-42\r\n")).isEqualTo(RespValue.integer(-42));
            assertThat(parseFully(":0\r\n")).isEqualTo(RespValue.integer(0));
        }

        @Test
        void parsesBulkString() throws Exception {
            assertThat(parseFully("$5\r\nhello\r\n")).isEqualTo(RespValue.bulk("hello"));
        }

        @Test
        void parsesEmptyBulkString() throws Exception {
            assertThat(parseFully("$0\r\n\r\n")).isEqualTo(RespValue.bulk(""));
        }

        @Test
        @DisplayName("a bulk string is binary safe: CRLF and NUL inside the payload are data")
        void parsesBulkStringContainingProtocolBytes() throws Exception {
            byte[] payload = {'a', '\r', '\n', 0, 'b'};
            ByteBuffer buf = ByteBuffer.allocate(64);
            buf.put("$5\r\n".getBytes(StandardCharsets.US_ASCII));
            buf.put(payload);
            buf.put("\r\n".getBytes(StandardCharsets.US_ASCII));
            buf.flip();

            Optional<RespValue> parsed = reader.tryParse(buf);

            assertThat(parsed).contains(RespValue.bulk(payload));
        }

        @Test
        void parsesNullBulkAndNullArray() throws Exception {
            assertThat(parseFully("$-1\r\n")).isEqualTo(RespValue.Null.BULK);
            assertThat(parseFully("*-1\r\n")).isEqualTo(RespValue.Null.ARRAY);
        }

        @Test
        void parsesArrayOfBulkStrings() throws Exception {
            RespValue parsed = parseFully("*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n");

            assertThat(parsed).isEqualTo(RespValue.array(List.of(
                    RespValue.bulk("SET"), RespValue.bulk("foo"), RespValue.bulk("bar"))));
        }

        @Test
        void parsesEmptyArray() throws Exception {
            assertThat(parseFully("*0\r\n")).isEqualTo(RespValue.array(List.of()));
        }

        @Test
        void parsesNestedArray() throws Exception {
            RespValue parsed = parseFully("*2\r\n*1\r\n:1\r\n*1\r\n:2\r\n");

            assertThat(parsed).isEqualTo(RespValue.array(List.of(
                    RespValue.array(List.of(RespValue.integer(1))),
                    RespValue.array(List.of(RespValue.integer(2))))));
        }

        @Test
        void parsesUtf8PayloadByByteLengthNotCharacterCount() throws Exception {
            // "héllo" is 5 characters but 6 bytes in UTF-8. A reader that counted
            // characters would desynchronise the stream here.
            byte[] utf8 = "héllo".getBytes(StandardCharsets.UTF_8);
            assertThat(utf8).hasSize(6);

            assertThat(parseFully("$6\r\nhéllo\r\n")).isEqualTo(RespValue.bulk("héllo"));
        }
    }

    @Nested
    @DisplayName("malformed input is rejected, not silently accepted")
    class Malformed {

        @Test
        void rejectsUnknownTypeByte() {
            assertThatThrownBy(() -> RespReader.strict().tryParse(bytes("%2\r\n")))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("unexpected byte");
        }

        @Test
        void rejectsNonNumericLength() {
            assertThatThrownBy(() -> reader.tryParse(bytes("$abc\r\nhello\r\n")))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("invalid number");
        }

        @Test
        void rejectsNegativeBulkLengthOtherThanMinusOne() {
            assertThatThrownBy(() -> reader.tryParse(bytes("$-7\r\n")))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("invalid bulk length");
        }

        @Test
        void rejectsBulkStringNotTerminatedByCrlf() {
            assertThatThrownBy(() -> reader.tryParse(bytes("$5\r\nhelloXX")))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("not terminated by CRLF");
        }

        @Test
        @DisplayName("a huge declared length is refused rather than allocated")
        void rejectsBulkLengthBeyondLimit() {
            RespReader limited = RespReader.withLimits(1024, 1024, false);

            assertThatThrownBy(() -> limited.tryParse(bytes("$999999999\r\n")))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("exceeds limit");
        }

        @Test
        @DisplayName("a huge declared array size is refused rather than allocated")
        void rejectsArraySizeBeyondLimit() {
            RespReader limited = RespReader.withLimits(1024, 8, false);

            assertThatThrownBy(() -> limited.tryParse(bytes("*100000\r\n")))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("exceeds limit");
        }

        @Test
        @DisplayName("an endless line with no CRLF is cut off instead of buffered forever")
        void rejectsOverlongLine() {
            String flood = "+" + "x".repeat(RespReader.MAX_LINE_LENGTH + 10);

            assertThatThrownBy(() -> reader.tryParse(bytes(flood)))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("without CRLF");
        }
    }

    @Nested
    @DisplayName("encoding")
    class Encoding {

        @Test
        void encodesEachType() {
            assertThat(RespWriter.encode(RespValue.simple("OK"))).asString().isEqualTo("+OK\r\n");
            assertThat(RespWriter.encode(RespValue.error("ERR bad"))).asString()
                    .isEqualTo("-ERR bad\r\n");
            assertThat(RespWriter.encode(RespValue.integer(-3))).asString().isEqualTo(":-3\r\n");
            assertThat(RespWriter.encode(RespValue.bulk("hi"))).asString().isEqualTo("$2\r\nhi\r\n");
            assertThat(RespWriter.encode(RespValue.Null.BULK)).asString().isEqualTo("$-1\r\n");
            assertThat(RespWriter.encode(RespValue.Null.ARRAY)).asString().isEqualTo("*-1\r\n");
            assertThat(RespWriter.encode(RespValue.array(List.of(RespValue.integer(1)))))
                    .asString().isEqualTo("*1\r\n:1\r\n");
        }

        @Test
        @DisplayName("a CRLF inside a simple string would desynchronise the client, so it is refused")
        void refusesNewlineInSimpleString() {
            assertThatThrownBy(() -> RespWriter.encode(RespValue.simple("a\r\nb")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bulk string instead");

            assertThatThrownBy(() -> RespWriter.encode(RespValue.error("bad\nthing")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        static List<RespValue> values() {
            return List.of(
                    RespValue.simple("OK"),
                    RespValue.simple(""),
                    RespValue.error("ERR something went wrong"),
                    RespValue.integer(0),
                    RespValue.integer(Long.MAX_VALUE),
                    RespValue.integer(Long.MIN_VALUE),
                    RespValue.bulk(""),
                    RespValue.bulk("hello"),
                    RespValue.bulk("héllo — unicode"),
                    RespValue.bulk(new byte[]{0, 1, 2, '\r', '\n', (byte) 0xFF}),
                    RespValue.Null.BULK,
                    RespValue.Null.ARRAY,
                    RespValue.array(List.of()),
                    RespValue.array(List.of(RespValue.bulk("a"), RespValue.integer(2))),
                    RespValue.array(List.of(
                            RespValue.array(List.of(RespValue.bulk("nested"))),
                            RespValue.Null.BULK)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("values")
        void encodeThenParseYieldsTheSameValue(RespValue original) throws Exception {
            byte[] encoded = RespWriter.encode(original);
            ByteBuffer buf = ByteBuffer.wrap(encoded);

            Optional<RespValue> parsed = RespReader.strict().tryParse(buf);

            assertThat(parsed).contains(original);
            assertThat(buf.hasRemaining()).as("whole frame consumed").isFalse();
        }
    }
}
