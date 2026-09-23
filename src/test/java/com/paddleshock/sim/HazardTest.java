package com.paddleshock.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.ColorRGBA;

import com.paddleshock.GameConstants;
import com.paddleshock.data.LevelHazard;
import com.paddleshock.entities.Ball;
import com.paddleshock.entities.BallModel;
import com.paddleshock.entities.Paddle;
import com.paddleshock.entities.PaddleModel;
import com.paddleshock.entities.Table;
import com.paddleshock.entities.TextureSet;

/** Pinball bumpers and Glacier ice - see docs/specs/05-hazard-arenas.md. */
class HazardTest {

    private static final float TICK = 1f / 60f;
    private static AssetManager assetManager;

    private Ball ball;
    private Paddle playerPaddle;
    private Paddle opponentPaddle;
    private Table table;

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
    }

    private MatchSimulation sim(LevelHazard hazard) {
        return new MatchSimulation(ball, playerPaddle, opponentPaddle, table, GameConstants.WIN_SCORE, hazard);
    }

    /** A low ball about to run head-on into the bumper at (x, z), approaching along -z. */
    private void lowBallHeadingInto(float x, float z) {
        ball.setNetworkState(x, ball.getRadius(), z + Bumpers.RADIUS + ball.getRadius() - 0.05f, 0f, -7f, 0f);
    }

    // ---- Bumpers ----

    @Test
    void lowBallReboundsOffTheNearBumperAndSpeedsUp() {
        lowBallHeadingInto(1.5f, -Bumpers.Z);
        assertTrue(Bumpers.collide(ball, 1.5f));
        assertTrue(ball.getVelocity().z > 0f, "sent back the way it came");
        assertTrue(ball.getVelocity().length() > 7.2f, "rebounds faster than it arrived: " + ball.getVelocity().length());
        float gap = ball.getPosition().distance(new com.jme3.math.Vector3f(1.5f, ball.getPosition().y, -Bumpers.Z));
        assertTrue(gap >= Bumpers.RADIUS + ball.getRadius() - 1e-4f, "pushed out of the bumper");
    }

    @Test
    void theFarBumperMirrorsTheNearOne() {
        lowBallHeadingInto(-1.5f, Bumpers.Z);
        assertTrue(Bumpers.collide(ball, 1.5f), "far bumper sits at -offset");
        lowBallHeadingInto(1.5f, Bumpers.Z);
        assertFalse(Bumpers.collide(ball, 1.5f), "nothing at +offset in the far half");
        for (float t = 0f; t < 10f; t += 0.37f) {
            assertTrue(Math.abs(Bumpers.offsetAt(t)) <= Bumpers.TRAVEL + 1e-5f);
        }
    }

    @Test
    void aHighBallSailsOverAndARecedingBallIsNotTrapped() {
        ball.setNetworkState(0f, Bumpers.HEIGHT + ball.getRadius() + 0.1f, -Bumpers.Z + 0.5f, 0f, -7f, 0f);
        assertFalse(Bumpers.collide(ball, 0f), "high ball passes over");

        ball.setNetworkState(0f, ball.getRadius(), -Bumpers.Z + 0.5f, 0f, 7f, 0f);
        assertFalse(Bumpers.collide(ball, 0f), "a ball already moving away is left alone");
    }

    @Test
    void reboundSpeedIsCapped() {
        ball.setNetworkState(0f, ball.getRadius(), -Bumpers.Z + Bumpers.RADIUS + ball.getRadius() - 0.05f, 0f,
                -GameConstants.BALL_MAX_SPEED, 0f);
        assertTrue(Bumpers.collide(ball, 0f));
        assertEquals(GameConstants.BALL_MAX_SPEED, ball.getVelocity().length(), 1e-3f);
    }

    @Test
    void bumpersOnlyExistOnThePinballArena() {
        playerPaddle.setNetworkPosition(500f, GameConstants.PADDLE_PLAYER_Z);
        opponentPaddle.setNetworkPosition(500f, GameConstants.PADDLE_OPPONENT_Z);

        MatchSimulation pinball = sim(LevelHazard.BUMPERS);
        lowBallHeadingInto(0f, -Bumpers.Z);
        assertTrue(pinball.tick(0f, PaddleInput.none(), PaddleInput.none()).isWallBounce());

        MatchSimulation plain = sim(LevelHazard.NONE);
        lowBallHeadingInto(0f, -Bumpers.Z);
        assertFalse(plain.tick(0f, PaddleInput.none(), PaddleInput.none()).isWallBounce());
        assertEquals(0f, plain.getBumperOffset());
    }

    // ---- Ice ----

    @Test
    void iceRampsUpThenSlidesOnAfterRelease() {
        IceSlide ice = new IceSlide();
        float first = ice.step(0.1f, 0f, TICK)[0];
        assertTrue(first > 0f && first < 0.05f, "a standing start lags: " + first);
        float moved = first;
        for (int i = 0; i < 120; i++) {
            moved = ice.step(0.1f, 0f, TICK)[0];
        }
        assertEquals(0.1f, moved, 0.005f, "catches up with steady input");

        float coast = ice.step(0f, 0f, TICK)[0];
        assertTrue(coast > 0.05f, "keeps sliding after the input stops: " + coast);
        for (int i = 0; i < 180; i++) {
            coast = ice.step(0f, 0f, TICK)[0];
        }
        assertTrue(coast < 0.001f, "and eventually stops: " + coast);
    }

    @Test
    void aRailStopsTheSlide() {
        IceSlide ice = new IceSlide();
        for (int i = 0; i < 60; i++) {
            ice.step(0.1f, 0f, TICK);
        }
        ice.onBlocked(0.1f, 0f, TICK);
        assertEquals(0f, ice.getVelocityX(), 1e-6f);
    }

    @Test
    void icyPaddlesLagInTheSimulationButNotElsewhere() {
        MatchSimulation glacier = sim(LevelHazard.ICE);
        ball.setNetworkState(0f, 50f, 0f, 0f, 0f, 0f);
        glacier.tick(TICK, PaddleInput.move(0.1f, 0f), PaddleInput.none());
        assertTrue(playerPaddle.getPosition().x < 0.05f, "ice lags: " + playerPaddle.getPosition().x);
        float afterPush = playerPaddle.getPosition().x;
        glacier.tick(TICK, PaddleInput.none(), PaddleInput.none());
        assertTrue(playerPaddle.getPosition().x > afterPush, "still sliding with no input");

        playerPaddle.setNetworkPosition(0f, GameConstants.PADDLE_PLAYER_Z);
        MatchSimulation plain = sim(LevelHazard.NONE);
        plain.tick(TICK, PaddleInput.move(0.1f, 0f), PaddleInput.none());
        assertEquals(0.1f, playerPaddle.getPosition().x, 1e-5f);
    }

    @Test
    void aSpeedDebuffDoesNotKillTheIceSlide() {
        // Regression: the rail check once compared the ice's request against the paddle's
        // speed-scaled movement, so a 0.5x debuff read as "blocked" every tick.
        MatchSimulation glacier = sim(LevelHazard.ICE);
        ball.setNetworkState(0f, 50f, 0f, 0f, 0f, 0f);
        // A real Slow Opponent from the AI (a directly-set buff is recomputed away every tick).
        PaddleInput slowPlayer = PaddleInput.none().withPowerUp(
                com.paddleshock.data.Catalog.findPowerUp("powerup_slow_opponent").orElseThrow());
        assertTrue(glacier.tick(TICK, PaddleInput.move(0.01f, 0f), slowPlayer).isOpponentPowerUpActivated());
        for (int i = 0; i < 120; i++) {
            glacier.tick(TICK, PaddleInput.move(0.01f, 0f), PaddleInput.none());
        }
        assertEquals(0.01f * 0.5f, playerPaddle.getLastMoveX(), 5e-4f);
    }
}
