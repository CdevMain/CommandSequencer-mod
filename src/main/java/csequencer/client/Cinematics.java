package csequencer.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import csequencer.CommandSequencerMod;
import csequencer.client.gui.CsqScreen;
import csequencer.core.json.J;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client side of the camera pieces: cinematic camera (fixed shots, keyframe paths, orbits), forced look,
 * player control locks and the "Screen Effect+" shader. All state comes from server HUD elements
 * (kinds camera / look / controls / effect), so everything is undone when the sequence ends.
 */
public final class Cinematics {
    private Cinematics() {}

    private record Pose(Vec3 pos, float yaw, float pitch, float fov) {}

    private static final class Shot {
        JsonObject d;
        long start;
        Pose from; // pose to blend from (null = none)
    }

    private static final class Effect {
        String name;
        List<String> words = List.of();
        float intensity;
        long start, fadeIn;
        long releaseStart = -1, releaseMs;
        float releaseFrom;

        float level(long now) {
            if (releaseStart >= 0) {
                if (releaseMs <= 0) return 0;
                return releaseFrom * Math.max(0, 1 - (now - releaseStart) / (float) releaseMs);
            }
            return fadeIn <= 0 ? intensity : intensity * Math.min(1, (now - start) / (float) fadeIn);
        }
    }

    private static final Map<String, Shot> shots = new LinkedHashMap<>();
    private static final Map<String, JsonObject> looks = new LinkedHashMap<>();
    private static final Map<String, JsonObject> controls = new LinkedHashMap<>();
    private static final Map<String, Effect> effects = new LinkedHashMap<>();
    private static final Map<String, JsonObject> anims = new LinkedHashMap<>();

    /** Blend back to the player after a shot ends. */
    private static Pose releaseFrom;
    private static long releaseStart, releaseMs;
    private static Pose lastPose;
    private static float lastFov;
    private static boolean lockActive;
    private static float lockYaw, lockPitch;
    private static long lastFrame;

    // ── Messages ───────────────────────────────────────────────────────────

    /** Handles HUD messages of kinds camera / look / controls / effect. */
    public static void onMessage(String kind, String op, String id, JsonObject o) {
        long now = System.currentTimeMillis();
        boolean hide = op.equals("hide");
        switch (kind) {
            case "camera" -> {
                if (hide) {
                    Shot s = shots.remove(id);
                    if (s != null && shots.isEmpty() && lastPose != null) {
                        releaseFrom = lastPose;
                        releaseStart = now;
                        releaseMs = Math.max(0, J.l(o, "releaseMs", 0));
                    }
                    return;
                }
                Shot s = shots.get(id);
                if (s != null && op.equals("update")) { s.d = o; return; }
                s = new Shot();
                s.d = o;
                s.start = now;
                if (J.l(o, "blendMs", 0) > 0) s.from = lastPose != null && (active() || releasing(now)) ? lastPose : null;
                shots.remove(id);
                shots.put(id, s);
                releaseFrom = null;
            }
            case "look" -> { if (hide) looks.remove(id); else looks.put(id, o); }
            case "anim" -> { if (hide) anims.remove(id); else anims.put(id, o); }
            case "controls" -> { if (hide) controls.remove(id); else controls.put(id, o); }
            case "effect" -> {
                Effect e = effects.get(id);
                if (hide) {
                    if (e != null && e.releaseStart < 0) {
                        e.releaseFrom = e.level(now);
                        e.releaseStart = now;
                        e.releaseMs = Math.max(0, J.l(o, "releaseMs", 0));
                    }
                    return;
                }
                if (e == null || e.releaseStart >= 0) {
                    float from = e == null ? 0 : e.level(now);
                    e = new Effect();
                    e.start = now;
                    e.fadeIn = Math.max(0, J.l(o, "fadeInMs", 0));
                    if (from > 0 && e.fadeIn > 0) e.start -= (long) (from / Math.max(0.01, J.d(o, "intensity", 1)) * e.fadeIn);
                    effects.put(id, e);
                }
                e.name = J.str(o, "effect", "");
                List<String> w = new ArrayList<>();
                for (String s : J.str(o, "words", "").split(",")) if (!s.isBlank()) w.add(s.trim());
                e.words = w;
                e.intensity = (float) Math.max(0, Math.min(1, J.d(o, "intensity", 1)));
            }
            default -> {}
        }
    }

