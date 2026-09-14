package com.paddleshock;

import java.awt.Dimension;
import java.awt.Toolkit;

import com.jme3.system.AppSettings;
import com.paddleshock.app.PaddleShockApp;
import com.paddleshock.data.SaveManager;
import com.paddleshock.diagnostics.CrashReporter;
import com.paddleshock.settings.GameSettings;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // Install the global crash handler before anything else that could throw, so even a
        // startup failure gets captured to a local crash file - see com.paddleshock.diagnostics.
        CrashReporter.install();

        PaddleShockApp app = new PaddleShockApp();
        GameSettings savedSettings = SaveManager.loadSettings();

        AppSettings settings = new AppSettings(true);
        settings.setTitle("PaddleShock");
        if (savedSettings.isFullscreen()) {
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            settings.setResolution(screenSize.width, screenSize.height);
            settings.setFullscreen(true);
        } else {
            settings.setResolution(savedSettings.getResolution().getWidth(), savedSettings.getResolution().getHeight());
        }
        settings.setSamples(savedSettings.getVideoQuality().getSamples());
        settings.setVSync(true);
        // jME defaults this to true, which reinterprets our UI/material colors as linear
        // and washes them out on screen; keep authored colors WYSIWYG instead.
        settings.setGammaCorrection(false);

        app.setSettings(settings);
        app.setShowSettings(false);
        // jME defaults this to true, which stops calling update() on every AppState (including
        // the multiplayer host/joiner network polling) whenever the window loses OS focus - e.g.
        // a host alt-tabbing to paste their IP into chat while waiting for a friend to connect
        // would never see the match start, even though the handshake completed fine in the
        // background network thread. A multiplayer game needs to keep ticking while unfocused.
        app.setPauseOnLostFocus(false);
        app.start();
    }
}
