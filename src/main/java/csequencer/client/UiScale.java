package csequencer.client;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Gives Command Sequencer screens enough room. With a large GUI scale (e.g. 1080p on "Auto") a screen is
 * only ~480 GUI pixels wide and the editors overflow, so while one of our screens is open the GUI scale is
 * lowered until the screen is at least {@link #minWidth()} wide. The player's own setting is restored as
 * soon as any other screen (or the game) is shown. The HUD is never affected.
 */
public final class UiScale {
    private UiScale() {}

    /** Implemented by every Command Sequencer screen. */
    public interface Scaled {}

    public static final String[] MODES = {"Auto (roomy)", "Same as Minecraft", "Compact (more space)"};

    private static int minWidth() { return ClientConfig.uiSize == 2 ? 860 : 640; }

    private static int minHeight() { return ClientConfig.uiSize == 2 ? 460 : 330; }

    private static int normalScale(Minecraft mc) {
        return mc.getWindow().calculateScale(mc.options.guiScale().get(), mc.isEnforceUnicode());
    }

    /** The scale our screens should use on this window. */
    private static int targetScale(Minecraft mc) {
        Window w = mc.getWindow();
        int s = normalScale(mc);
        if (ClientConfig.uiSize == 1) return s;
        while (s > 1 && (w.getWidth() / s < minWidth() || w.getHeight() / s < minHeight())) s--;
        return s;
    }

    /** Applies our scale; returns true when it changed. */
    public static boolean apply(Minecraft mc) {
        int s = targetScale(mc);
        if ((int) mc.getWindow().getGuiScale() == s) return false;
        mc.getWindow().setGuiScale(s);
        return true;
    }

    /** Puts the player's GUI scale back (and re-lays-out the current screen). */
    public static void restore(Minecraft mc) {
        if ((int) mc.getWindow().getGuiScale() == normalScale(mc)) return;
        mc.resizeDisplay();
    }

    @SubscribeEvent
    public static void onOpening(ScreenEvent.Opening e) {
        Minecraft mc = Minecraft.getInstance();
        // setScreen() initialises the new screen with the window's scaled size right after this event.
        if (e.getNewScreen() instanceof Scaled) apply(mc);
        else if (mc.getWindow().getGuiScale() != normalScale(mc)) mc.getWindow().setGuiScale(normalScale(mc));
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing e) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> { if (!(mc.screen instanceof Scaled)) restore(mc); });
    }

    /** Called by our screens when the window is resized (Minecraft recomputes the player's scale first). */
    public static int[] onResize(Minecraft mc) {
        apply(mc);
        return new int[]{mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()};
    }
}