    public static void clear() {
        shots.clear();
        looks.clear();
        controls.clear();
        effects.clear();
        anims.clear();
        words.clear();
        releaseFrom = null;
        lastPose = null;
        lockActive = false;
    }

    // ── State helpers ──────────────────────────────────────────────────────

    private static Shot shot() {
        Shot last = null;
        for (Shot s : shots.values()) last = s;
        return last;
    }

    public static boolean active() { return !shots.isEmpty(); }

    private static boolean releasing(long now) { return releaseFrom != null && now - releaseStart < releaseMs; }

    private static boolean control(String key) {
        for (JsonObject c : controls.values()) if (J.b(c, key, false)) return true;
        return false;
    }

    private static boolean cameraLocks() {
        Shot s = shot();
        return s != null && J.b(s.d, "lockPlayer", true);
    }

    private static boolean freezeMovement() { return cameraLocks() || control("movement"); }
    private static boolean freezeJump() { return cameraLocks() || control("jump"); }
    private static boolean lockLook() { return cameraLocks() || control("look"); }
    private static boolean blockActions() { return cameraLocks() || control("actions"); }
    private static boolean blockInventory() { return cameraLocks() || control("inventory") || control("screens"); }
    private static boolean blockScreens() { return control("screens"); }

    private static boolean hideHud() {
        Shot s = shot();
        return (s != null && J.b(s.d, "hideHud", true)) || control("hideHud");
    }

    private static boolean hideHand() { return active() || releasing(System.currentTimeMillis()) || control("hideHand"); }

    private static float effect(String name, long now) {
        float v = 0;
        for (Effect e : effects.values()) if (e.name.equals(name)) v = Math.max(v, e.level(now));
        return v;
    }

    // ── Geometry ───────────────────────────────────────────────────────────

