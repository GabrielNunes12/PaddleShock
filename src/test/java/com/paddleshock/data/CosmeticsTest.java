package com.paddleshock.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

import com.paddleshock.GameConstants;
import com.paddleshock.app.MatchRewards;
import com.paddleshock.entities.TrailBuffer;

/** Cosmetics, loss rewards and the trail buffer - see docs/specs/06-cosmetics-and-loss-rewards.md. */
class CosmeticsTest {

    private static final List<String> CATEGORIES = List.of("skin", "trail", "celebration");

    @Test
    void everyCategoryStartsWithAFreeNoneAndHasValidItems() {
        Set<String> ids = new HashSet<>();
        for (String category : CATEGORIES) {
            List<CosmeticDefinition> items = Catalog.cosmeticsFor(category);
            assertTrue(items.get(0).isNone(), category);
            assertEquals(0, items.get(0).getPrice(), category);
            for (CosmeticDefinition item : items) {
                assertTrue(ids.add(item.getId()), "duplicate " + item.getId());
                assertEquals(category, item.getCategory(), item.getId());
                assertTrue(item.isNone() || item.getPrice() > 0, item.getId());
            }
        }
    }

    @Test
    void newProfileOwnsAndEquipsEachNoneDefault() {
        PlayerProfile profile = new PlayerProfile();
        for (String category : CATEGORIES) {
            String none = Catalog.cosmeticsFor(category).get(0).getId();
            assertTrue(profile.owns(category, none), category);
            assertEquals(none, profile.getEquippedId(category), category);
        }
    }

    @Test
    void anUnownedCosmeticCannotBeEquipped() {
        PlayerProfile profile = new PlayerProfile();
        profile.equip("skin", "skin_gold");
        assertEquals("skin_none", profile.getEquippedId("skin"));
        assertFalse(profile.owns("skin", "skin_gold"));
    }

    @Test
    void purchaseDeductsAndUnlocksEquip() {
        PlayerProfile profile = new PlayerProfile();
        profile.addCurrency(1000);
        CosmeticDefinition frost = Catalog.findCosmetic("trail", "trail_frost").orElseThrow();
        int before = profile.getCurrency();
        assertTrue(profile.purchase("trail", frost.getId(), frost.getPrice()));
        assertEquals(before - frost.getPrice(), profile.getCurrency());
        profile.equip("trail", frost.getId());
        assertEquals(frost.getId(), profile.getEquippedId("trail"));
    }

    @Test
    void oldSavesGetTheNoneDefaults() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        PlayerProfile old = gson.fromJson("{\"ownedSkinIds\":null,\"equippedSkinId\":null,\"ownedTrailIds\":null,"
                + "\"equippedTrailId\":null,\"ownedCelebrationIds\":null,\"equippedCelebrationId\":null}", PlayerProfile.class);
        for (String category : CATEGORIES) {
            String none = Catalog.cosmeticsFor(category).get(0).getId();
            assertTrue(old.owns(category, none), category);
            assertEquals(none, old.getEquippedId(category), category);
        }
        old.addCurrency(1000);
        assertTrue(old.purchase("celebration", "celebration_sparks", 200), "an old save can still buy cosmetics");
    }

    @Test
    void aLossPaysTheConsolationAndAWinPaysTheWinRange() {
        RandomGenerator random = new java.util.Random(7);
        assertEquals(GameConstants.LOSS_REWARD, MatchRewards.forResult(false, random));
        for (int i = 0; i < 200; i++) {
            int win = MatchRewards.forResult(true, random);
            assertTrue(win >= GameConstants.MATCH_REWARD_MIN && win <= GameConstants.MATCH_REWARD_MAX, "win " + win);
        }
        assertTrue(GameConstants.LOSS_REWARD > 0 && GameConstants.LOSS_REWARD < GameConstants.MATCH_REWARD_MIN,
                "a loss pays something, but always less than a win");
    }

    @Test
    void trailKeepsTheLastSamplesOldestFaintest() {
        TrailBuffer buffer = new TrailBuffer(3);
        for (int i = 1; i <= 5; i++) {
            buffer.push(i, 0f, 0f);
        }
        assertEquals(3, buffer.size());
        assertEquals(3f, buffer.get(0)[0], "oldest kept");
        assertEquals(5f, buffer.get(2)[0], "newest");
        assertTrue(buffer.alpha(0, 1f) < buffer.alpha(2, 1f));
        assertEquals(1f, buffer.alpha(2, 1f), 1e-6f);
        buffer.clear();
        assertEquals(0, buffer.size());
    }
}
