package com.paddleshock.challenges;

/**
 * One generated challenge. {@code id} identifies its period ("D2026-09-23", "W2026-39") and is the
 * key its progress is stored under; {@code levelId} is only set for {@link ChallengeKind#WINS_ON_ARENA}.
 */
public record Challenge(String id, boolean weekly, ChallengeKind kind, String levelId, int target, int reward) {
}
