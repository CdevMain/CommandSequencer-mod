package csequencer.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayDeque;
import java.util.Deque;

/** Small notification toasts (right side, below the overlay area). Rendered over screens too. */
public final class Toasts {
    private record Toast(String msg, int color, long at) {}

    private static final Deque<Toast> queue = new ArrayDeque<>();
    private static final int SHOW_MS = 4000, FADE_MS = 600;

    private Toasts() {}

    private static String lastMsg = "";
    private static long lastMsgAt;

    public static void push(String msg, String level) {
        long now = System.currentTimeMillis();
        if (msg != null && msg.equals(lastMsg) && now - lastMsgAt < 4000) {
            // Same message again within 4 s: refresh the existing toast instead of stacking another one.
            for (var it = queue.descendingIterator(); it.hasNext(); ) {
                Toast t = it.next();
                if (t.msg.equals(msg.length() > 160 ? msg.substring(0, 159) + "…" : msg)) {
                    queue.remove(t);
                    queue.addLast(new Toast(t.msg, t.color, now));
                    break;
                }
            }
            lastMsgAt = now;
            return;
        }
        lastMsg = msg == null ? "" : msg;
        lastMsgAt = now;
        int color = switch (level == null ? "" : level) {
            case "error" -> 0xFFE57373;
            case "warn" -> 0xFFFFD54F;
            case "success" -> 0xFF81C784;
            default -> 0xFF4FC3F7;
        };
        if (msg == null || msg.isEmpty()) return;
        if (queue.size() >= 6) queue.pollFirst();
        queue.addLast(new Toast(msg.length() > 160 ? msg.substring(0, 159) + "…" : msg, color, System.currentTimeMillis()));
    }

    public static void info(String m) { push(m, "info"); }
    public static void success(String m) { push(m, "success"); }
    public static void error(String m) { push(m, "error"); }
    public static void warn(String m) { push(m, "warn"); }

    public static void render(GuiGraphics g, int screenW) {
        if (queue.isEmpty()) return;
        var font = Minecraft.getInstance().font;
        long now = System.currentTimeMillis();
        queue.removeIf(t -> now - t.at > SHOW_MS);
        int y = 40;
        for (Toast t : queue) {
            long age = now - t.at;
            float alpha = age > SHOW_MS - FADE_MS ? 1f - (age - (SHOW_MS - FADE_MS)) / (float) FADE_MS : 1f;
            int a = Math.max(8, (int) (alpha * 230)) << 24;
            int w = Math.min(260, font.width(t.msg) + 12);
            int x = screenW - w - 6;
            g.pose().pushPose();
            g.pose().translate(0, 0, 500);
            g.fill(x, y, x + w, y + 16, a | 0x101622);
            g.fill(x, y, x + 2, y + 16, (a & 0xFF000000) | (t.color & 0xFFFFFF));
            g.drawString(font, font.plainSubstrByWidth(t.msg, w - 8), x + 6, y + 4, (a & 0xFF000000) | 0xEEEEEE, false);
            g.pose().popPose();
            y += 19;
        }
    }
}
