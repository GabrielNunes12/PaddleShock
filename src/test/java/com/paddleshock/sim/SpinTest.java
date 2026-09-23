package com.paddleshock.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.ColorRGBA;

import com.paddleshock.GameConstants;
import com.paddleshock.entities.Ball;
import com.paddleshock.entities.BallModel;
import com.paddleshock.entities.Paddle;
import com.paddleshock.entities.PaddleModel;
import com.paddleshock.entities.Table;
import com.paddleshock.entities.TextureSet;

/** Spin: a sideways swipe at contact curves the ball - see docs/specs/03-spin.md. Same real,
 *  non-rendering entity setup as {@link MatchSimulationTest}. */
class SpinTest {

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

    /** Ball arriving at the player's paddle (at x=0) this tick: it starts just outside the hit
     *  range (radius + paddle height = 0.65) and crosses into it during the tick, like a real
     *  return - so exactly one contact happens, not a re-hit every tick while deep inside. */
    private void ballArrivingAtPlayerPaddle() {
        ball.setNetworkState(0f, ball.getRadius(), GameConstants.PADDLE_PLAYER_Z + 0.75f, 0f, -7f, 0f);
    }

    @Test
    void hitWithAStillPaddleImpartsNoSpin() {
        ballArrivingAtPlayerPaddle();
        TickResult result = sim.tick(TICK, PaddleInput.none(), PaddleInput.none());
        assertTrue(result.isPlayerPaddleHit());
        assertEquals(0f, ball.getSpin(), 1e-6f);
    }

    @Test
    void swipingRightCurvesTheBallRight() {
        ballArrivingAtPlayerPaddle();
        TickResult result = sim.tick(TICK, PaddleInput.move(0.2f, 0f), PaddleInput.none());
        assertTrue(result.isPlayerPaddleHit());
        assertTrue(ball.getSpin() > 0f, "a +x swipe must give +x spin, was " + ball.getSpin());

        float velXAfterHit = ball.getVelocity().x;
        for (int i = 0; i < 30; i++) {
            TickResult later = sim.tick(TICK, PaddleInput.none(), PaddleInput.none());
            assertTrue(!later.isAnyPaddleHit() && !later.isWallBounce(), "the flight must be free, tick " + i);
        }
        assertTrue(ball.getVelocity().x > velXAfterHit + 0.5f,
                "spin should bend the ball toward +x: " + velXAfterHit + " -> " + ball.getVelocity().x);
    }

    @Test
    void swipingLeftCurvesTheBallLeft() {
        ballArrivingAtPlayerPaddle();
        sim.tick(TICK, PaddleInput.move(-0.2f, 0f), PaddleInput.none());
        assertTrue(ball.getSpin() < 0f);
    }

    @Test
    void spinIsProportionalToSwipeAndClamped() {
        assertEquals(10f * GameConstants.SPIN_PER_PADDLE_SPEED, Ball.spinFromPaddleSpeed(10f), 1e-6f);
        assertEquals(GameConstants.SPIN_MAX, Ball.spinFromPaddleSpeed(10_000f), 1e-6f);
        assertEquals(-GameConstants.SPIN_MAX, Ball.spinFromPaddleSpeed(-10_000f), 1e-6f);
    }

    @Test
    void spinDecaysOverTimeAndServeResetsIt() {
        ball.setNetworkState(0f, 5f, 0f, 0f, 1f, 0f);
        ball.setSpin(GameConstants.SPIN_MAX);
        for (int i = 0; i < 60; i++) {
            ball.update(TICK);
        }
        float afterOneSecond = ball.getSpin();
        assertTrue(afterOneSecond > 0f && afterOneSecond < GameConstants.SPIN_MAX * 0.5f,
                "spin after 1s: " + afterOneSecond);

        ball.launch(1f);
        assertEquals(0f, ball.getSpin(), 1e-6f);
    }

    @Test
    void railBounceReversesAndHalvesSpin() {
        ball.setNetworkState(GameConstants.TABLE_HALF_WIDTH, ball.getRadius(), 0f, 3f, 1f, 0f);
        ball.setSpin(1.5f);
        ball.bounceOffSideRail();
        assertEquals(-0.75f, ball.getSpin(), 1e-6f);
    }
}
