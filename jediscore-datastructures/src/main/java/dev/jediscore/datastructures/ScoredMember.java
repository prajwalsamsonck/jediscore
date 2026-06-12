package dev.jediscore.datastructures;

/**
 * A sorted-set element: a binary member with its {@code double} score.
 *
 * @param member the member bytes
 * @param score  the score
 */
public record ScoredMember(byte[] member, double score) {
}
