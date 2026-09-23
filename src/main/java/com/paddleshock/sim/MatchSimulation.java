package com.paddleshock.sim;

import com.jme3.math.Vector3f;

import com.paddleshock.GameConstants;
import com.paddleshock.data.PowerUpDefinition;
import com.paddleshock.entities.Ball;
import com.paddleshock.entities.Paddle;
import com.paddleshock.entities.Table;
import com.paddleshock.powerups.PowerUpManager;

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

    private int playerScore;
    private int opponentScore;

    public MatchSimulation(Ball ball, Paddle playerPaddle, Paddle opponentPaddle, Table table) {
        this(ball, playerPaddle, opponentPaddle, table, GameConstants.WIN_SCORE);
    }

    public MatchSimulation(Ball ball, Paddle playerPaddle, Paddle opponentPaddle, Table table, int winScore) {
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
        ball.launch(Math.random() < 0.5 ? 1f : -1f);
    }

    /** Advances the simulation by exactly one step. Pure game-state logic. */
    public TickResult tick(float tpf, PaddleInput playerInput, PaddleInput opponentInput) {
        TickResult result = new TickResult();

        playerPaddle.moveDelta(playerInput.getDeltaX(), playerInput.getDeltaZ());
        opponentPaddle.moveDelta(opponentInput.getDeltaX(), opponentInput.getDeltaZ());

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

    private void handleCollisions(TickResult result) {
        Vector3f pos = ball.getPosition();

        if (table.isOutsideSideRails(pos, ball.getRadius())) {
            ball.bounceOffSideRail();
            result.setWallBounce(true);
        }

        if (tryPaddleBounce(playerPaddle)) {
            result.setPlayerPaddleHit(true);
        }
        if (tryPaddleBounce(opponentPaddle)) {
            result.setOpponentPaddleHit(true);
        }

        if (pos.z < -GameConstants.TABLE_HALF_LENGTH) {
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

    private boolean tryPaddleBounce(Paddle paddle) {
        Vector3f ballPos = ball.getPosition();
        Vector3f paddlePos = paddle.getPosition();

        float zGap = ballPos.z - paddlePos.z;
        boolean withinReach = Math.abs(zGap) < (ball.getRadius() + GameConstants.PADDLE_HEIGHT);
        boolean withinPaddleWidth = Math.abs(ballPos.x - paddlePos.x) < paddle.getEffectiveRadius() + ball.getRadius();
        boolean lowEnoughToHit = ball.isWithinPaddleReach();

        if (withinReach && withinPaddleWidth && lowEnoughToHit) {
            ball.bounceOffPaddle(paddle);
            return true;
        }
        return false;
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
