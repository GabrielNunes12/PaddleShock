package com.paddleshock.ui;

import com.jme3.math.Vector2f;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;
import com.simsilica.lemur.component.IconComponent;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * Small, simplified flag icons for the Settings LANGUAGE card (US / Brazil), drawn procedurally
 * with AWT rather than shipped as image assets - the project has no flag art yet, and these are
 * tiny enough (32x22) that generating them at startup is simpler than adding a new asset pipeline.
 * Iconic/simplified, not photorealistic, matching the approved design mockup.
 */
public final class FlagIcons {

    private static final int W = 32;
    private static final int H = 22;

    private static IconComponent usFlag;
    private static IconComponent brFlag;

    private FlagIcons() {
    }

    public static IconComponent usFlag() {
        if (usFlag == null) {
            usFlag = build(drawUsFlag());
        }
        return usFlag.clone();
    }

    public static IconComponent brFlag() {
        if (brFlag == null) {
            brFlag = build(drawBrFlag());
        }
        return brFlag.clone();
    }

    private static IconComponent build(BufferedImage image) {
        Image jmeImage = new AWTLoader().load(image, false);
        Texture2D texture = new Texture2D(jmeImage);
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        return new IconComponent(texture, new Vector2f(1, 1), 0, 0, 0, false);
    }

    private static BufferedImage drawUsFlag() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xF5, 0xF6, 0xF8));
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(0xB2, 0x22, 0x34));
        float stripeH = H / 7f;
        for (int i = 0; i < 7; i += 2) {
            g.fillRect(0, Math.round(i * stripeH), W, Math.round(stripeH) + 1);
        }
        int cantonW = W * 2 / 5;
        int cantonH = Math.round(stripeH * 4);
        g.setColor(new Color(0x3C, 0x3B, 0x6E));
        g.fillRect(0, 0, cantonW, cantonH);
        g.dispose();
        return img;
    }

    private static BufferedImage drawBrFlag() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x00, 0x97, 0x39));
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(0xFE, 0xDD, 0x00));
        Path2D diamond = new Path2D.Float();
        diamond.moveTo(W / 2f, 1);
        diamond.lineTo(W - 2, H / 2f);
        diamond.lineTo(W / 2f, H - 1);
        diamond.lineTo(2, H / 2f);
        diamond.closePath();
        g.fill(diamond);
        g.setColor(new Color(0x01, 0x21, 0x69));
        float r = H * 0.28f;
        g.fill(new Ellipse2D.Float(W / 2f - r, H / 2f - r, r * 2, r * 2));
        g.dispose();
        return img;
    }
}
