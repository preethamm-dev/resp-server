package com.preetham.respserver.store;

/**
 * Glob matching for {@code KEYS}, using Redis's own pattern syntax.
 *
 * <pre>
 *   *        any sequence of characters, including none
 *   ?        exactly one character
 *   [abc]    one character from the set
 *   [^abc]   one character not in the set
 *   [a-c]    one character from the range
 *   \x       a literal x
 * </pre>
 *
 * <h2>Why not a regex</h2>
 *
 * Translating a glob into {@link java.util.regex.Pattern} means escaping the user's
 * input correctly, and a mistake there turns {@code KEYS} into a denial-of-service:
 * Java's regex engine backtracks, so a crafted pattern can take exponential time. A
 * direct matcher has no such failure mode.
 *
 * <h2>Why iterative and not recursive</h2>
 *
 * The obvious implementation recurses on every {@code *}. Against a hostile pattern
 * such as {@code "*a*a*a*a*a*"} that is exponential and also risks a stack overflow.
 * This version uses the standard backtracking trick instead: remember where the last
 * {@code *} was and how far the input had been consumed, and on a mismatch resume
 * from there having let the star swallow one more character. That is O(n*m) worst
 * case with constant stack.
 */
public final class GlobMatcher {

    private GlobMatcher() {
    }

    public static boolean matches(String pattern, String input) {
        int p = 0;
        int s = 0;
        int starPattern = -1;
        int starInput = 0;

        while (s < input.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '\\' && p + 1 < pattern.length()) {
                // Escaped literal: \* matches a literal asterisk.
                if (pattern.charAt(p + 1) == input.charAt(s)) {
                    p += 2;
                    s++;
                    continue;
                }
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                // Record the backtrack point and try matching zero characters first.
                starPattern = p;
                starInput = s;
                p++;
                continue;
            } else if (p < pattern.length() && pattern.charAt(p) == '?') {
                p++;
                s++;
                continue;
            } else if (p < pattern.length() && pattern.charAt(p) == '[') {
                int close = findClosingBracket(pattern, p);
                if (close < 0) {
                    // Unterminated class, e.g. the pattern "a[bc". There is no sensible
                    // set to match against, so the '[' degrades to a literal rather
                    // than making the whole pattern silently match nothing.
                    if (input.charAt(s) == '[') {
                        p++;
                        s++;
                        continue;
                    }
                } else if (matchesClass(pattern, p + 1, close, input.charAt(s))) {
                    p = close + 1;
                    s++;
                    continue;
                }
            } else if (p < pattern.length() && pattern.charAt(p) == input.charAt(s)) {
                p++;
                s++;
                continue;
            }

            // Mismatch. If a star is available, let it consume one more character.
            if (starPattern >= 0) {
                p = starPattern + 1;
                starInput++;
                s = starInput;
                continue;
            }
            return false;
        }

        // Input exhausted: any pattern remainder must be stars.
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }

    /** Index of the {@code ]} closing a class starting at {@code open}, or -1. */
    private static int findClosingBracket(String pattern, int open) {
        for (int i = open + 1; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == ']' && i > open + 1) {
                return i;
            }
        }
        return -1;
    }

    /** Whether {@code c} satisfies the class body between {@code from} and {@code toExclusive}. */
    private static boolean matchesClass(String pattern, int from, int toExclusive, char c) {
        boolean negated = false;
        int i = from;
        if (i < toExclusive && (pattern.charAt(i) == '^')) {
            negated = true;
            i++;
        }

        boolean found = false;
        while (i < toExclusive) {
            char current = pattern.charAt(i);
            if (current == '\\' && i + 1 < toExclusive) {
                i++;
                if (pattern.charAt(i) == c) {
                    found = true;
                }
                i++;
            } else if (i + 2 < toExclusive && pattern.charAt(i + 1) == '-') {
                char low = current;
                char high = pattern.charAt(i + 2);
                if (c >= Math.min(low, high) && c <= Math.max(low, high)) {
                    found = true;
                }
                i += 3;
            } else {
                if (current == c) {
                    found = true;
                }
                i++;
            }
        }
        return negated != found;
    }
}
