package com.preetham.respserver.protocol;

/**
 * Thrown when incoming bytes cannot be a valid RESP frame no matter how many
 * more bytes arrive -- a bad type byte, a malformed length, a missing CRLF
 * terminator, or a length beyond the configured limit.
 *
 * <p>This is deliberately distinct from "not enough bytes yet", which is not an
 * error and is signalled by {@link RespReader#tryParse} returning empty. Conflating
 * the two is the classic bug in hand-written protocol parsers: a command split
 * across two TCP reads gets rejected as malformed.
 */
public class ProtocolException extends Exception {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
