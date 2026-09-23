package com.paddleshock.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.ColorRGBA;

import com.paddleshock.GameConstants;
import com.paddleshock.data.Catalog;
import com.paddleshock.data.PowerUpDefinition;
import com.paddleshock.entities.Ball;
import com.paddleshock.entities.BallModel;
import com.paddleshock.entities.Paddle;
import com.paddleshock.entities.PaddleModel;
import com.paddleshock.entities.Table;
import com.paddleshock.entities.TextureSet;
import com.paddleshock.powerups.PowerUpType;

/** Curveball / Shield / Ghost Ball - see docs/specs/04-behavior-power-ups.md. */
class BehaviorPowerUpTest {

    private static final float TICK = 1f / 60f;
    private static AssetManager assetManager;

    private Ball ball;
    private Paddle playerPaddle;
    private Paddle opponentPaddle;
    private MatchSimulation sim;

    @BeforeAll
    static void setUpAssetManager() {
        assetManager = new DesktopAssetManager(true);
    }

    @BeforeEach
    void setUp() {
        ball = new Ball(assetManager, ColorRGBA.White, TextureSet.RUBBER, BallModel.NONE, 1f, 1f, 1f, 1f, 0f);
        playerPaddle = new Paddle(assetManager, ColorRGBA.White, TextureSet.PLASTIC, PaddleModel.CLASSIC,
                GameConstants.PADDLE_PLAYER_Z, 1f, 1f);
        opponentPaddle = new Paddle(assetManager, ColorRGBA.White, TextureSet.PLASTIC, PaddleModel.CLASSIC,
                GameConstants.PADDLE_OPPONENT_Z, 1f, 1f);
        Table table = new Table(assetManager, ColorRGBA.White, TextureSet.MARBLE, 1f);
        sim = new MatchSimulation(ball, playerPaddle, opponentPaddle, table);
        opponentPaddle.setNetworkPosition(500f, GameConstants.PADDLE_OPPONENT_Z);
    }

    private static PowerUpDefinition def(String id) {
        return Catalog.findPowerUp(id).orElseThrow();
    }

    /** Activates a power-up for the player (or AI) with the ball parked safely mid-air, far from everything. */
    private void activate(String id, boolean player) {
        ball.setNetworkState(0f, 50f, 0f, 0f, 0f, 0f);
        PaddleInput input = PaddleInput.none().withPowerUp(def(id));
        TickResult result = sim.tick(0f, player ? input : PaddleInput.none(), player ? PaddleInput.none() : input);
        assertTrue(player ? result.isPlayerPowerUpActivated() : result.isOpponentPowerUpActivated(), id);
    }

    private void ballArrivingAtPlayerPaddle() {
        ball.setNetworkState(0f, ball.getRadius(), GameConstants.PADDLE_PLAYER_Z + 0.75f, 0f, -7f, 0f);
    }

    // ---- Curveball ----

    @Test
    void curveballGivesMaxSpinInTheSwipeDirectionOnceThenIsSpent() {
        activate("powerup_curveball", true);
        ballArrivingAtPlayerPaddle();
        assertTrue(sim.tick(TICK, PaddleInput.move(0.05f, 0f), PaddleInput.none()).isPlayerPaddleHit());
        assertEquals(GameConstants.SPIN_MAX, ball.getSpin(), 1e-6f);
        assertFalse(sim.getPowerUpManager().hasEffect(true, PowerUpType.CURVEBALL), "consumed by the hit");

        playerPaddle.setNetworkPosition(0f, GameConstants.PADDLE_PLAYER_Z);
        ballArrivingAtPlayerPaddle();
        sim.tick(TICK, PaddleInput.move(0.05f, 0f), PaddleInput.none());
        assertEquals(Ball.spinFromPaddleSpeed(0.05f / TICK), ball.getSpin(), 1e-4f, "the next hit is a normal hit");
    }

    @Test
    void stillCurveballHitCurvesAwayFromTheOpponent() {
        assertEquals(-GameConstants.SPIN_MAX, MatchSimulation.curveballSpin(0f, 0f, 3f), 1e-6f);
        assertEquals(GameConstants.SPIN_MAX, MatchSimulation.curveballSpin(0f, 0f, -3f), 1e-6f);
        assertEquals(-GameConstants.SPIN_MAX, MatchSimulation.curveballSpin(-2f, 0f, -3f), 1e-6f,
                "a real swipe wins over the away-from-opponent default");
    }

    @Test
    void curveballOnlyAffectsItsOwnersHits() {
        activate("powerup_curveball", false); // the AI's charge
        ballArrivingAtPlayerPaddle();
        sim.tick(TICK, PaddleInput.none(), PaddleInput.none());
        assertEquals(0f, ball.getSpin(), 1e-6f);
        assertTrue(sim.getPowerUpManager().hasEffect(false, PowerUpType.CURVEBALL), "still charged for the AI");
    }

