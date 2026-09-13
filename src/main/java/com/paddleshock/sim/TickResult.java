package com.paddleshock.sim;

import com.paddleshock.powerups.PowerUpType;

/**
 * Everything observable that happened during one {@link MatchSimulation#tick} call, for the
 * caller to translate into SFX/HUD/scene-graph side effects. Pure data - no jME types.
 */
public final class TickResult {

    public enum Scorer { NONE, PLAYER, OPPONENT }

    private boolean wallBounce;
    private boolean playerPaddleHit;
    private boolean opponentPaddleHit;
    private boolean playerPowerUpActivated;
    private boolean opponentPowerUpActivated;
    private PowerUpType playerActivatedType;
    private PowerUpType opponentActivatedType;
    private Scorer scorer = Scorer.NONE;
    private boolean matchOver;
    private boolean playerWon;

    public boolean isWallBounce() {
        return wallBounce;
    }

    void setWallBounce(boolean wallBounce) {
        this.wallBounce = wallBounce;
    }

    public boolean isPlayerPaddleHit() {
        return playerPaddleHit;
    }

    void setPlayerPaddleHit(boolean playerPaddleHit) {
        this.playerPaddleHit = playerPaddleHit;
    }

    public boolean isOpponentPaddleHit() {
        return opponentPaddleHit;
    }

    void setOpponentPaddleHit(boolean opponentPaddleHit) {
        this.opponentPaddleHit = opponentPaddleHit;
    }

    /** Whether any paddle was hit this tick (convenience for a single "paddle_hit" SFX trigger). */
    public boolean isAnyPaddleHit() {
        return playerPaddleHit || opponentPaddleHit;
    }

    public boolean isPlayerPowerUpActivated() {
        return playerPowerUpActivated;
    }

    void setPlayerPowerUpActivated(boolean playerPowerUpActivated) {
        this.playerPowerUpActivated = playerPowerUpActivated;
    }

    public boolean isOpponentPowerUpActivated() {
        return opponentPowerUpActivated;
    }

    void setOpponentPowerUpActivated(boolean opponentPowerUpActivated) {
        this.opponentPowerUpActivated = opponentPowerUpActivated;
    }

    /** Which power-up the player successfully activated this tick, or {@code null} if
     *  {@link #isPlayerPowerUpActivated()} is false. Lets HUD feedback name the actual effect
     *  (buff vs. debuff) instead of relying on its swatch color alone. */
    public PowerUpType getPlayerActivatedType() {
        return playerActivatedType;
    }

    void setPlayerActivatedType(PowerUpType playerActivatedType) {
        this.playerActivatedType = playerActivatedType;
    }

    /** Same as {@link #getPlayerActivatedType()}, for the opponent side. */
    public PowerUpType getOpponentActivatedType() {
        return opponentActivatedType;
    }

    void setOpponentActivatedType(PowerUpType opponentActivatedType) {
        this.opponentActivatedType = opponentActivatedType;
    }

    /** Whether any power-up successfully activated this tick (convenience for a single SFX trigger). */
    public boolean isAnyPowerUpActivated() {
        return playerPowerUpActivated || opponentPowerUpActivated;
    }

    public Scorer getScorer() {
        return scorer;
    }

    void setScorer(Scorer scorer) {
        this.scorer = scorer;
    }

    public boolean isMatchOver() {
        return matchOver;
    }

    void setMatchOver(boolean matchOver) {
        this.matchOver = matchOver;
    }

    public boolean isPlayerWon() {
        return playerWon;
    }

    void setPlayerWon(boolean playerWon) {
        this.playerWon = playerWon;
    }
}
