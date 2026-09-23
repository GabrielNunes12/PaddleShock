package com.paddleshock.sim;

import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.paddleshock.GameConstants;
import com.paddleshock.data.PowerUpDefinition;
import com.paddleshock.entities.Ball;
import com.paddleshock.entities.BallModel;
import com.paddleshock.entities.Paddle;
import com.paddleshock.entities.PaddleModel;
import com.paddleshock.entities.Table;
import com.paddleshock.entities.TextureSet;
import com.paddleshock.powerups.PowerUpType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link MatchSimulation} against real (non-jME-rendering) Ball/Paddle/Table
 * instances. Construction of those still requires an {@link AssetManager} (they load real
 * meshes/materials), so tests use a shared headless {@link DesktopAssetManager} loading the
 * project's actual classpath assets - no mocking, no GL context needed since nothing here
 * renders. Ball/Paddle both expose "setNetworkState"/"setNetworkPosition" (meant for a
 * networked joiner client to apply a host's authoritative snapshot) which doubles nicely as a
 * deterministic test seam for placing entities at exact positions.
 */
class MatchSimulationTest {

    private static final float EPS = 1e-4f;

    private static AssetManager assetManager;

    private Ball ball;
    private Paddle playerPaddle;
    private Paddle opponentPaddle;
    private Table table;
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
        table = new Table(assetManager, ColorRGBA.White, TextureSet.MARBLE, 1f);
        sim = new MatchSimulation(ball, playerPaddle, opponentPaddle, table);
    }

    /** Pushes both paddles far off to the side so they can never accidentally intercept the ball. */
    private void moveAllPaddlesAway() {
        playerPaddle.setNetworkPosition(500f, GameConstants.PADDLE_PLAYER_Z);
        opponentPaddle.setNetworkPosition(500f, GameConstants.PADDLE_OPPONENT_Z);
    }

    @Test
    void tickAdvancesBallPositionByVelocityAndGravity() {
        moveAllPaddlesAway();
        ball.setNetworkState(0f, 0.5f, 0f, 2f, 3f, 0f);

        TickResult result = sim.tick(0.1f, PaddleInput.none(), PaddleInput.none());

        // Gravity: vertVel -= 22*0.1 = 2.2 -> vertVel=-2.2; newY = 0.5 - 0.22 = 0.28, which is
        // below the ball's radius (0.35f), so it bounces off the table surface this same tick.
        float expectedVertVel = 2.2f * 0.80f; // base restitution, multiplier 1
        Vector3f pos = ball.getPosition();
        assertEquals(0.35f, ball.getRadius(), EPS);
        assertEquals(0f + 2f * 0.1f, pos.x, EPS);
        assertEquals(ball.getRadius(), pos.y, EPS);
        assertEquals(0f + 3f * 0.1f, pos.z, EPS);
        assertEquals(expectedVertVel, ball.getVerticalVelocity(), EPS);
        assertFalse(result.isWallBounce());
        assertFalse(result.isAnyPaddleHit());
        assertEquals(TickResult.Scorer.NONE, result.getScorer());
    }

    @Test
    void ballBouncesOffSideRailAndFlagsWallBounce() {
        moveAllPaddlesAway();
        // Just past the right rail: |x| + radius > TABLE_HALF_WIDTH (5).
        ball.setNetworkState(4.9f, ball.getRadius(), 0f, 1f, 0f, 0f);

        TickResult result = sim.tick(0f, PaddleInput.none(), PaddleInput.none());

        assertTrue(result.isWallBounce());
        float maxX = GameConstants.TABLE_HALF_WIDTH - ball.getRadius();
        assertEquals(maxX, ball.getPosition().x, EPS);
        assertTrue(ball.getVelocity().x < 0f, "velocity.x should flip sign after a rail bounce");
    }

    @Test
    void ballHittingPlayerPaddleFlagsPaddleHit() {
        moveAllPaddlesAway();
        // Put the player paddle right where the ball is so it's guaranteed to be hit.
        playerPaddle.setNetworkPosition(0f, GameConstants.PADDLE_PLAYER_Z);
        ball.setNetworkState(0f, GameConstants.PADDLE_HEIGHT, GameConstants.PADDLE_PLAYER_Z, 0f, -1f, 0f);

        TickResult result = sim.tick(0f, PaddleInput.none(), PaddleInput.none());

        assertTrue(result.isPlayerPaddleHit());
        assertFalse(result.isOpponentPaddleHit());
        assertTrue(result.isAnyPaddleHit());
        // bounceOffPaddle always reflects Z away from the incoming direction.
        assertTrue(ball.getVelocity().z > 0f, "ball should reflect off the player's paddle back toward the opponent");
    }

    @Test
    void ballHittingOpponentPaddleFlagsPaddleHit() {
        moveAllPaddlesAway();
        opponentPaddle.setNetworkPosition(0f, GameConstants.PADDLE_OPPONENT_Z);
        ball.setNetworkState(0f, GameConstants.PADDLE_HEIGHT, GameConstants.PADDLE_OPPONENT_Z, 0f, 1f, 0f);

        TickResult result = sim.tick(0f, PaddleInput.none(), PaddleInput.none());

        assertTrue(result.isOpponentPaddleHit());
        assertFalse(result.isPlayerPaddleHit());
    }

    @Test
    void ballPastOpponentBackLineScoresForPlayer() {
        moveAllPaddlesAway();
        ball.setNetworkState(0f, ball.getRadius(), GameConstants.TABLE_HALF_LENGTH + 0.5f, 0f, 1f, 0f);

        TickResult result = sim.tick(0f, PaddleInput.none(), PaddleInput.none());

        assertEquals(TickResult.Scorer.PLAYER, result.getScorer());
        assertEquals(1, sim.getPlayerScore());
        assertEquals(0, sim.getOpponentScore());
        assertFalse(result.isMatchOver());
        // Not match-over yet, so the point re-serves the ball back to the table center.
        assertEquals(0f, ball.getPosition().x, EPS);
        assertEquals(ball.getRadius(), ball.getPosition().y, EPS);
        assertEquals(0f, ball.getPosition().z, EPS);
    }

    @Test
    void ballPastPlayerBackLineScoresForOpponent() {
        moveAllPaddlesAway();
        ball.setNetworkState(0f, ball.getRadius(), -GameConstants.TABLE_HALF_LENGTH - 0.5f, 0f, -1f, 0f);

        TickResult result = sim.tick(0f, PaddleInput.none(), PaddleInput.none());

        assertEquals(TickResult.Scorer.OPPONENT, result.getScorer());
        assertEquals(1, sim.getOpponentScore());
        assertEquals(0, sim.getPlayerScore());
        assertFalse(result.isMatchOver());
    }

    @Test
    void matchEndsWhenPlayerReachesWinScore() {
        moveAllPaddlesAway();

        TickResult lastResult = null;
        for (int point = 1; point <= GameConstants.WIN_SCORE; point++) {
            ball.setNetworkState(0f, ball.getRadius(), GameConstants.TABLE_HALF_LENGTH + 0.5f, 0f, 1f, 0f);
            lastResult = sim.tick(0f, PaddleInput.none(), PaddleInput.none());
            assertEquals(TickResult.Scorer.PLAYER, lastResult.getScorer());
        }

        assertEquals(GameConstants.WIN_SCORE, sim.getPlayerScore());
        assertTrue(lastResult.isMatchOver());
        assertTrue(lastResult.isPlayerWon());
    }

    @Test
    void matchEndsWhenOpponentReachesWinScore() {
        moveAllPaddlesAway();

        TickResult lastResult = null;
        for (int point = 1; point <= GameConstants.WIN_SCORE; point++) {
            ball.setNetworkState(0f, ball.getRadius(), -GameConstants.TABLE_HALF_LENGTH - 0.5f, 0f, -1f, 0f);
            lastResult = sim.tick(0f, PaddleInput.none(), PaddleInput.none());
        }

        assertEquals(GameConstants.WIN_SCORE, sim.getOpponentScore());
        assertTrue(lastResult.isMatchOver());
        assertFalse(lastResult.isPlayerWon());
    }

    @Test
    void customWinScoreEndsTheMatchEarly() {
        MatchSimulation shortMatch = new MatchSimulation(ball, playerPaddle, opponentPaddle, table, 5);
        moveAllPaddlesAway();

        TickResult lastResult = null;
        for (int point = 1; point <= 5; point++) {
            ball.setNetworkState(0f, ball.getRadius(), GameConstants.TABLE_HALF_LENGTH + 0.5f, 0f, 1f, 0f);
            lastResult = shortMatch.tick(0f, PaddleInput.none(), PaddleInput.none());
            assertEquals(point == 5, lastResult.isMatchOver(), "point " + point);
        }
        assertTrue(lastResult.isPlayerWon());
        assertEquals(5, shortMatch.getWinScore());
        assertEquals(GameConstants.WIN_SCORE, sim.getWinScore());
    }

    @Test
    void playerPowerUpActivationTriggersFlagAndRespectsCooldown() {
        moveAllPaddlesAway();
        ball.setNetworkState(0f, 3f, 0f, 0f, 0f, 0f); // parked well above paddle reach height, no collisions

        PowerUpDefinition grow = new PowerUpDefinition("grow_1", "Paddle Grow", 0, PowerUpType.PADDLE_GROW, 5f);
        PaddleInput activate = PaddleInput.move(0f, 0f).withPowerUp(grow);

        TickResult first = sim.tick(0f, activate, PaddleInput.none());
        assertTrue(first.isPlayerPowerUpActivated());
        assertTrue(first.isAnyPowerUpActivated());
        assertFalse(first.isOpponentPowerUpActivated());

        // Still on cooldown immediately after - activation should be rejected.
        TickResult second = sim.tick(0f, activate, PaddleInput.none());
        assertFalse(second.isPlayerPowerUpActivated());
    }

    @Test
    void opponentPowerUpActivationTriggersFlag() {
        moveAllPaddlesAway();
        ball.setNetworkState(0f, 3f, 0f, 0f, 0f, 0f);

        PowerUpDefinition slow = new PowerUpDefinition("slow_1", "Slow Opponent", 0, PowerUpType.SLOW_OPPONENT, 5f);
        PaddleInput activate = PaddleInput.move(0f, 0f).withPowerUp(slow);

        TickResult result = sim.tick(0f, PaddleInput.none(), activate);

        assertTrue(result.isOpponentPowerUpActivated());
        assertFalse(result.isPlayerPowerUpActivated());
    }

    @Test
    void noActivationWhenInputCarriesNoPowerUp() {
        moveAllPaddlesAway();
        ball.setNetworkState(0f, 3f, 0f, 0f, 0f, 0f);

        TickResult result = sim.tick(0f, PaddleInput.none(), PaddleInput.none());

        assertFalse(result.isAnyPowerUpActivated());
    }
}
