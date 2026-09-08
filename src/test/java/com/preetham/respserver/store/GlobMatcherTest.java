package com.preetham.respserver.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Glob matching for {@code KEYS}, including the pathological patterns. */
class GlobMatcherTest {

    @ParameterizedTest(name = "{0} matches {1} -> {2}")
    @CsvSource({
            // literals
            "foo,          foo,        true",
            "foo,          bar,        false",
            "'',           '',         true",
            "'',           x,          false",

            // star
            "*,            anything,   true",
            "*,            '',         true",
            "user:*,       user:1,     true",
            "user:*,       user:,      true",
            "user:*,       admin:1,    false",
            "*:1,          user:1,     true",
            "*sion*,       expression, true",
            "a*b*c,        axxbyyc,    true",
            "a*b*c,        axxbyy,     false",
            "**,           abc,        true",

            // question mark
            "h?llo,        hello,      true",
            "h?llo,        hallo,      true",
            "h?llo,        hllo,       false",
            "h?llo,        heello,     false",

            // character classes
            "h[ae]llo,     hello,      true",
            "h[ae]llo,     hallo,      true",
            "h[ae]llo,     hillo,      false",
            "h[^e]llo,     hallo,      true",
            "h[^e]llo,     hello,      false",
            "h[a-c]llo,    hbllo,      true",
            "h[a-c]llo,    hdllo,      false",
            "key[0-9],     key7,       true",
            "key[0-9],     keyx,       false",
    })
    void matchesRedisGlobSyntax(String pattern, String input, boolean expected) {
        assertThat(GlobMatcher.matches(pattern, input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a backslash escapes a metacharacter into a literal")
    void supportsEscaping() {
        assertThat(GlobMatcher.matches("a\\*b", "a*b")).isTrue();
        assertThat(GlobMatcher.matches("a\\*b", "axxb")).isFalse();
        assertThat(GlobMatcher.matches("a\\?b", "a?b")).isTrue();
    }

    @Test
    @DisplayName("a pathological pattern stays fast -- no exponential backtracking")
    void nestedStarsDoNotBlowUp() {
        // A recursive matcher, or a naive translation to java.util.regex, takes
        // effectively forever on this input. The iterative backtracking version is
        // O(n*m). Asserting a time bound is what stops a future "simplification"
        // into a regex from quietly reintroducing a denial-of-service.
        String pattern = "*a*a*a*a*a*a*a*a*a*a*b";
        String input = "a".repeat(2000);

        org.assertj.core.api.Assertions
                .assertThatCode(() -> assertThat(GlobMatcher.matches(pattern, input)).isFalse())
                .doesNotThrowAnyException();
    }

    @Test
    void pathologicalPatternCompletesWellUnderASecond() {
        String pattern = "*a*a*a*a*a*a*a*a*a*a*b";
        String input = "a".repeat(5000);

        long startNanos = System.nanoTime();
        boolean result = GlobMatcher.matches(pattern, input);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        assertThat(result).isFalse();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void unterminatedClassIsTreatedAsALiteralBracket() {
        assertThat(GlobMatcher.matches("a[bc", "a[bc")).isTrue();
    }
}
