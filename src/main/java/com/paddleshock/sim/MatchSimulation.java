package com.paddleshock.sim;

import com.jme3.math.Vector3f;

import com.paddleshock.GameConstants;
import com.paddleshock.data.LevelHazard;
import com.paddleshock.data.PowerUpDefinition;
import com.paddleshock.entities.Ball;
import com.paddleshock.entities.Paddle;
import com.paddleshock.entities.Table;
import com.paddleshock.powerups.PowerUpManager;
import com.paddleshock.powerups.PowerUpType;

/**
 * Owns the authoritative state of a single match - the ball, both paddles, power-ups and score -
 * and advances it one step at a time via {@link #tick}. This class makes zero jME scene-graph or
 * rendering calls (no {@code Geometry}/{@code Node}/HUD/{@code BitmapText} references); it only
 * reads/writes the {@link Ball}/{@link Paddle}/{@link PowerUpManager} game-state objects and does
 * plain math ({@link Vector3f} is jME's math type, not a scene-graph type).
 *
 * <p>Today {@code GameplayAppState} is the sole (and always-authoritative) driver of this class,
 * feeding it local-mouse input for the player side and local AI-decision input for the opponent
 * side, then reading back {@link TickResult} plus the entities' own transforms to drive rendering.
 * Later, a server-authoritative driver can own an instance of this same class instead, with the
 * client only rendering + interpolating from it - nothing in this class needs to change for that.
 */
public final class MatchSimulation {

    private final Ball ball;
    private final Paddle playerPaddle;
    private final Paddle opponentPaddle;
    private final Table table;
    private final PowerUpManager powerUpManager;
    /** Points needed to win - {@link GameConstants#WIN_SCORE} except for shorter World Tour matches. */
    private final int winScore;
    private final LevelHazard hazard;
    private final IceSlide playerIce = new IceSlide();
    private final IceSlide opponentIce = new IceSlide();
    /** Seconds since the match started - drives the Pinball bumpers' slide. */
    private float matchTime;

    private float playerPaddleSpeedX;
    private float opponentPaddleSpeedX;

    private int playerScore;
    private int opponentScore;

    public MatchSimulation(Ball ball, Paddle playerPaddle, Paddle opponentPaddle, Table table) {
        this(ball, playerPaddle, opponentPaddle, table, GameConstants.WIN_SCORE);
    }

    public MatchSimulation(Ball ball, Paddle playerPaddle, Paddle opponentPaddle, Table table, int winScore) {
        this(ball, playerPaddle, opponentPaddle, table, winScore, LevelHazard.NONE);
    }

    public MatchSimulation(Ball ball, Paddle playerPaddle, Paddle opponentPaddle, Table table, int winScore,
            LevelHazard hazard) {
        this.hazard = hazard;
        this.winScore = winScore;
        this.ball = ball;
        this.playerPaddle = playerPaddle;
        this.opponentPaddle = opponentPaddle;
        this.table = table;
        this.powerUpManager = new PowerUpManager(playerPaddle, opponentPaddle);
    }

    /** Resets the score to 0-0 and serves a fresh ball in a random direction. */
    public void startNewMatch() {
        playerScore = 0;
        opponentScore = 0;
        matchTime = 0f;
        ball.launch(Math.random() < 0.5 ? 1f : -1f);
    }

    /** Advances the simulation by exactly one step. Pure game-state logic. */
    public TickResult tick(float tpf, PaddleInput playerInput, PaddleInput opponentInput) {
        TickResult result = new TickResult();

        matchTime += tpf;
        movePaddle(playerPaddle, playerIce, playerInput, tpf);
        movePaddle(opponentPaddle, opponentIce, opponentInput, tpf);
        // Sideways swipe speed this tick, which becomes spin if that paddle hits the ball.
        playerPaddleSpeedX = tpf > 0f ? playerPaddle.getLastMoveX() / tpf : 0f;
        opponentPaddleSpeedX = tpf > 0f ? opponentPaddle.getLastMoveX() / tpf : 0f;

        PowerUpDefinition playerPowerUp = playerInput.getActivatedPowerUp();
        if (playerPowerUp != null) {
            boolean activated = powerUpManager.activatePlayerPowerUp(playerPowerUp.getType(), playerPowerUp.getCooldownSeconds());
            result.setPlayerPowerUpActivated(activated);
            if (activated) {
                result.setPlayerActivatedType(playerPowerUp.getType());
            }
        }
        PowerUpDefinition opponentPowerUp = opponentInput.getActivatedPowerUp();
        if (opponentPowerUp != null) {
            boolean activated = powerUpManager.activateAiPowerUp(opponentPowerUp.getType(), opponentPowerUp.getCooldownSeconds());
            result.setOpponentPowerUpActivated(activated);
            if (activated) {
                result.setOpponentActivatedType(opponentPowerUp.getType());
            }
        }

        ball.update(tpf);
        powerUpManager.update(tpf);

        handleCollisions(result);
        return result;
    }

    /** Applies one side's movement input - straight through, or through its ice slide on Glacier Rink. */
    private void movePaddle(Paddle paddle, IceSlide ice, PaddleInput input, float tpf) {
        if (hazard != LevelHazard.ICE) {
            paddle.moveDelta(input.getDeltaX(), input.getDeltaZ());
            return;
        }
        float[] slid = ice.step(input.getDeltaX(), input.getDeltaZ(), tpf);
        paddle.moveDelta(slid[0], slid[1]);
        // moveDelta scales by the paddle's speed multipliers; compare in input units.
        float multiplier = paddle.getEffectiveSpeedMultiplier();
        ice.onBlocked(slid[0], multiplier > 0f ? paddle.getLastMoveX() / multiplier : 0f, tpf);
    }

