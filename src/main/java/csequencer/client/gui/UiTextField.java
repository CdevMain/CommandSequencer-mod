package csequencer.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Themed text field: dark fill, subtle border, accent border when focused. */
public class UiTextField extends EditBox {
    private static final int PAD = 4;

    public UiTextField(Font font, int x, int y, int w, int h, String hint) {
        super(font, x, y, w, h, Component.literal(hint));
        setBordered(false);
        setMaxLength(32000);
        setTextColor(Theme.TEXT);
        setHint(Component.literal(hint).withStyle(s -> s.withColor(Theme.MUTED & 0xFFFFFF)));
    }

    @Override
    public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        if (!isVisible()) return;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        int border = isFocused() ? Theme.ACCENT : isHovered() ? Theme.BORDER_HI : Theme.BORDER;
        g.fill(x + 1, y, x + w - 1, y + h, border);
        g.fill(x, y + 1, x + w, y + h - 1, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF0D111A);
        g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        g.pose().pushPose();
        g.pose().translate(PAD, (h - 8) / 2f, 0);
        super.renderWidget(g, mx - PAD, my, pt);
        g.pose().popPose();
        g.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!isVisible()) return false;
        boolean inside = mx >= getX() && mx < getX() + getWidth() && my >= getY() && my < getY() + getHeight();
        if (!inside) return super.mouseClicked(mx, my, button);
        // Shift the click into the padded text area so the cursor lands where the user clicked.
        return super.mouseClicked(Math.max(getX(), mx - PAD), my, button);
    }

    @Override
    public int getInnerWidth() { return getWidth() - PAD * 2; }
}
