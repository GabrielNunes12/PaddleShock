package com.paddleshock.challenges;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.paddleshock.achievements.MatchOutcome;
import com.paddleshock.data.PlayerProfile;

/**
 * Advances the current challenges from a finished match and pays out any that complete, exactly
 * once. Works purely on {@link PlayerProfile}; the date is a parameter so tests control it.
 */
public final class ChallengeTracker {

    private ChallengeTracker() {
    }

    /** Records {@code outcome} against the challenges current on {@code date}; returns the ones
     *  it completed (their reward has already been added to the profile's credits). */
    public static List<Challenge> recordMatch(PlayerProfile profile, MatchOutcome outcome, LocalDate date) {
        if (outcome.kind() == MatchOutcome.Kind.LOCAL_VERSUS) {
            return List.of(); // farmable by one person playing both sides - see AchievementTracker
        }
        List<Challenge> current = ChallengeGenerator.current(date);
        Set<String> currentIds = current.stream().map(Challenge::id).collect(Collectors.toSet());
        profile.pruneChallenges(currentIds);

        List<Challenge> completed = new ArrayList<>();
        for (Challenge challenge : current) {
            int gained = challenge.kind().progressFrom(outcome, challenge.levelId());
            if (gained <= 0) {
                continue;
            }
            int progress = Math.min(challenge.target(), profile.getChallengeProgress(challenge.id()) + gained);
            profile.setChallengeProgress(challenge.id(), progress);
            if (progress >= challenge.target() && profile.claimChallenge(challenge.id())) {
                profile.addCurrency(challenge.reward());
                completed.add(challenge);
            }
        }
        return completed;
    }

    public static int progress(PlayerProfile profile, Challenge challenge) {
        return Math.min(challenge.target(), profile.getChallengeProgress(challenge.id()));
    }

    public static boolean isComplete(PlayerProfile profile, Challenge challenge) {
        return profile.isChallengeClaimed(challenge.id());
    }
}