    // ---- Shield ----

    @Test
    void shieldBlocksOneGoalThenTheNextOneScores() {
        activate("powerup_shield", true);
        ball.setNetworkState(0f, ball.getRadius(), -GameConstants.TABLE_HALF_LENGTH - 0.1f, 0f, -7f, 0f);
        TickResult blocked = sim.tick(0f, PaddleInput.none(), PaddleInput.none());
        assertTrue(blocked.isShieldBlocked());
        assertEquals(TickResult.Scorer.NONE, blocked.getScorer());
        assertEquals(0, sim.getOpponentScore());
        assertEquals(-GameConstants.TABLE_HALF_LENGTH, ball.getPosition().z, 1e-6f);
        assertTrue(ball.getVelocity().z > 0f, "rebounds back up the table");
        assertFalse(sim.getPowerUpManager().hasEffect(true, PowerUpType.SHIELD));

        ball.setNetworkState(0f, ball.getRadius(), -GameConstants.TABLE_HALF_LENGTH - 0.1f, 0f, -7f, 0f);
        TickResult scored = sim.tick(0f, PaddleInput.none(), PaddleInput.none());
        assertFalse(scored.isShieldBlocked());
        assertEquals(1, sim.getOpponentScore());
    }

    @Test
    void shieldOnlyProtectsItsOwnersGoal() {
        activate("powerup_shield", false); // the AI shields its own (far) goal
        ball.setNetworkState(0f, ball.getRadius(), -GameConstants.TABLE_HALF_LENGTH - 0.1f, 0f, -7f, 0f);
        assertEquals(TickResult.Scorer.OPPONENT, sim.tick(0f, PaddleInput.none(), PaddleInput.none()).getScorer());

        ball.setNetworkState(0f, ball.getRadius(), GameConstants.TABLE_HALF_LENGTH + 0.1f, 0f, 7f, 0f);
        TickResult blocked = sim.tick(0f, PaddleInput.none(), PaddleInput.none());
        assertTrue(blocked.isShieldBlocked());
        assertEquals(0, sim.getPlayerScore());
        assertTrue(ball.getVelocity().z < 0f);
    }

    // ---- Ghost Ball ----

    @Test
    void ghostBallHidesTheBallOnlyFromItsTargetAndOnlyMidTable() {
        activate("powerup_ghost_ball", true); // player casts it -> lands on the opponent
        ball.setNetworkState(0f, 1f, 0f, 0f, 0f, 0f);
        assertTrue(sim.isBallHiddenFor(false), "hidden from the target mid-table");
        assertFalse(sim.isBallHiddenFor(true), "never hidden from the caster");

        ball.setNetworkState(0f, 1f, GameConstants.GHOST_ZONE_HALF_DEPTH + 0.1f, 0f, 0f, 0f);
        assertFalse(sim.isBallHiddenFor(false), "visible again near the ends");
    }

    @Test
    void ghostBallWearsOff() {
        activate("powerup_ghost_ball", true);
        ball.setNetworkState(0f, 1f, 0f, 0f, 0f, 0f);
        sim.getPowerUpManager().update(PowerUpType.GHOST_BALL.getDuration() + 0.1f);
        assertFalse(sim.isBallHiddenFor(false));
    }

    // ---- Catalog / wire / strings ----

    @Test
    void originalPowerUpOrdinalsAreUnchangedSoOldPeersStillDecodeThem() {
        assertEquals(0, PowerUpType.PADDLE_GROW.ordinal());
        assertEquals(1, PowerUpType.SPEED_BOOST.ordinal());
        assertEquals(2, PowerUpType.SLOW_OPPONENT.ordinal());
        assertEquals(3, PowerUpType.SHRINK_OPPONENT.ordinal());
    }

    @Test
    void everyPowerUpTypeIsSoldAndDescribedInBothLanguages() throws IOException {
        for (PowerUpType type : PowerUpType.values()) {
            assertTrue(Catalog.POWERUPS.stream().anyMatch(p -> p.getType() == type), type + " not in catalog");
        }
        for (String file : List.of("strings_en.properties", "strings_pt_BR.properties")) {
            Properties strings = new Properties();
            try (InputStream in = BehaviorPowerUpTest.class.getResourceAsStream("/i18n/" + file)) {
                strings.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (PowerUpType type : PowerUpType.values()) {
                assertTrue(strings.containsKey("powerup.desc." + type.name().toLowerCase()), file + " " + type);
            }
        }
    }
}
