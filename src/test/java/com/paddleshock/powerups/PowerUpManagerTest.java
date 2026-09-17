package com.paddleshock.powerups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.ColorRGBA;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.paddleshock.GameConstants;
import com.paddleshock.entities.Paddle;
import com.paddleshock.entities.PaddleModel;
import com.paddleshock.entities.TextureSet;

/**
 * Exercises {@link PowerUpManager} directly (not through {@link com.paddleshock.sim.MatchSimulation}):
 * cooldown gating is already covered by {@code MatchSimulationTest}, but effect application (which
 * paddle a buff/debuff actually lands on), expiry, and multi-effect stacking are not exercised
 * anywhere else. Uses real (non-rendering) {@link Paddle} instances via a headless
 * {@link DesktopAssetManager}, same pattern as {@code MatchSimulationTest}.
 */
class PowerUpManagerTest {

    private static final float EPS = 1e-4f;

    private static AssetManager assetManager;

    private Paddle playerPaddle;
    private Paddle opponentPaddle;
    private PowerUpManager manager;

    @BeforeAll
    static void setUpAssetManager() {
        assetManager = new DesktopAssetManager(true);
    }

    @BeforeEach
    void setUp() {
        playerPaddle = new Paddle(assetManager, ColorRGBA.White, TextureSet.PLASTIC, PaddleModel.CLASSIC,
                GameConstants.PADDLE_PLAYER_Z, 1f, 1f);
        opponentPaddle = new Paddle(assetManager, ColorRGBA.White, TextureSet.PLASTIC, PaddleModel.CLASSIC,
                GameConstants.PADDLE_OPPONENT_Z, 1f, 1f);
        manager = new PowerUpManager(playerPaddle, opponentPaddle);
    }

    @Test
    void selfBuffLandsOnActivatorsOwnPaddleOnly() {
        manager.activatePlayerPowerUp(PowerUpType.PADDLE_GROW, 8f);
        manager.update(0f);

        float baseRadius = GameConstants.PADDLE_RADIUS;
        assertEquals(baseRadius * PowerUpType.PADDLE_GROW.getModifier().radiusFactor(),
                playerPaddle.getEffectiveRadius(), EPS);
        assertEquals(baseRadius, opponentPaddle.getEffectiveRadius(), EPS,
                "a self-buff must never touch the other paddle");
    }

    @Test
    void debuffLandsOnTheFoesPaddleNotTheActivators() {
        manager.activatePlayerPowerUp(PowerUpType.SLOW_OPPONENT, 8f);
        manager.update(0f);

        playerPaddle.moveDelta(0.01f, 0f);
        opponentPaddle.moveDelta(0.01f, 0f);

        float slowFactor = PowerUpType.SLOW_OPPONENT.getModifier().speedFactor();
        assertEquals(0.01f, playerPaddle.getPosition().x, EPS,
                "the activator's own paddle must move at full speed");
        assertEquals(0.01f * slowFactor, opponentPaddle.getPosition().x, EPS,
                "SLOW_OPPONENT must reduce the opponent's paddle speed, not the caster's");
    }

    @Test
    void effectRevertsOnceItsDurationElapses() {
        manager.activatePlayerPowerUp(PowerUpType.PADDLE_GROW, 8f);
        manager.update(0f);
        assertTrue(playerPaddle.getEffectiveRadius() > GameConstants.PADDLE_RADIUS);

        manager.update(PowerUpType.PADDLE_GROW.getDuration() + 0.01f);

        assertEquals(GameConstants.PADDLE_RADIUS, playerPaddle.getEffectiveRadius(), EPS,
                "buff should fully revert once its duration has elapsed");
    }

    @Test
    void reactivationIsRejectedUntilCooldownElapsesThenAllowed() {
        boolean first = manager.activatePlayerPowerUp(PowerUpType.SPEED_BOOST, 5f);
        assertTrue(first);
        assertFalse(manager.isPlayerReady(PowerUpType.SPEED_BOOST));

        boolean tooSoon = manager.activatePlayerPowerUp(PowerUpType.SPEED_BOOST, 5f);
        assertFalse(tooSoon, "reactivation while on cooldown must be rejected");

        manager.update(5f + 0.01f);
        assertTrue(manager.isPlayerReady(PowerUpType.SPEED_BOOST));

        boolean afterCooldown = manager.activatePlayerPowerUp(PowerUpType.SPEED_BOOST, 5f);
        assertTrue(afterCooldown, "reactivation once the cooldown has fully elapsed must succeed");
    }

    @Test
    void playerAndAiCooldownsAreTrackedIndependently() {
        manager.activatePlayerPowerUp(PowerUpType.SPEED_BOOST, 5f);

        assertFalse(manager.isPlayerReady(PowerUpType.SPEED_BOOST));
        assertTrue(manager.isAiReady(PowerUpType.SPEED_BOOST),
                "the player activating a power-up must never put the AI's own copy on cooldown");
    }

    @Test
    void multipleActiveEffectsOnTheSamePaddleStackMultiplicatively() {
        // Both land on the opponent paddle: SLOW_OPPONENT (speed x0.5) is a debuff cast by the
        // player, SHRINK_OPPONENT (radius x0.5) too - applyCombinedBuffs must multiply them
        // together rather than the last-applied effect silently overwriting the other.
        manager.activatePlayerPowerUp(PowerUpType.SLOW_OPPONENT, 8f);
        manager.activatePlayerPowerUp(PowerUpType.SHRINK_OPPONENT, 8f);
        manager.update(0f);

        opponentPaddle.moveDelta(0.02f, 0f);

        float expectedSpeed = PowerUpType.SLOW_OPPONENT.getModifier().speedFactor();
        float expectedRadius = GameConstants.PADDLE_RADIUS * PowerUpType.SHRINK_OPPONENT.getModifier().radiusFactor();
        assertEquals(0.02f * expectedSpeed, opponentPaddle.getPosition().x, EPS);
        assertEquals(expectedRadius, opponentPaddle.getEffectiveRadius(), EPS);
    }
}
