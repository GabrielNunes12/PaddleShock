package com.paddleshock.audio;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.jme3.asset.AssetManager;
import com.jme3.audio.AudioData;
import com.jme3.audio.AudioNode;

import com.paddleshock.settings.GameSettings;

/** Central place for playing sound effects and looping background music, at the current settings' volumes. */
public class AudioManager {

    private static final String SFX_PATH = "Sounds/SFX/";
    private static final String MUSIC_PATH = "Sounds/Music/";
    private static final String[] MATCH_THEMES = {
        MUSIC_PATH + "match_theme_1.ogg", MUSIC_PATH + "match_theme_2.ogg", MUSIC_PATH + "match_theme_3.ogg"
    };

    private final AssetManager assetManager;
    private final GameSettings settings;
    private final Map<String, AudioNode> sfxCache = new HashMap<>();

    private AudioNode currentMusic;

    public AudioManager(AssetManager assetManager, GameSettings settings) {
        this.assetManager = assetManager;
        this.settings = settings;
    }

    /** Plays a one-shot effect; safe to call rapidly/overlapping (e.g. quick paddle volleys). */
    public void playSfx(String fileName) {
        if (settings.getSoundVolume() <= 0f) {
            return;
        }
        AudioNode node = sfxCache.computeIfAbsent(fileName, name -> {
            AudioNode sfx = new AudioNode(assetManager, SFX_PATH + name, AudioData.DataType.Buffer);
            sfx.setPositional(false);
            sfx.setLooping(false);
            return sfx;
        });
        node.setVolume(settings.getSoundVolume());
        node.playInstance();
    }

    /** Same, with per-play volume scale and pitch (e.g. harder hits sound higher and louder -
     *  see {@link HitSoundMapping}). */
    public void playSfx(String fileName, float volumeScale, float pitch) {
        if (settings.getSoundVolume() <= 0f) {
            return;
        }
        AudioNode node = sfxCache.computeIfAbsent(fileName, name -> {
            AudioNode sfx = new AudioNode(assetManager, SFX_PATH + name, AudioData.DataType.Buffer);
            sfx.setPositional(false);
            sfx.setLooping(false);
            return sfx;
        });
        node.setVolume(settings.getSoundVolume() * volumeScale);
        node.setPitch(Math.max(0.5f, Math.min(2f, pitch)));
        node.playInstance();
        node.setPitch(1f);
    }

    public void playMenuMusic() {
        playMusic(MUSIC_PATH + "menu_theme.ogg");
    }

    /** Picks one of a few match themes at random each time, so matches don't all sound the same. */
    public void playRandomMatchMusic() {
        String track = MATCH_THEMES[ThreadLocalRandom.current().nextInt(MATCH_THEMES.length)];
        playMusic(track);
    }

    public void stopMusic() {
        if (currentMusic != null) {
            currentMusic.stop();
            currentMusic = null;
        }
    }

    private void playMusic(String path) {
        stopMusic();
        currentMusic = new AudioNode(assetManager, path, AudioData.DataType.Stream);
        currentMusic.setPositional(false);
        currentMusic.setLooping(true);
        currentMusic.setVolume(settings.getMusicVolume());
        currentMusic.play();
    }

    /** Applies the current settings' music volume live, e.g. right after a settings-screen adjustment. */
    public void refreshMusicVolume() {
        if (currentMusic != null) {
            currentMusic.setVolume(settings.getMusicVolume());
        }
    }
}
