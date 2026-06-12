package dev.jediscore.datastructures;

/**
 * Glob-style pattern matching compatible with Redis's {@code stringmatchlen}
 * (used by {@code KEYS}, {@code SCAN MATCH}, pub/sub patterns, …).
 *
 * <p>Supports {@code *} (any run), {@code ?} (one byte), {@code [...]} character
 * classes with ranges and {@code ^} negation, and {@code \} escaping. Matching is
 * byte-wise and case-sensitive, as Redis's default is.
 */
public final class Glob {

    private Glob() {
        // Static utility; not instantiable.
    }

    /**
     * Tests whether {@code string} matches {@code pattern}.
     *
     * @param pattern the glob pattern bytes
     * @param string  the subject bytes
     * @return {@code true} if the whole subject matches the pattern
     */
    public static boolean match(byte[] pattern, byte[] string) {
        return matchLen(pattern, 0, pattern.length, string, 0, string.length);
    }

    private static boolean matchLen(byte[] p, int pi, int pend, byte[] s, int si, int send) {
        while (pi < pend && si <= send) {
            switch (p[pi]) {
                case '*' -> {
                    // Collapse consecutive '*'.
                    while (pi + 1 < pend && p[pi + 1] == '*') {
                        pi++;
                    }
                    if (pi + 1 == pend) {
                        return true; // trailing '*' matches the rest
                    }
                    // Try to match the remainder of the pattern at every position.
                    for (int i = si; i <= send; i++) {
                        if (matchLen(p, pi + 1, pend, s, i, send)) {
                            return true;
                        }
                    }
                    return false;
                }
                case '?' -> {
                    if (si == send) {
                        return false;
                    }
                    si++;
                    pi++;
                }
                case '[' -> {
                    if (si == send) {
                        return false;
                    }
                    pi++;
                    boolean negate = pi < pend && p[pi] == '^';
                    if (negate) {
                        pi++;
                    }
                    boolean matched = false;
                    while (pi < pend && p[pi] != ']') {
                        if (p[pi] == '\\' && pi + 1 < pend) {
                            pi++;
                            if (p[pi] == s[si]) {
                                matched = true;
                            }
                        } else if (pi + 2 < pend && p[pi + 1] == '-' && p[pi + 2] != ']') {
                            int lo = p[pi] & 0xff;
                            int hi = p[pi + 2] & 0xff;
                            if (lo > hi) {
                                int t = lo;
                                lo = hi;
                                hi = t;
                            }
                            int c = s[si] & 0xff;
                            if (c >= lo && c <= hi) {
                                matched = true;
                            }
                            pi += 2;
                        } else if (p[pi] == s[si]) {
                            matched = true;
                        }
                        pi++;
                    }
                    if (pi < pend) {
                        pi++; // consume ']'
                    }
                    if (negate) {
                        matched = !matched;
                    }
                    if (!matched) {
                        return false;
                    }
                    si++;
                }
                case '\\' -> {
                    if (pi + 1 < pend) {
                        pi++;
                    }
                    if (si == send || p[pi] != s[si]) {
                        return false;
                    }
                    si++;
                    pi++;
                }
                default -> {
                    if (si == send || p[pi] != s[si]) {
                        return false;
                    }
                    si++;
                    pi++;
                }
            }
        }
        // Any trailing '*' in the pattern can match the empty remainder.
        while (pi < pend && p[pi] == '*') {
            pi++;
        }
        return pi == pend && si == send;
    }
}