    /** The near Pinball bumper's current x (the far one is its mirror); 0 on any other arena. */
    public float getBumperOffset() {
        return hazard == LevelHazard.BUMPERS ? Bumpers.offsetAt(matchTime) : 0f;
    }

    public LevelHazard getHazard() {
        return hazard;
    }

    private void handleCollisions(TickResult result) {
        Vector3f pos = ball.getPosition();

        if (hazard == LevelHazard.BUMPERS && Bumpers.collide(ball, getBumperOffset())) {
            result.setWallBounce(true);
        }

        if (table.isOutsideSideRails(pos, ball.getRadius())) {
            ball.bounceOffSideRail();
            result.setWallBounce(true);
        }

        if (tryPaddleBounce(playerPaddle, playerPaddleSpeedX, true)) {
            result.setPlayerPaddleHit(true);
        }
        if (tryPaddleBounce(opponentPaddle, opponentPaddleSpeedX, false)) {
            result.setOpponentPaddleHit(true);
        }

        // A live Shield turns a would-be goal into a rebound off the goal line (one block each).
        if (pos.z < -GameConstants.TABLE_HALF_LENGTH && powerUpManager.consumeEffect(true, PowerUpType.SHIELD)) {
            ball.reboundFromGoal(-GameConstants.TABLE_HALF_LENGTH);
            result.setShieldBlocked(true);
        } else if (pos.z > GameConstants.TABLE_HALF_LENGTH && powerUpManager.consumeEffect(false, PowerUpType.SHIELD)) {
            ball.reboundFromGoal(GameConstants.TABLE_HALF_LENGTH);
            result.setShieldBlocked(true);
        } else if (pos.z < -GameConstants.TABLE_HALF_LENGTH) {
            opponentScore++;
            result.setScorer(TickResult.Scorer.OPPONENT);
            if (opponentScore >= winScore) {
                result.setMatchOver(true);
                result.setPlayerWon(false);
            } else {
                ball.launch(1f);
            }
        } else if (pos.z > GameConstants.TABLE_HALF_LENGTH) {
            playerScore++;
            result.setScorer(TickResult.Scorer.PLAYER);
            if (playerScore >= winScore) {
                result.setMatchOver(true);
                result.setPlayerWon(true);
            } else {
                ball.launch(-1f);
            }
        }
    }

    private boolean tryPaddleBounce(Paddle paddle, float paddleSpeedX, boolean playerSide) {
        Vector3f ballPos = ball.getPosition();
        Vector3f paddlePos = paddle.getPosition();

        float zGap = ballPos.z - paddlePos.z;
        boolean withinReach = Math.abs(zGap) < (ball.getRadius() + GameConstants.PADDLE_HEIGHT);
        boolean withinPaddleWidth = Math.abs(ballPos.x - paddlePos.x) < paddle.getEffectiveRadius() + ball.getRadius();
        boolean lowEnoughToHit = ball.isWithinPaddleReach();

        if (withinReach && withinPaddleWidth && lowEnoughToHit) {
            ball.bounceOffPaddle(paddle);
            float spin = Ball.spinFromPaddleSpeed(paddleSpeedX);
            if (powerUpManager.consumeEffect(playerSide, PowerUpType.CURVEBALL)) {
                Paddle other = playerSide ? opponentPaddle : playerPaddle;
                spin = curveballSpin(paddleSpeedX, ballPos.x, other.getPosition().x);
            }
            ball.setSpin(spin);
            return true;
        }
        return false;
    }

    /** A Curveball hit: maximum spin, in the swipe direction - or, for a still paddle, curving
     *  away from where the opponent's paddle is. */
    static float curveballSpin(float paddleSpeedX, float ballX, float otherPaddleX) {
        float direction;
        if (Math.abs(paddleSpeedX) >= GameConstants.CURVEBALL_MIN_SWIPE_SPEED) {
            direction = Math.signum(paddleSpeedX);
        } else {
            direction = otherPaddleX >= ballX ? -1f : 1f;
        }
        return direction * GameConstants.SPIN_MAX;
    }

    /** Ghost Ball: whether the ball should be hidden from the player ({@code forPlayerSide}) or
     *  the opponent right now - only while a Ghost Ball is live on that side and the ball is over
     *  the middle of the table. */
    public boolean isBallHiddenFor(boolean forPlayerSide) {
        return powerUpManager.hasEffect(forPlayerSide, PowerUpType.GHOST_BALL) && isInGhostZone(ball.getPosition().z);
    }

    public static boolean isInGhostZone(float ballZ) {
        return Math.abs(ballZ) < GameConstants.GHOST_ZONE_HALF_DEPTH;
    }

    public Ball getBall() {
        return ball;
    }

    public Paddle getPlayerPaddle() {
        return playerPaddle;
    }

    public Paddle getOpponentPaddle() {
        return opponentPaddle;
    }

    public PowerUpManager getPowerUpManager() {
        return powerUpManager;
    }

    public int getPlayerScore() {
        return playerScore;
    }

    public int getOpponentScore() {
        return opponentScore;
    }

    public int getWinScore() {
        return winScore;
    }
}
