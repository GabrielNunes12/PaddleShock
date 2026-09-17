package com.paddleshock.app;

import java.util.List;

import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.input.InputManager;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;

import com.paddleshock.entities.Ball;
import com.paddleshock.entities.Paddle;
import com.paddleshock.i18n.I18n;
import com.paddleshock.replay.ReplayRecorder;
import com.paddleshock.replay.ReplaySample;
import com.paddleshock.ui.Theme;

/**
 * Owns the post-match instant replay: recording (via {@link ReplayRecorder}) during live play,
 * then played back over the SAME scene ball/paddle objects once a match ends (see
 * {@code GameplayAppState#applyTickResult}/{@code GameplayAppState#applySnapshotToScene}), before
 * running the deferred match-end action. Pulled out of {@link GameplayAppState} since it's a
 * genuinely self-contained state machine (recording -> playing -> done) that only needs the
 * ball/paddle scene objects and an {@link InputManager} (to restore cursor visibility for the SKIP
 * click) - none of the mode-specific simulation/networking logic around it.
 */
final class ReplayController {

    private final Ball ball;
    private final Paddle playerPaddle;
    private final Paddle opponentPaddle;
    private final InputManager inputManager;

    private final ReplayRecorder replayRecorder = new ReplayRecorder();
    private boolean replaying;
    private List<ReplaySample> replayBuffer;
    private int replayIndex;
    private float replayElapsedInSample;
    /** The exact {@code PaddleShockApp.endMatch}/{@code endRankedHostMatch}/{@code
     *  endRankedJoinerMatch} call that was deferred to run the instant replay first - invoked once
     *  playback finishes or is skipped, so every existing match-end call site keeps working exactly
     *  as before. */
    private Runnable pendingMatchEndAction;
    private BitmapText replayLabelText;
    private BitmapText replaySkipText;

    ReplayController(Ball ball, Paddle playerPaddle, Paddle opponentPaddle, InputManager inputManager) {
        this.ball = ball;
        this.playerPaddle = playerPaddle;
        this.opponentPaddle = opponentPaddle;
        this.inputManager = inputManager;
    }

    /** Builds the (initially hidden) "INSTANT REPLAY" label + skip hint, attached to {@code hudNode}. */
    void buildHud(Node hudNode, BitmapFont font, float screenWidth, float screenHeight) {
        replayLabelText = new BitmapText(font);
        replayLabelText.setSize(34);
        replayLabelText.setColor(Theme.ORANGE);
        replayLabelText.setText(I18n.t("gameplay.instant_replay"));
        replayLabelText.setLocalTranslation(
                (screenWidth - replayLabelText.getLineWidth()) / 2f, screenHeight - 40, 5);
        replayLabelText.setCullHint(Spatial.CullHint.Always);
        hudNode.attachChild(replayLabelText);

        replaySkipText = new BitmapText(font);
        replaySkipText.setSize(16);
        replaySkipText.setColor(Theme.TEXT_DIM);
        replaySkipText.setText(I18n.t("gameplay.replay_skip_hint"));
        replaySkipText.setLocalTranslation(
                (screenWidth - replaySkipText.getLineWidth()) / 2f, screenHeight - 78, 5);
        replaySkipText.setCullHint(Spatial.CullHint.Always);
        hudNode.attachChild(replaySkipText);
    }

    boolean isReplaying() {
        return replaying;
    }

    /** Appends one already-built sample to the in-progress recording. */
    void recordSample(ReplaySample sample) {
        replayRecorder.record(sample);
    }

    /** Starts playing the just-recorded buffer back over the real ball/paddle objects - see
     *  {@link #update}, deferring {@code postMatchEndAction} until playback finishes naturally or
     *  is skipped (see {@link #skip}). If nothing was recorded (shouldn't normally happen - a match
     *  always runs at least one tick), skips straight to the match-end action instead of showing
     *  an empty replay. */
    void start(Runnable postMatchEndAction) {
        replayBuffer = replayRecorder.snapshot();
        replayIndex = 0;
        replayElapsedInSample = 0f;
        pendingMatchEndAction = postMatchEndAction;
        replaying = !replayBuffer.isEmpty();
        if (!replaying) {
            finish();
            return;
        }
        showHud();
    }

    /** Steps through the recorded buffer at roughly the pace it was recorded at (each sample's own
     *  {@code tpf}), re-applying each sample's ball/paddle positions to the real scene objects via
     *  the same {@code setNetworkState}/{@code setNetworkPosition} seam a networked joiner already
     *  uses to render a received snapshot - no new scene objects, no new rendering path. Call only
     *  while {@link #isReplaying()} is true. */
    void update(float tpf) {
        if (replayBuffer == null || replayIndex >= replayBuffer.size()) {
            finish();
            return;
        }
        ReplaySample sample = replayBuffer.get(replayIndex);
        ball.setNetworkState(sample.ballX(), sample.ballY(), sample.ballZ(),
                sample.ballVelX(), sample.ballVelZ(), sample.ballVerticalVel());
        playerPaddle.setNetworkPosition(sample.playerPaddleX(), sample.playerPaddleZ());
        opponentPaddle.setNetworkPosition(sample.opponentPaddleX(), sample.opponentPaddleZ());

        replayElapsedInSample += tpf;
        if (replayElapsedInSample >= Math.max(sample.tpf(), 0.0001f)) {
            replayElapsedInSample = 0f;
            replayIndex++;
            if (replayIndex >= replayBuffer.size()) {
                finish();
            }
        }
    }

    /** Left click, Enter, or Space while replaying - jumps straight to the deferred match-end
     *  action instead of finishing the playback naturally. */
    void skip() {
        finish();
    }

    /** Ends replay playback (naturally finishing, or skipped via {@link #skip}) and runs the
     *  deferred match-end action - see {@link #start}. */
    private void finish() {
        replaying = false;
        replayBuffer = null;
        hideHud();
        Runnable action = pendingMatchEndAction;
        pendingMatchEndAction = null;
        if (action != null) {
            action.run();
        }
    }

    private void showHud() {
        if (replayLabelText != null) {
            replayLabelText.setCullHint(Spatial.CullHint.Never);
            replaySkipText.setCullHint(Spatial.CullHint.Never);
        }
        // The gameplay cursor is normally hidden (see GameplayAppState#onEnable) so raw mouse
        // deltas can drive the paddle; make it visible again so the player can actually click SKIP.
        inputManager.setCursorVisible(true);
    }

    private void hideHud() {
        if (replayLabelText != null) {
            replayLabelText.setCullHint(Spatial.CullHint.Always);
            replaySkipText.setCullHint(Spatial.CullHint.Always);
        }
    }

    /** Resets all replay state for a fresh/rematch match - see {@code GameplayAppState#startNewMatch}. */
    void reset() {
        replayRecorder.reset();
        replaying = false;
        replayBuffer = null;
        pendingMatchEndAction = null;
        hideHud();
    }
}
