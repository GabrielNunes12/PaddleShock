package com.paddleshock.entities;

import com.jme3.asset.AssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Sphere;

import com.paddleshock.GameConstants;

/** The ball: a real mesh (per catalog item) with an XZ velocity, gliding on the table surface. */
public class Ball {

    private final Node node;
    private final Vector3f velocity = new Vector3f();
    private final float radius;
    private final float baseSpeed;
    private final float restitutionMultiplier;
    private final float gravityMultiplier;
    private final float windAccelX;
    private float verticalVelocity;
    /** Sideways spin - see docs/specs/03-spin.md. Positive curves the ball toward +x. */
    private float spin;
    private final Spatial model;

    public Ball(AssetManager assetManager, ColorRGBA color, TextureSet textureSet, BallModel ballModel,
            float speedMultiplier, float sizeMultiplier, float restitutionMultiplier,
            float gravityMultiplier, float windAccelX) {
        this.radius = GameConstants.BALL_RADIUS * sizeMultiplier;
        this.baseSpeed = GameConstants.BALL_BASE_SPEED * speedMultiplier;
        this.restitutionMultiplier = restitutionMultiplier;
        this.gravityMultiplier = gravityMultiplier;
        this.windAccelX = windAccelX;

        model = ballModel.getPath() != null
                ? assetManager.loadModel(ballModel.getPath())
                : new Geometry("ballShape", new Sphere(16, 16, 1f));

        if (ballModel.isTinted()) {
            Material material = TexturedMaterials.create(assetManager, textureSet.getColorMap(),
                    textureSet.getNormalMap(), color);
            model.depthFirstTraversal(spatial -> {
                if (spatial instanceof Geometry geometry) {
                    geometry.setMaterial(material);
                }
            });
            TexturedMaterials.generateTangents(model);
        }

        // Found models carry their own baked-in scale from the source file, so size them by their
        // actual measured bounds rather than assuming the raw mesh data is exactly unit-radius.
        model.updateModelBound();
        BoundingVolume bound = model.getWorldBound();
        float nativeRadius = bound instanceof BoundingBox box
                ? Math.max(box.getXExtent(), Math.max(box.getYExtent(), box.getZExtent()))
                : 1f;
        model.setLocalScale(radius / nativeRadius);

        node = new Node("ball");
        node.attachChild(model);
        node.setLocalTranslation(0, radius, 0);
    }

    public void launch(float directionZ) {
        float angle = (float) (Math.random() * 0.6 - 0.3);
        velocity.set(
                (float) Math.sin(angle) * baseSpeed,
                0,
                Math.signum(directionZ) * (float) Math.cos(angle) * baseSpeed);
        verticalVelocity = GameConstants.BALL_SERVE_POP;
        spin = 0f;
        node.setLocalTranslation(0, radius, 0);
    }

    /** Spin imparted by a paddle moving sideways at {@code paddleSpeedX} (units/s) at contact:
     *  proportional to the swipe, in the swipe's direction, clamped to {@link GameConstants#SPIN_MAX}. */
    public static float spinFromPaddleSpeed(float paddleSpeedX) {
        float spin = paddleSpeedX * GameConstants.SPIN_PER_PADDLE_SPEED;
        return Math.max(-GameConstants.SPIN_MAX, Math.min(GameConstants.SPIN_MAX, spin));
    }

    public float getSpin() {
        return spin;
    }

    public void setSpin(float spin) {
        this.spin = spin;
    }

    /** Seconds left of the contact "pop" (see {@link #pop}). Purely visual. */
    private float popTimer;
    private static final float POP_SECONDS = 0.14f;
    private static final float POP_SCALE = 0.28f;

    /** A quick swell on contact, so hits read at a glance. Visual only - radius is unchanged. */
    public void pop() {
        popTimer = POP_SECONDS;
    }

    /** Advances the pop swell; call every rendered frame on every client. */
    public void animatePop(float tpf) {
        if (popTimer <= 0f) {
            return;
        }
        popTimer = Math.max(0f, popTimer - tpf);
        float t = popTimer / POP_SECONDS;
        node.setLocalScale(1f + POP_SCALE * t * t);
    }

    /** Turns the model about its vertical axis in proportion to spin - purely visual, so every
     *  client (including a joiner that only renders snapshots) can call it each frame. */
    public void animateSpin(float tpf) {
        if (spin != 0f) {
            model.rotate(0f, spin * GameConstants.SPIN_VISUAL_RATE * tpf, 0f);
        }
    }