    private static Vec3 vec(JsonObject o, String key) {
        JsonArray a = J.arr(o, key);
        if (a == null || a.size() < 3) return null;
        return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    private static Entity entity(String uuid) {
        Minecraft mc = Minecraft.getInstance();
        if (uuid == null || uuid.isEmpty() || mc.level == null) return null;
        UUID u;
        try {
            u = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (Entity e : mc.level.entitiesForRendering()) if (u.equals(e.getUUID())) return e;
        return null;
    }

    private static Vec3 body(Entity e, float pt) { return e.getPosition(pt).add(0, e.getBbHeight() * 0.6, 0); }

    /** Point the shot looks at (or null to use explicit rotation). */
    private static Vec3 lookTarget(JsonObject d, float pt) {
        Minecraft mc = Minecraft.getInstance();
        return switch (J.str(d, "look", "at_player")) {
            case "at_player" -> body(mc.player, pt);
            case "at_point" -> vec(d, "lookAt");
            case "at_entity" -> {
                Entity e = entity(J.str(d, "entity", ""));
                yield e != null ? body(e, pt) : body(mc.player, pt);
            }
            default -> null;
        };
    }

    private static float[] angles(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        return new float[]{(float) Math.toDegrees(Math.atan2(dz, dx)) - 90f, (float) -Math.toDegrees(Math.atan2(dy, h))};
    }

    private static double ease(String e, double t) {
        t = Math.max(0, Math.min(1, t));
        return switch (e) {
            case "linear" -> t;
            case "ease_in" -> t * t * t;
            case "ease_out" -> 1 - Math.pow(1 - t, 3);
            default -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
        };
    }

    private static double catmull(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    /** Keyframes as [x,y,z,yaw,pitch] with yaw unwrapped so interpolation takes the short way round. */
    private static double[][] keyframes(JsonObject d) {
        JsonArray a = J.arr(d, "points");
        if (a == null) return new double[0][];
        double[][] k = new double[a.size()][];
        Double prevYaw = null;
        for (int i = 0; i < a.size(); i++) {
            JsonArray p = a.get(i).getAsJsonArray();
            double yaw = p.size() >= 5 ? p.get(3).getAsDouble() : Double.NaN, pitch = p.size() >= 5 ? p.get(4).getAsDouble() : Double.NaN;
            if (!Double.isNaN(yaw) && prevYaw != null) yaw = prevYaw + Mth.wrapDegrees(yaw - prevYaw);
            if (!Double.isNaN(yaw)) prevYaw = yaw;
            k[i] = new double[]{p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble(), yaw, pitch};
        }
        return k;
    }

    private static double[] samplePath(double[][] k, double u, boolean smooth) {
        int n = k.length;
        double s = u * (n - 1);
        int i = Math.min(n - 2, Math.max(0, (int) Math.floor(s)));
        double f = s - i;
        double[] out = new double[5];
        for (int c = 0; c < 5; c++) {
            double p1 = k[i][c], p2 = k[i + 1][c];
            if (Double.isNaN(p1) || Double.isNaN(p2)) { out[c] = Double.NaN; continue; }
            if (!smooth) { out[c] = p1 + (p2 - p1) * f; continue; }
            double p0 = i > 0 && !Double.isNaN(k[i - 1][c]) ? k[i - 1][c] : p1 - (p2 - p1);
            double p3 = i + 2 < n && !Double.isNaN(k[i + 2][c]) ? k[i + 2][c] : p2 + (p2 - p1);
            out[c] = catmull(p0, p1, p2, p3, f);
        }
        return out;
    }

    private static Pose eyePose(float pt) {
        LocalPlayer p = Minecraft.getInstance().player;
        return new Pose(p.getEyePosition(pt), p.getViewYRot(pt), p.getViewXRot(pt), 0);
    }

    private static Pose computeShot(Shot s, long now, float pt) {
        JsonObject d = s.d;
        Minecraft mc = Minecraft.getInstance();
        double elapsed = (now - s.start) / 1000.0;
        float fov = (float) J.d(d, "fov", 0);
        Vec3 pos;
        float yaw, pitch;
        String mode = J.str(d, "mode", "fixed");
        switch (mode) {
            case "path" -> {
                double[][] k = keyframes(d);
                if (k.length < 2) return eyePose(pt);
                long dur = Math.max(1, J.l(d, "durationMs", 5000));
                double blend = J.l(d, "blendMs", 0) / 1000.0;
                double t = Math.max(0, elapsed - blend) * 1000 / dur;
                double u = J.b(d, "loop", false) ? t - Math.floor(t) : ease(J.str(d, "easing", "ease_in_out"), t);
                boolean smooth = J.b(d, "smooth", true);
                double[] v = samplePath(k, u, smooth);
                pos = new Vec3(v[0], v[1], v[2]);
                String look = J.str(d, "look", "keyframes");
                Vec3 target = look.equals("keyframes") || look.equals("forward") ? null : lookTarget(d, pt);
                if (target != null) {
                    float[] a = angles(pos, target);
                    yaw = a[0];
                    pitch = a[1];
                } else if (look.equals("keyframes") && !Double.isNaN(v[3])) {
                    yaw = (float) v[3];
                    pitch = (float) v[4];
                } else {
                    double[] ahead = samplePath(k, Math.min(1, u + 0.01), smooth);
                    Vec3 dir = new Vec3(ahead[0], ahead[1], ahead[2]);
                    if (dir.distanceToSqr(pos) < 1e-6) { double[] back = samplePath(k, Math.max(0, u - 0.01), smooth); dir = pos.add(pos.subtract(new Vec3(back[0], back[1], back[2]))); }
                    float[] a = angles(pos, dir);
                    yaw = a[0];
                    pitch = a[1];
                }
            }
            case "orbit" -> {
                Vec3 c = lookTarget(d, pt);
                if (c == null) c = body(mc.player, pt);
                double ang = Math.toRadians(J.d(d, "startAngle", 0) + J.d(d, "speed", 20) * elapsed);
                double r = J.d(d, "radius", 6);
                pos = new Vec3(c.x - Math.sin(ang) * r, c.y + J.d(d, "height", 3), c.z + Math.cos(ang) * r);
                float[] a = angles(pos, c);
                yaw = a[0];
                pitch = a[1];
            }
            default -> {
                pos = vec(d, "pos");
                if (pos == null) return eyePose(pt);
                Vec3 target = lookTarget(d, pt);
                if (target != null) {
                    float[] a = angles(pos, target);
                    yaw = a[0];
                    pitch = a[1];
                } else {
                    yaw = (float) J.d(d, "yaw", 0);
                    pitch = (float) J.d(d, "pitch", 0);
                }
            }
        }
        Pose pose = new Pose(pos, yaw, pitch, fov);
        long blendMs = J.l(d, "blendMs", 0);
        if (blendMs > 0 && now - s.start < blendMs) {
            Pose from = s.from != null ? s.from : eyePose(pt);
            pose = lerp(from, pose, (float) ease("ease_in_out", (now - s.start) / (double) blendMs));
        }
        return pose;
    }

    private static Pose lerp(Pose a, Pose b, float k) {
        float yaw = a.yaw + Mth.wrapDegrees(b.yaw - a.yaw) * k;
        return new Pose(a.pos.lerp(b.pos, k), yaw, Mth.lerp(k, a.pitch, b.pitch), Mth.lerp(k, a.fov, b.fov));
    }

    // ── Camera hook ────────────────────────────────────────────────────────

    private static Method setPosition;
    private static Field detached;
    private static boolean reflectFailed;

    private static boolean reflect() {
        if (setPosition != null) return true;
        if (reflectFailed) return false;
        try {
            setPosition = ObfuscationReflectionHelper.findMethod(Camera.class, "m_90581_", Vec3.class);
            detached = ObfuscationReflectionHelper.findField(Camera.class, "f_90560_");
            return true;
        } catch (RuntimeException e) {
            reflectFailed = true;
            CommandSequencerMod.LOGGER.error("[CSQ] Cinematic camera unavailable: {}", e.toString());
            return false;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onCamera(ViewportEvent.ComputeCameraAngles e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        float pt = (float) e.getPartialTick();
        Shot s = shot();
        Pose pose = null;
        if (s != null) {
            pose = computeShot(s, now, pt);
        } else if (releasing(now)) {
            float k = (float) ease("ease_in_out", (now - releaseStart) / (double) Math.max(1, releaseMs));
            pose = lerp(releaseFrom, eyePose(pt), k);
        } else {
            releaseFrom = null;
        }
        lastPose = pose;
        if (pose != null && reflect()) {
            try {
                setPosition.invoke(e.getCamera(), pose.pos);
                detached.setBoolean(e.getCamera(), true);
            } catch (ReflectiveOperationException ex) {
                reflectFailed = true;
                setPosition = null;
            }
            e.setYaw(pose.yaw);
            e.setPitch(pose.pitch);
            e.setRoll(0);
            lastFov = pose.fov;
        }
        // Effect sway (applies with or without a cinematic camera)
        double t = now / 1000.0;
        float dizzy = effect("dizzy", now), poison = effect("poison", now), insane = effect("insanity", now), sleep = effect("sleep", now);
        if (dizzy + poison + insane + sleep > 0) {
            e.setRoll(e.getRoll() + (float) (Math.sin(t * 1.1) * 7 * dizzy + Math.sin(t * 0.7) * 3 * poison + Math.sin(t * 9.0) * 1.5 * insane + Math.sin(t * 0.5) * 4 * sleep));
            e.setYaw(e.getYaw() + (float) (Math.sin(t * 0.6) * 2.5 * dizzy + Math.sin(t * 13) * insane * 0.6));
            e.setPitch(e.getPitch() + (float) (Math.cos(t * 0.8) * 1.5 * dizzy + Math.sin(t * 0.4) * 3 * sleep));
        }
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov e) {
        long now = System.currentTimeMillis();
        double fov = e.getFOV();
        if (lastPose != null && lastPose.fov > 0 && (active() || releasing(now))) fov = lastPose.fov;
        double t = now / 1000.0;
        float dizzy = effect("dizzy", now), poison = effect("poison", now), insane = effect("insanity", now);
        fov += Math.sin(t * 1.4) * 6 * dizzy + Math.sin(t * 2.0) * 3 * poison + Math.sin(t * 7.0) * 4 * insane;
        if (fov != e.getFOV()) e.setFOV(Math.max(5, Math.min(170, fov)));
    }

    @SubscribeEvent
    public static void onHand(RenderHandEvent e) { if (hideHand()) e.setCanceled(true); }

    @SubscribeEvent
    public static void onHighlight(RenderHighlightEvent.Block e) { if (active()) e.setCanceled(true); }

    @SubscribeEvent
    public static void onOverlay(RenderGuiOverlayEvent.Pre e) {
        ResourceLocation id = e.getOverlay().id();
        if (!"minecraft".equals(id.getNamespace()) || id.getPath().equals("chat_panel")) return;
        if (hideHud() || (active() && id.getPath().equals("crosshair"))) e.setCanceled(true);
    }

    // ── Player control ─────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onMovement(MovementInputUpdateEvent e) {
        Input in = e.getInput();
        if (freezeMovement()) {
            in.up = in.down = in.left = in.right = false;
            in.forwardImpulse = 0;
            in.leftImpulse = 0;
        }
        if (freezeJump()) {
            in.jumping = false;
            in.shiftKeyDown = false;
        }
    }

    @SubscribeEvent
    public static void onInteract(InputEvent.InteractionKeyMappingTriggered e) {
        if (!blockActions()) return;
        e.setCanceled(true);
        e.setSwingHand(false);
    }

    private static void drain(KeyMapping k) { while (k.consumeClick()) { /* swallowed */ } }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (blockActions()) {
            drain(mc.options.keyDrop);
            drain(mc.options.keySwapOffhand);
            drain(mc.options.keyPickItem);
            for (KeyMapping k : mc.options.keyHotbarSlots) drain(k);
        }
        if (blockInventory()) drain(mc.options.keyInventory);
        if (freezeMovement()) mc.player.setSprinting(false);
        // expire finished effects
        long now = System.currentTimeMillis();
        effects.values().removeIf(x -> x.releaseStart >= 0 && now - x.releaseStart >= x.releaseMs);
    }

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Opening e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Screen s = e.getNewScreen();
        if (s == null) return;
        boolean container = s instanceof AbstractContainerScreen<?>;
        boolean inventory = s instanceof InventoryScreen || s instanceof CreativeModeInventoryScreen;
        boolean block = false;
        if (blockInventory() && inventory) block = true;
        if (blockScreens()) {
            if (container) block = true;
            else if (mc.screen == null && !(s instanceof PauseScreen || s instanceof ChatScreen || s instanceof DeathScreen || s instanceof CsqScreen
                    || s instanceof ReceivingLevelScreen || s instanceof ProgressScreen || s instanceof GenericDirtMessageScreen
                    || s instanceof LevelLoadingScreen || s instanceof WinScreen)) block = true;
        }
        if (!block) return;
        e.setCanceled(true);
        if (container && !inventory) mc.tell(() -> { if (mc.player != null) mc.player.closeContainer(); });
    }

    /** Forced look and look lock: applied every frame, after the mouse has turned the player. */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        long now = System.currentTimeMillis();
        long dt = lastFrame == 0 ? 16 : Math.max(1, Math.min(200, now - lastFrame));
        lastFrame = now;
        if (p == null) { lockActive = false; return; }
        JsonObject look = null;
        for (JsonObject l : looks.values()) look = l;
        if (look != null) {
            Vec3 target;
            if (J.str(look, "target", "point").equals("entity")) {
                Entity en = entity(J.str(look, "entity", ""));
                target = en == null ? null : body(en, e.renderTickTime);
            } else {
                target = vec(look, "pos");
            }
            if (target != null) {
                float[] a = angles(p.getEyePosition(e.renderTickTime), target);
                double s = J.d(look, "strength", 1);
                float k = s >= 0.99 ? 1f : (float) (1 - Math.pow(1 - s, dt / 50.0));
                float yaw = p.getYRot() + Mth.wrapDegrees(a[0] - p.getYRot()) * k;
                float pitch = Mth.clamp(p.getXRot() + (a[1] - p.getXRot()) * k, -90, 90);
                setRot(p, yaw, pitch);
                lockYaw = yaw;
                lockPitch = pitch;
                lockActive = true;
                return;
            }
        }
        if (lockLook()) {
            if (!lockActive) {
                lockYaw = p.getYRot();
                lockPitch = p.getXRot();
                lockActive = true;
            }
            setRot(p, lockYaw, lockPitch);
        } else {
            lockActive = false;
        }
    }

    private static void setRot(LocalPlayer p, float yaw, float pitch) {
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.yRotO = yaw;
        p.xRotO = pitch;
        p.setYHeadRot(yaw);
        p.yHeadRotO = yaw;
    }

    // ── Screen Effect+ shader ──────────────────────────────────────────────

    private static ShaderInstance shader;
    private static TextureTarget copy;
    private static boolean shaderFailed;

    public static void onRegisterShaders(RegisterShadersEvent e) {
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), new ResourceLocation(CommandSequencerMod.MODID, "screen_fx"),
                    DefaultVertexFormat.POSITION_TEX), s -> shader = s);
        } catch (IOException ex) {
            CommandSequencerMod.LOGGER.error("[CSQ] Could not load the screen effect shader", ex);
        }
    }

    private static Field lastBlend;
    private static boolean lastBlendFailed;

    private static Object lastBlendMode() {
        if (lastBlend == null && !lastBlendFailed) {
            try {
                lastBlend = ObfuscationReflectionHelper.findField(com.mojang.blaze3d.shaders.BlendMode.class, "f_85499_");
            } catch (RuntimeException e) {
                lastBlendFailed = true;
                CommandSequencerMod.LOGGER.warn("[CSQ] BlendMode.lastApplied not accessible: {}", e.toString());
            }
        }
        try {
            return lastBlend == null ? null : lastBlend.get(null);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static void restoreBlendMode(Object prev) {
        if (lastBlend == null) return;
        try {
            lastBlend.set(null, prev);
        } catch (IllegalAccessException ignored) {
            // keep going: worst case the next draw re-applies its blend mode
        }
    }

    private static final String[] FX = {"dizzy", "sleep", "heat", "cold", "insanity", "poison", "invert", "melt", "grayscale", "blood"};
    private static final String[] UNIFORM = {"Dizzy", "Sleep", "Heat", "Cold", "Insanity", "Poison", "Invert", "Melt", "Gray", "Blood"};

    /** Seconds since the oldest active effect of that name started (drives the blood drips). */
    private static float age(String name, long now) {
        long start = Long.MAX_VALUE;
        for (Effect e : effects.values()) if (e.name.equals(name)) start = Math.min(start, e.start);
        return start == Long.MAX_VALUE ? 0 : (now - start) / 1000f;
    }

    private static boolean anyEffect(long now) {
        for (Effect e : effects.values()) if (e.level(now) > 0.001f) return true;
        return false;
    }

    /** Applied before the GUI (so it also covers the hand), or after the world when the GUI is hidden (F1). */
    @SubscribeEvent
    public static void onGui(RenderGuiEvent.Pre e) { applyEffects(); }

    @SubscribeEvent
    public static void onLevel(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui && mc.screen == null) applyEffects();
    }

    private static void applyEffects() {
        long now = System.currentTimeMillis();
        if (shader == null || shaderFailed || !anyEffect(now)) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        try {
            if (copy == null) copy = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
            else if (copy.width != main.width || copy.height != main.height) copy.resize(main.width, main.height, Minecraft.ON_OSX);
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            Object prevBlendMode = lastBlendMode();
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copy.frameBufferId);
            GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, copy.width, copy.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            main.bindWrite(true);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableBlend();
            shader.safeGetUniform("Time").set((float) ((now % 3_600_000L) / 1000.0));
            shader.safeGetUniform("ScreenSize").set((float) main.width, (float) main.height);
            for (int i = 0; i < FX.length; i++) shader.safeGetUniform(UNIFORM[i]).set(effect(FX[i], now));
            shader.safeGetUniform("BloodTime").set(age("blood", now));
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderTexture(0, copy.getColorTextureId());
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            b.vertex(-1, -1, 0).uv(0, 0).endVertex();
            b.vertex(1, -1, 0).uv(1, 0).endVertex();
            b.vertex(1, 1, 0).uv(1, 1).endVertex();
            b.vertex(-1, 1, 0).uv(0, 1).endVertex();
            BufferUploader.drawWithShader(b.end());
            RenderSystem.depthMask(true);
            if (depth) RenderSystem.enableDepthTest();
            if (blend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();
            // Our shader's (opaque) blend mode became Minecraft's "last applied" one. Put the previous one back,
            // otherwise the next GUI draw re-applies its default blending and overrides custom blend functions
            // (the HUD vignette then paints the whole screen black).
            restoreBlendMode(prevBlendMode);
        } catch (RuntimeException ex) {
            shaderFailed = true;
            CommandSequencerMod.LOGGER.error("[CSQ] Screen effects disabled after an error", ex);
        }
    }

    // ── Insanity words ─────────────────────────────────────────────────────

    private record Word(String text, float x, float y, float scale, float rot, int color, boolean mirror) {}

    private static final List<Word> words = new ArrayList<>();
    private static final List<int[]> bars = new ArrayList<>();
    private static long nextShuffle;
    private static final java.util.Random RNG = new java.util.Random();

    private static List<String> wordList() {
        for (Effect e : effects.values()) if (e.name.equals("insanity") && !e.words.isEmpty()) return e.words;
        return ClientConfig.insanityWords;
    }

    /** Words in disorder all over the screen, reshuffled several times a second, plus glitch bars. */
    @SubscribeEvent
    public static void onGuiPost(RenderGuiEvent.Post e) {
        long now = System.currentTimeMillis();
        float lvl = effect("insanity", now);
        if (lvl <= 0.02f) { words.clear(); return; }
        int w = e.getWindow().getGuiScaledWidth(), h = e.getWindow().getGuiScaledHeight();
        List<String> list = wordList();
        if (list.isEmpty()) return;
        if (now >= nextShuffle) {
            nextShuffle = now + 90 + RNG.nextInt((int) (380 - 250 * lvl));
            words.clear();
            int n = 2 + Math.round(lvl * (6 + RNG.nextInt(6)));
            int[] palette = {0xFFB00000, 0xFFFFFFFF, 0xFF000000, 0xFFFF2020, 0xFFE0E0E0};
            for (int i = 0; i < n; i++) {
                words.add(new Word(list.get(RNG.nextInt(list.size())), RNG.nextFloat() * w, RNG.nextFloat() * h,
                        0.8f + RNG.nextFloat() * (1.5f + 3f * lvl), (RNG.nextFloat() - 0.5f) * 70f * lvl,
                        palette[RNG.nextInt(palette.length)], RNG.nextFloat() < 0.15f * lvl));
            }
            bars.clear();
            int nb = RNG.nextInt(1 + Math.round(4 * lvl));
            for (int i = 0; i < nb; i++) {
                int by = RNG.nextInt(Math.max(1, h)), bh = 1 + RNG.nextInt(6);
                bars.add(new int[]{0, by, w, by + bh, RNG.nextBoolean() ? 0xCC000000 : (0x88000000 | RNG.nextInt(0xFFFFFF))});
            }
        }
        var g = e.getGuiGraphics();
        var font = Minecraft.getInstance().font;
        for (int[] b : bars) g.fill(b[0], b[1], b[2], b[3], b[4]);
        for (Word wd : words) {
            float jx = (RNG.nextFloat() - 0.5f) * 3f * lvl, jy = (RNG.nextFloat() - 0.5f) * 3f * lvl;
            int alpha = (int) (Math.min(1f, 0.35f + 0.65f * lvl) * (0.6f + 0.4f * RNG.nextFloat()) * 255);
            int col = (alpha << 24) | (wd.color & 0xFFFFFF);
            g.pose().pushPose();
            g.pose().translate(wd.x + jx, wd.y + jy, 400);
            g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(wd.rot));
            g.pose().scale(wd.mirror ? -wd.scale : wd.scale, wd.scale, 1);
            int tw = font.width(wd.text);
            g.drawString(font, wd.text, -tw / 2, -4, col, (wd.color & 0xFFFFFF) != 0);
            g.pose().popPose();
        }
    }

    // ── Player body animations (seen by everyone, e.g. through a camera shot) ──

    private static final class Saved {
        float headRot, headRotO, xRot, xRotO, attack, attackO;
    }

    private static Saved saved;

    @SubscribeEvent
    public static void onPlayerPre(RenderPlayerEvent.Pre e) {
        if (anims.isEmpty()) return;
        var p = e.getEntity();
        String uuid = p.getUUID().toString();
        String anim = null;
        float lvl = 0;
        for (JsonObject a : anims.values()) {
            JsonArray ps = J.arr(a, "players");
            if (ps == null) continue;
            for (var x : ps) if (x.getAsString().equals(uuid)) { anim = J.str(a, "anim", "insanity"); lvl = (float) J.d(a, "intensity", 1); }
        }
        if (anim == null) return;
        saved = new Saved();
        saved.headRot = p.yHeadRot;
        saved.headRotO = p.yHeadRotO;
        saved.xRot = p.getXRot();
        saved.xRotO = p.xRotO;
        saved.attack = p.attackAnim;
        saved.attackO = p.oAttackAnim;
        double t = System.currentTimeMillis() / 1000.0 + (p.getUUID().getLeastSignificantBits() & 0xFF) / 37.0;
        var ps = e.getPoseStack();
        float head = p.yBodyRot, pitch = 0;
        switch (anim) {
            case "shiver" -> {
                ps.translate((RNG.nextFloat() - 0.5f) * 0.04f * lvl, 0, (RNG.nextFloat() - 0.5f) * 0.04f * lvl);
                head += (RNG.nextFloat() - 0.5f) * 6 * lvl;
                pitch = 15 + (RNG.nextFloat() - 0.5f) * 6 * lvl;
            }
            case "sway" -> {
                ps.translate(0, 0, 0);
                ps.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) Math.sin(t * 1.6) * 12 * lvl));
                ps.mulPose(com.mojang.math.Axis.XP.rotationDegrees((float) Math.cos(t * 1.1) * 8 * lvl));
                head += (float) Math.sin(t * 0.9) * 35 * lvl;
                pitch = (float) (25 + Math.sin(t * 1.3) * 20) * lvl;
            }
            default -> { // insanity: convulsions, head snapping, arms flailing
                boolean twitch = Math.sin(t * 2.3) > 0.6;
                ps.translate((RNG.nextFloat() - 0.5f) * 0.06f * lvl, (twitch ? RNG.nextFloat() * 0.05f : 0) * lvl, (RNG.nextFloat() - 0.5f) * 0.06f * lvl);
                ps.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) (Math.sin(t * 3.1) * 10 + (twitch ? (RNG.nextFloat() - 0.5f) * 20 : 0)) * lvl));
                ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees((float) Math.sin(t * 0.7) * 25 * lvl));
                head += (float) (Math.sin(t * 9.0) * 45 + (twitch ? (RNG.nextFloat() - 0.5f) * 120 : 0)) * lvl;
                pitch = (float) (Math.sin(t * 6.0) * 45 + Math.sin(t * 1.7) * 20) * lvl;
                float swing = (float) ((t * 2.2) % 1.0);
                p.attackAnim = swing;
                p.oAttackAnim = swing;
            }
        }
        p.yHeadRot = head;
        p.yHeadRotO = head;
        p.setXRot(Mth.clamp(pitch, -90, 90));
        p.xRotO = p.getXRot();
    }

    @SubscribeEvent
    public static void onPlayerPost(RenderPlayerEvent.Post e) {
        Saved s = saved;
        if (s == null) return;
        saved = null;
        var p = e.getEntity();
        p.yHeadRot = s.headRot;
        p.yHeadRotO = s.headRotO;
        p.setXRot(s.xRot);
        p.xRotO = s.xRotO;
        p.attackAnim = s.attack;
        p.oAttackAnim = s.attackO;
    }

    /** Names of the active effects (for debugging / the Settings screen). */
    public static List<String> activeEffects() {
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>();
        Map<String, Float> m = new HashMap<>();
        for (Effect e : effects.values()) m.merge(e.name, e.level(now), Math::max);
        for (var en : m.entrySet()) if (en.getValue() > 0.001f) out.add(en.getKey());
        return out;
    }
}
