package com.paddleshock;

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
        settings.setResolution(1280, 720);
        settings.setSamples(savedSettings.getVideoQuality().getSamples());
        settings.setVSync(true);

        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }
}