    public void update(float tpf) {
        Vector3f position = node.getLocalTranslation();

        verticalVelocity -= GameConstants.BALL_GRAVITY * gravityMultiplier * tpf;
        float newY = position.y + verticalVelocity * tpf;
        if (newY <= radius && verticalVelocity < 0) {
            newY = radius;
            verticalVelocity = -verticalVelocity * bounceRestitution();
            if (Math.abs(verticalVelocity) < GameConstants.BALL_BOUNCE_SETTLE_SPEED) {
                verticalVelocity = 0f;
            }
        }

        velocity.x += windAccelX * tpf;
        velocity.x += spin * GameConstants.SPIN_CURVE_ACCEL * tpf;
        spin *= Math.max(0f, 1f - GameConstants.SPIN_DECAY_PER_SECOND * tpf);
        animateSpin(tpf);

        Vector3f horizontal = velocity.mult(tpf);
        node.setLocalTranslation(position.x + horizontal.x, newY, position.z + horizontal.z);
    }

    /** How much vertical speed survives each table bounce; a bouncier table decays slower. */
    private float bounceRestitution() {
        return Math.min(GameConstants.BALL_BOUNCE_MAX_RESTITUTION,
                GameConstants.BALL_BOUNCE_BASE_RESTITUTION * restitutionMultiplier);
    }

    /** Whether the ball is low enough for a paddle to actually reach it (it can hop over a swing). */
    public boolean isWithinPaddleReach() {
        return node.getLocalTranslation().y <= GameConstants.PADDLE_REACH_HEIGHT;
    }

    public void bounceOffSideRail() {
        Vector3f position = node.getLocalTranslation();
        float maxX = GameConstants.TABLE_HALF_WIDTH - radius;
        float clampedX = Math.max(-maxX, Math.min(maxX, position.x));
        node.setLocalTranslation(clampedX, position.y, position.z);
        velocity.x = -velocity.x * restitutionMultiplier;
        // Reverse and halve spin, or it would keep pushing the ball back into the same rail.
        spin = -spin * 0.5f;
    }

    /** Shield block: puts the ball back on the goal line at {@code goalZ} and sends it back the
     *  way it came, as if it hit a wall. */
    public void reboundFromGoal(float goalZ) {
        Vector3f position = node.getLocalTranslation();
        node.setLocalTranslation(position.x, position.y, goalZ);
        velocity.z = -velocity.z;
    }

    /** Reflects off a paddle, biasing X based on where the ball hit the paddle. */
    public void bounceOffPaddle(Paddle paddle) {
        float offsetX = node.getLocalTranslation().x - paddle.getPosition().x;
        float normalized = offsetX / paddle.getEffectiveRadius();

        float speed = Math.min(
                velocity.length() * GameConstants.BALL_SPEED_RAMP * restitutionMultiplier,
                GameConstants.BALL_MAX_SPEED);
        velocity.z = -velocity.z;
        velocity.x = normalized * speed * 0.6f;

        float newLength = velocity.length();
        if (newLength > 0.0001f) {
            velocity.multLocal(speed / newLength);
        }

        verticalVelocity = GameConstants.PADDLE_POP_BASE + speed * GameConstants.PADDLE_POP_SPEED_FACTOR;
    }

    public Vector3f getPosition() {
        return node.getLocalTranslation();
    }

    public Node getNode() {
        return node;
    }

    public float getRadius() {
        return radius;
    }

    /** Horizontal velocity (X/Z), for a network host to include in its snapshot. */
    public Vector3f getVelocity() {
        return velocity;
    }

    /** Current vertical (bounce) velocity, for a network host to include in its snapshot. */
    public float getVerticalVelocity() {
        return verticalVelocity;
    }

    /** Directly applies a received authoritative state from a network host snapshot; a networked
     *  joiner client never runs its own physics, it only renders whatever the host last sent. */
    public void setNetworkState(float x, float y, float z, float velX, float velZ, float verticalVel) {
        node.setLocalTranslation(x, y, z);
        velocity.set(velX, 0, velZ);
        verticalVelocity = verticalVel;
    }

    /** Same, plus the host's spin (drives the joiner's spin animation only - it runs no physics). */
    public void setNetworkState(float x, float y, float z, float velX, float velZ, float verticalVel, float spin) {
        setNetworkState(x, y, z, velX, velZ, verticalVel);
        this.spin = spin;
    }
}
