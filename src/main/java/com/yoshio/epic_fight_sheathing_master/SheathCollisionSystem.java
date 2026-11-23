/*
 * Copyright (C) 2026 Yoshio255
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.yoshio.epic_fight_sheathing_master;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import yesman.epicfight.gameasset.EpicFightSounds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

// Detects a rendered sheath (tachi/katana/sword/longsword — dagger is
// excluded, it sits flush against the thigh with nothing protruding from the
// body) clipping into a block, and plays a short clank sound the moment
// contact starts. Client-side only, purely cosmetic: each client that can
// see the collision independently detects and plays it locally — no network
// sync needed, same as how vanilla's own footstep sounds work per-observer.
public class SheathCollisionSystem {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Sentinel "never happened yet" tick value for throttle checks below.
    // MUST NOT be Long.MIN_VALUE: `currentTick - NEVER` is computed directly
    // and Long.MIN_VALUE makes that subtraction overflow (wrap around to a
    // negative number), which made every `(now - last) >= threshold` check
    // silently false forever — the actual root cause of the debug log and
    // the sound cooldown both never firing, confirmed by comparing an
    // unconditional call counter (which did log every call, proving this
    // method runs constantly) against these gated logs (which never fired
    // even while the unconditional counter confirmed debugEnabled == true).
    private static final long NEVER = -1_000_000L;

    private static long lastLogTick = NEVER;
    private static long lastEntryLogTick = NEVER;

    // Unconditional call counter — NOT gated behind debugEnabled at all, so
    // it can prove whether checkAndPlaySound is even being reached, decoupled
    // from any possible visibility issue on the (non-volatile) debugEnabled
    // flag itself being read from a different thread than it's written on.
    private static final AtomicLong callCount = new AtomicLong(0);

    // Minimum ticks between sound triggers for the same sheath instance, so
    // holding a sheath against a wall doesn't spam the sound every frame.
    private static final long COOLDOWN_TICKS = 20;

    // volatile: toggled from the command-execution thread (server thread,
    // even in singleplayer), read from the render thread every frame — a
    // plain field here would have no visibility guarantee across threads.
    private static volatile boolean debugEnabled = false;

    // Edge-detection + cooldown state, keyed by "<entityUUID>:<sheathTag>" so
    // multiple simultaneous sheaths on the same player (e.g. dual-wielded
    // swords) don't interfere with each other.
    private static final Map<String, Boolean> wasColliding = new HashMap<>();
    private static final Map<String, Long> lastSoundTick = new HashMap<>();

    // Boxes queued this frame for debug visualization, drained and drawn by
    // SheathCollisionDebugRenderer on a separate render pass.
    private static final List<AABB> debugBoxesThisFrame = new ArrayList<>();

    // Inverse of the camera-only rotation baked into the shared level
    // PoseStack this frame, captured once per frame (SheathCollisionDebugRenderer,
    // RenderLevelStageEvent.Stage.AFTER_SKY — the earliest stage, firing
    // before any entity renders) directly from the actual matrix Minecraft
    // used, rather than reconstructed from camera.getYRot()/getXRot() by
    // hand. A hand-rolled yaw/pitch/roll reconstruction was tried first and
    // was subtly wrong (the debug box tracked the player's position but not
    // its rotation correctly when the body turned) — capturing and inverting
    // the real matrix removes any chance of an order/sign/roll mismatch.
    // Only ever read/written on the render thread, so a plain field is fine.
    private static Matrix4f cameraRotationInverse = new Matrix4f();

    public static void captureCameraRotation(PoseStack levelPoseStack) {
        cameraRotationInverse = new Matrix4f(levelPoseStack.last().pose()).invert();
    }

    public static void toggleDebug() {
        debugEnabled = !debugEnabled;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static synchronized List<AABB> drainDebugBoxes() {
        List<AABB> copy = new ArrayList<>(debugBoxesThisFrame);
        debugBoxesThisFrame.clear();
        return copy;
    }

    /**
     * Checks the sheath's current render position (postStack's accumulated
     * transform, at the point this is called — i.e. right before the sheath
     * item itself is rendered) for a block collision, and plays a clank
     * sound on the transition from clear to colliding. entity/sheathTag
     * together identify which sheath instance this is, for the per-instance
     * cooldown/edge-detection state.
     */
    /**
     * Which idle hotbar slot the weapon being drawn comes from (set by the idle-weapon render loop, reset to 0 after
     * it). Part of the per-instance edge-detection key, so several weapons with the same tag drawn in one frame
     * (all idle weapons are shown, also several of one kind) don't overwrite each other's state.
     */
    public static int instanceSlot = 0;

    public static void checkAndPlaySound(Entity entity, String sheathTag, PoseStack postStack) {
        // Unconditional — logs on call #1, then every 300th call (~5s at 60fps
        // for one sheath), completely independent of debugEnabled.
        long calls = callCount.incrementAndGet();
        if (calls == 1 || calls % 300 == 0) {
            LOGGER.debug("[SheathDebug] checkAndPlaySound call #{} tag={} debugEnabled={}", calls, sheathTag, debugEnabled);
        }

        if (debugEnabled) {
            long nowTickEntry = entity.level().getGameTime();
            if (nowTickEntry - lastEntryLogTick >= 10) {
                lastEntryLogTick = nowTickEntry;
                LOGGER.info("[SheathDebug] checkAndPlaySound ENTERED, tag={}", sheathTag);
            }
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        SheathHitboxProfile profile = SheathHitboxProfile.REGISTRY.get(sheathTag);
        if (profile == null) return;

        int count = profile.count;
        List<AABB> boxes = new ArrayList<>(count);
        Vec3 firstWorldPos = null;
        Vec3 collidingPos = null;
        boolean anyColliding = false;
        try {
            // Same for every box this call — copy/look up once, not per box.
            Matrix4f mat = new Matrix4f(postStack.last().pose());
            Vec3 camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            for (int i = 0; i < count; i++) {
                Vec3 worldPos = localPointToWorldPos(mat, camPos,
                        profile.offsetX + profile.stepX * i,
                        profile.offsetY + profile.stepY * i,
                        profile.offsetZ + profile.stepZ * i);
                if (i == 0) firstWorldPos = worldPos;
                AABB box = new AABB(
                        worldPos.x - profile.halfSizeX, worldPos.y - profile.halfSizeY, worldPos.z - profile.halfSizeZ,
                        worldPos.x + profile.halfSizeX, worldPos.y + profile.halfSizeY, worldPos.z + profile.halfSizeZ);
                boxes.add(box);
                if (!level.noCollision(box)) {
                    anyColliding = true;
                    if (collidingPos == null) collidingPos = worldPos;
                }
            }
        } catch (Exception e) {
            if (debugEnabled) {
                LOGGER.error("[SheathDebug] localPointToWorldPos threw for tag={}", sheathTag, e);
            }
            return;
        }

        if (debugEnabled) {
            synchronized (SheathCollisionSystem.class) {
                debugBoxesThisFrame.addAll(boxes);
            }
        }

        boolean colliding = anyColliding;
        String key = entity.getUUID() + ":" + sheathTag + ":" + instanceSlot;
        boolean prevColliding = wasColliding.getOrDefault(key, false);
        wasColliding.put(key, colliding);

        if (debugEnabled) {
            long nowTick = level.getGameTime();
            if (nowTick - lastLogTick >= 10) {
                lastLogTick = nowTick;
                Vec3 entityPos = entity.position();
                Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
                LOGGER.info("[SheathDebug] tag={} entityPos=({}, {}, {}) computedWorldPos=({}, {}, {}) delta=({}, {}, {}) camPos=({}, {}, {}) colliding={}",
                        sheathTag,
                        String.format("%.3f", entityPos.x), String.format("%.3f", entityPos.y), String.format("%.3f", entityPos.z),
                        String.format("%.3f", firstWorldPos.x), String.format("%.3f", firstWorldPos.y), String.format("%.3f", firstWorldPos.z),
                        String.format("%.3f", firstWorldPos.x - entityPos.x), String.format("%.3f", firstWorldPos.y - entityPos.y), String.format("%.3f", firstWorldPos.z - entityPos.z),
                        String.format("%.3f", camera.getPosition().x), String.format("%.3f", camera.getPosition().y), String.format("%.3f", camera.getPosition().z),
                        colliding);
            }
        }

        if (colliding && !prevColliding) {
            if (debugEnabled) {
                Vec3 entityPos = entity.position();
                net.minecraft.core.BlockPos hitBlockPos = net.minecraft.core.BlockPos.containing(collidingPos);
                LOGGER.info("[SheathDebug] COLLISION START tag={} hitPos=({}, {}, {}) block={} onGround={} entityPos=({}, {}, {}) delta=({}, {}, {})",
                        sheathTag,
                        String.format("%.3f", collidingPos.x), String.format("%.3f", collidingPos.y), String.format("%.3f", collidingPos.z),
                        level.getBlockState(hitBlockPos).getBlock(),
                        entity.onGround(),
                        String.format("%.3f", entityPos.x), String.format("%.3f", entityPos.y), String.format("%.3f", entityPos.z),
                        String.format("%.3f", collidingPos.x - entityPos.x), String.format("%.3f", collidingPos.y - entityPos.y), String.format("%.3f", collidingPos.z - entityPos.z));
            }
            long now = level.getGameTime();
            long last = lastSoundTick.getOrDefault(key, NEVER);
            if (Config.collisionSoundEnabled && now - last >= COOLDOWN_TICKS) {
                lastSoundTick.put(key, now);
                level.playLocalSound(collidingPos.x, collidingPos.y, collidingPos.z,
                        SoundEvents.WOOD_STEP, SoundSource.PLAYERS, 0.5f, 1.0f, false);
                // Very faint layer of Epic Fight's own weapon-clash metal sound
                // on top of the footstep, for a subtle "blade guarding" tinge.
                level.playLocalSound(collidingPos.x, collidingPos.y, collidingPos.z,
                        EpicFightSounds.CLASH.get(), SoundSource.PLAYERS, 0.005f, 1.0f, false);
            }
        }
    }

    // postStack's current transform, at a sheath render call site, maps
    // model-local coordinates all the way through to "eye space": camera
    // rotation, then camera-relative translation, then the entity's own
    // world position + body rotation, then bone transforms — all baked in by
    // the normal entity-render pipeline before this method ever runs. Undo
    // just the camera-rotation part (cameraRotationInverse, captured fresh
    // this frame from the real matrix — see its field comment) and add the
    // camera position back to recover the true world position, leaving the
    // entity's own position/rotation contribution untouched. (lx, ly, lz) is
    // a point in the SAME local space as the render origin (0,0,0), so it
    // rotates with the sheath rather than being a fixed world-space shift.
    /** World position of a point given in the local frame of {@code postStack}'s current pose (client, during rendering). */
    public static Vec3 worldPosOf(com.mojang.blaze3d.vertex.PoseStack postStack, float lx, float ly, float lz) {
        Vec3 camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return localPointToWorldPos(new Matrix4f(postStack.last().pose()), camPos, lx, ly, lz);
    }

    private static Vec3 localPointToWorldPos(Matrix4f mat, Vec3 camPos, float lx, float ly, float lz) {
        Vector4f point = new Vector4f(lx, ly, lz, 1f);
        mat.transform(point);

        Vector4f relative = new Vector4f(point);
        cameraRotationInverse.transform(relative);

        return new Vec3(camPos.x + relative.x(), camPos.y + relative.y(), camPos.z + relative.z());
    }
}
