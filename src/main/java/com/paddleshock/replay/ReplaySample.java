package com.paddleshock.replay;

/**
 * One recorded tick's worth of renderable match state - everything {@link ReplayRecorder}
 * needs to redraw the scene during instant-replay playback without re-running the simulation:
 * the ball's position/velocity, both paddles' positions, and the score at that instant.
 * {@code tpf} is the real duration this sample represents, so playback can advance through the
 * buffer at roughly the same pace it was recorded at regardless of the frame/tick rate that
 * produced it.
 */
public record ReplaySample(
        float tpf,
        float ballX, float ballY, float ballZ,
        float ballVelX, float ballVelZ, float ballVerticalVel,
        float playerPaddleX, float playerPaddleZ,
        float opponentPaddleX, float opponentPaddleZ,
        int playerScore, int opponentScore) {
}
