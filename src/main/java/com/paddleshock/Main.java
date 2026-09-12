package com.paddleshock;

import java.awt.Dimension;
import java.awt.Toolkit;

import com.jme3.system.AppSettings;
import com.paddleshock.app.PaddleShockApp;
import com.paddleshock.data.SaveManager;
import com.paddleshock.settings.GameSettings;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        PaddleShockApp app = new PaddleShockApp();
        GameSettings savedSettings = SaveManager.loadSettings();

        AppSettings settings = new AppSettings(true);
        settings.setTitle("PaddleShock");
        if (savedSettings.isFullscreen()) {
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            settings.setResolution(screenSize.width, screenSize.height);
            settings.setFullscreen(true);
        } else {
            settings.setResolution(1280, 720);
        }
        settings.setSamples(savedSettings.getVideoQuality().getSamples());
        settings.setVSync(true);
        // jME defaults this to true, which reinterprets our UI/material colors as linear
        // and washes them out on screen; keep authored colors WYSIWYG instead.
        settings.setGammaCorrection(false);

        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }
}
