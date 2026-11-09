package com.lightning.northstar.particle;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SimpleAnimatedParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class ThrusterPlumeParticle extends SimpleAnimatedParticle {

    private static final float COLLISION_SPEED_RETENTION = 0.9f;
    private static final double COLLISION_DETECTION_EPSILON = 0.001;
    private static final float COLLISION_PERPENDICULAR_DAMPEN = 0.1f;
    private static final Vec3 AXIS_X = new Vec3(1, 0, 0);
    private static final Vec3 AXIS_Y = new Vec3(0, 1, 0);
    private static final Vec3 AXIS_Z = new Vec3(0, 0, 1);

    private static final int PLUME_SPRITE_COUNT = 6;
    private static final int SMOKE_SPRITE_COUNT = 10;
    private static final int TOTAL_SPRITE_COUNT = PLUME_SPRITE_COUNT + SMOKE_SPRITE_COUNT;
    private static final int BASE_SMOKE_TRANSITION_AGE = 20;

    private enum ParticleState {
        PLUME, SMOKE
    }

    private final SpriteSet spriteSet;
    private ParticleState currentState;
    private float currentSpeedMultiplier;
    private float currentFriction;

    private final int smokeTransitionAge;
    private final float smokeLift;
    private final Vec3 spreadDirection;
    private final float spreadMagnitude;
    private boolean hasCollided;
    private double dx;
    private double dy;
    private double dz;
    private float baseSize;

    protected ThrusterPlumeParticle(ClientLevel level, double x, double y, double z,
                                    double dxSource, double dySource, double dzSource,
                                    SpriteSet spriteSet) {
        super(level, x, y, z, spriteSet, 0);
        this.spriteSet = spriteSet;

        this.quadSize *= getPlumeBaseQuadSize();
        this.baseSize = this.quadSize;
        this.lifetime = getPlumeBaseLifetime() + random.nextInt(5);
        this.friction = getPlumeFriction();
        this.currentSpeedMultiplier = getPlumeSpeedMultiplier();
        this.currentFriction = getPlumeFriction();
        this.currentState = ParticleState.PLUME;

        this.dx = dxSource + getRandomSpread();
        this.dy = dySource + getRandomSpread();
        this.dz = dzSource + getRandomSpread();
        this.hasPhysics = true;

        Vec3 initialVelocity = new Vec3(dx, dy, dz).normalize();
        Vec3 nonParallel = new Vec3(1, 0, 0);
        if (Math.abs(initialVelocity.dot(nonParallel)) > 0.99)
            nonParallel = new Vec3(0, 1, 0);

        Vec3 u = initialVelocity.cross(nonParallel).normalize();
        Vec3 v = initialVelocity.cross(u).normalize();
        double randomAngle = random.nextDouble() * 2 * Math.PI;
        this.spreadDirection = u.scale(Math.cos(randomAngle)).add(v.scale(Math.sin(randomAngle)));
        this.spreadMagnitude = 0.1f + random.nextFloat() * 0.7f;
        this.smokeTransitionAge = BASE_SMOKE_TRANSITION_AGE + random.nextIntBetweenInclusive(-2, 2);
        this.smokeLift = getSmokeBaseLift() - 0.01f + random.nextFloat() * 0.04f;

        pickSprite();
        setAlpha(1);
    }

    protected float getPlumeSpread() {
        return 0.05f;
    }

    protected float getPlumeBaseQuadSize() {
        return 2;
    }

    protected float getPlumeFriction() {
        return 0.99f;
    }

    protected float getPlumeSpeedMultiplier() {
        return 0.144f;
    }

    protected int getPlumeBaseLifetime() {
        return 40;
    }

    protected float getSmokeSpreadMagnitude() {
        return 0.15f;
    }

    protected float getSmokeFriction() {
        return 0.96f;
    }

    protected float getSmokeBaseLift() {
        return 0.02f;
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;

        if (age++ >= lifetime) {
            remove();
            return;
        }

        double intendedMoveX = dx * currentSpeedMultiplier;
        double intendedMoveY = dy * currentSpeedMultiplier;
        double intendedMoveZ = dz * currentSpeedMultiplier;

        double previousX = x;
        double previousY = y;
        double previousZ = z;

        Vec3 totalMove = new Vec3(intendedMoveX, intendedMoveY, intendedMoveZ);
        double totalDistance = totalMove.length();
        if (totalDistance > 0) {
            int steps = (int) Math.ceil(totalDistance / 0.5);
            Vec3 stepMove = totalMove.scale(1.0 / steps);
            for (int i = 0; i < steps; i++) {
                double stepPreviousX = x;
                double stepPreviousY = y;
                double stepPreviousZ = z;
                move(stepMove.x, stepMove.y, stepMove.z);

                double movedX = x - stepPreviousX;
                double movedY = y - stepPreviousY;
                double movedZ = z - stepPreviousZ;
                if (Math.abs(movedX - stepMove.x) > 1.0E-6 ||
                        Math.abs(movedY - stepMove.y) > 1.0E-6 ||
                        Math.abs(movedZ - stepMove.z) > 1.0E-6)
                    break;
            }
        }

        double actualMoveX = x - previousX;
        double actualMoveY = y - previousY;
        double actualMoveZ = z - previousZ;

        boolean collisionDetected = false;
        Vec3 collisionNormal = null;
        if (onGround) {
            collisionDetected = true;
            collisionNormal = new Vec3(0, 1, 0);
        } else {
            boolean blockedX = Math.abs(intendedMoveX) > COLLISION_DETECTION_EPSILON &&
                    Math.abs(actualMoveX) < Math.abs(intendedMoveX) * 0.95;
            boolean blockedZ = Math.abs(intendedMoveZ) > COLLISION_DETECTION_EPSILON &&
                    Math.abs(actualMoveZ) < Math.abs(intendedMoveZ) * 0.95;
            boolean blockedCeiling = Math.abs(intendedMoveY) > COLLISION_DETECTION_EPSILON && intendedMoveY > 0 &&
                    Math.abs(actualMoveY) < Math.abs(intendedMoveY) * 0.95;

            if (blockedCeiling) {
                collisionDetected = true;
                collisionNormal = new Vec3(0, -1, 0);
            } else if (blockedX) {
                collisionDetected = true;
                collisionNormal = new Vec3(intendedMoveX < 0 ? 1 : -1, 0, 0);
            } else if (blockedZ) {
                collisionDetected = true;
                collisionNormal = new Vec3(0, 0, intendedMoveZ < 0 ? 1 : -1);
            }
        }

        if (collisionDetected && collisionNormal != null)
            resolveCollision(collisionNormal);

        if (currentState == ParticleState.PLUME && age >= smokeTransitionAge) {
            currentState = ParticleState.SMOKE;
            baseSize *= 1.2f;
            friction = getSmokeFriction();
            currentFriction = getSmokeFriction();
        }
        if (currentState == ParticleState.SMOKE)
            dy += smokeLift;

        float progress = (float) age / lifetime;
        if (currentState == ParticleState.PLUME) {
            quadSize = baseSize + (float) Math.pow(progress, 0.8) * 2;
        } else {
            quadSize = baseSize - progress * 2 + 2.5f;
        }

        int preSmokeAge = smokeTransitionAge - 5;
        if (age >= preSmokeAge && !hasCollided) {
            float smokeProgress = (age - preSmokeAge) / (float) (lifetime - preSmokeAge);
            float agedSpread = (0.8f - smokeProgress) * spreadMagnitude;
            dx += spreadDirection.x * getSmokeSpreadMagnitude() * agedSpread;
            dy += spreadDirection.y * getSmokeSpreadMagnitude() * agedSpread;
            dz += spreadDirection.z * getSmokeSpreadMagnitude() * agedSpread;
        }

        dx *= currentFriction;
        dy *= currentFriction;
        dz *= currentFriction;

        pickSprite();
    }

    private void resolveCollision(Vec3 collisionNormal) {
        Vec3 incomingVelocity = new Vec3(dx, dy, dz);
        if (incomingVelocity.normalize().dot(collisionNormal) > -1.0E-5)
            return;

        hasCollided = true;
        double incomingSpeedSq = incomingVelocity.lengthSqr();
        if (incomingSpeedSq <= 1.0E-7) {
            dx *= 0.1;
            dy *= 0.1;
            dz *= 0.1;
            return;
        }

        Vec3 incomingNormalized = incomingVelocity.normalize();
        double dot = incomingNormalized.dot(collisionNormal);
        double angleOfIncidence = Math.acos(Mth.clamp(Math.abs(dot), 0, 1));
        float spreadBlend = (float) Math.cos(angleOfIncidence);
        float slideBlend = (float) Math.sin(angleOfIncidence);

        Vec3 normalComponent = collisionNormal.scale(incomingVelocity.dot(collisionNormal));
        Vec3 tangentialComponent = incomingVelocity.subtract(normalComponent);
        Vec3 desiredNormal = incomingVelocity.dot(collisionNormal) < 0
                ? normalComponent.scale(-COLLISION_PERPENDICULAR_DAMPEN)
                : normalComponent;

        double randomAngle = random.nextDouble() * Math.PI * 2;
        Vec3 axis1;
        Vec3 axis2;
        if (Math.abs(collisionNormal.y) > 0.9) {
            axis1 = AXIS_X;
            axis2 = collisionNormal.cross(axis1).normalize();
        } else {
            axis1 = AXIS_Y;
            axis2 = collisionNormal.cross(axis1).normalize();
        }
        if (axis2.lengthSqr() < 0.1) {
            axis1 = Math.abs(collisionNormal.x) > 0.9 ? AXIS_Z : AXIS_X;
            axis2 = collisionNormal.cross(axis1).normalize();
        }

        Vec3 spreadPlaneDirection = axis1.scale(Math.cos(randomAngle))
                .add(axis2.scale(Math.sin(randomAngle)))
                .normalize();
        Vec3 spreadComponent = spreadPlaneDirection.scale(incomingVelocity.length() * spreadBlend);
        Vec3 desiredTangential = tangentialComponent.scale(slideBlend).add(spreadComponent);
        Vec3 newVelocity = desiredNormal.add(desiredTangential);
        double newVelocityMagnitude = newVelocity.length();

        if (newVelocityMagnitude > 1.0E-5) {
            double speed = incomingVelocity.length() * COLLISION_SPEED_RETENTION;
            dx = newVelocity.x / newVelocityMagnitude * speed;
            dy = newVelocity.y / newVelocityMagnitude * speed;
            dz = newVelocity.z / newVelocityMagnitude * speed;
        } else {
            double speed = incomingVelocity.length() * COLLISION_SPEED_RETENTION * 0.5;
            dx = spreadPlaneDirection.x * speed;
            dy = spreadPlaneDirection.y * speed;
            dz = spreadPlaneDirection.z * speed;
        }
    }

    private void pickSprite() {
        int frameIndex;
        if (currentState == ParticleState.PLUME) {
            float plumeProgress = (float) age / smokeTransitionAge;
            int plumeFrame = (int) (plumeProgress * PLUME_SPRITE_COUNT);
            frameIndex = Mth.clamp(plumeFrame, 0, PLUME_SPRITE_COUNT - 1);
        } else {
            int ageInSmokePhase = age - smokeTransitionAge;
            int smokePhaseDuration = lifetime - smokeTransitionAge;
            int smokeFrame = smokePhaseDuration <= 0 ? 0 : ageInSmokePhase * SMOKE_SPRITE_COUNT / smokePhaseDuration;
            frameIndex = PLUME_SPRITE_COUNT + Mth.clamp(smokeFrame, 0, SMOKE_SPRITE_COUNT - 1);
        }

        setSprite(spriteSet.get(frameIndex, TOTAL_SPRITE_COUNT));
    }

    private float getRandomSpread() {
        return (random.nextFloat() * 2 - 1) * getPlumeSpread();
    }

    @Override
    public @NotNull ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_LIT;
    }

    public static class Factory implements ParticleProvider<ThrusterPlumeParticleData> {

        private final SpriteSet spriteSet;

        public Factory(SpriteSet spriteSet) {
            this.spriteSet = spriteSet;
        }

        @Override
        public Particle createParticle(ThrusterPlumeParticleData data, ClientLevel level,
                                       double x, double y, double z,
                                       double dx, double dy, double dz) {
            return new ThrusterPlumeParticle(level, x, y, z, dx, dy, dz, spriteSet);
        }

    }

}
