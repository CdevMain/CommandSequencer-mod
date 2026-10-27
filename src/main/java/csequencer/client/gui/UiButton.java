package csequencer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Flat, theme-coloured button. Extends vanilla Button so focus, narration and keyboard work as usual. */
public class UiButton extends Button {
    public enum Style { DEFAULT, PRIMARY, GHOST, DANGER, TAB, TOOL, CHIP }

    private Style style;
    private boolean selected;
    private int accent = Theme.ACCENT;

    public UiButton(int x, int y, int w, int h, String label, Style style, OnPress onPress) {
        super(x, y, w, h, Component.literal(label), onPress, DEFAULT_NARRATION);
        this.style = style;
    }

    public static UiButton of(String label, Style style, int x, int y, int w, int h, Runnable action) {
        return new UiButton(x, y, w, h, label, style, b -> action.run());
    }

    public UiButton tip(String text) {
        if (text != null && !text.isEmpty()) setTooltip(Tooltip.create(Component.literal(text)));
        return this;
    }

    public UiButton selected(boolean s) { this.selected = s; return this; }

    public UiButton accent(int color) { this.accent = color; return this; }

    public UiButton style(Style s) { this.style = s; return this; }

    public boolean isSelected() { return selected; }

    public void setLabel(String s) { setMessage(Component.literal(s)); }

    /** Width that fits a label for this style. */
    public static int fit(String label) { return Minecraft.getInstance().font.width(label) + 14; }

    @Override
    protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hov = active && isHoveredOrFocused();
        int fill, border, text;
        switch (style) {
            case PRIMARY -> {
                fill = !active ? Theme.SURFACE_2 : hov ? Theme.ACCENT_HI : accent;
                border = fill;
                text = active ? 0xFFFFFFFF : Theme.MUTED;
            }
            case DANGER -> {
                fill = hov ? 0xFF5A1E26 : 0xFF3A1A20;
                border = hov ? Theme.ERR : 0xFF5A2A32;
                text = active ? 0xFFFFB4B4 : Theme.MUTED;
            }
            case GHOST, TOOL -> {
                fill = selected ? Theme.SURFACE_3 : hov ? 0x22FFFFFF : 0;
                border = selected ? Theme.BORDER_HI : hov ? Theme.BORDER : 0;
                text = !active ? Theme.MUTED : selected || hov ? Theme.TEXT : Theme.TEXT_DIM;
            }
            case TAB -> {
                fill = hov && !selected ? 0x14FFFFFF : 0;
                border = 0;
                text = selected ? Theme.TEXT : hov ? Theme.TEXT : Theme.TEXT_DIM;
            }
            case CHIP -> {
                fill = selected ? (accent & 0x00FFFFFF) | 0x44000000 : hov ? Theme.SURFACE_3 : Theme.SURFACE_2;
                border = selected ? accent : Theme.BORDER;
                text = selected ? Theme.TEXT : Theme.TEXT_DIM;
            }
            default -> {
                fill = !active ? Theme.SURFACE : hov ? Theme.SURFACE_3 : Theme.SURFACE_2;
                border = hov ? Theme.BORDER_HI : Theme.BORDER;
                text = active ? Theme.TEXT : Theme.MUTED;
            }
        }
        if (border != 0) {
            g.fill(x + 1, y, x + w - 1, y + h, border);
            g.fill(x, y + 1, x + w, y + h - 1, border);
        }
        if (fill != 0) g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        if (style == Style.TAB && selected) g.fill(x + 2, y + h - 2, x + w - 2, y + h, Theme.ACCENT);
        String label = Theme.clip(font, getMessage().getString(), w - 6);
        int tx = x + (w - font.width(label)) / 2;
        g.drawString(font, label, tx, y + (h - 8) / 2 + (style == Style.TAB ? -1 : 0), text, false);
    }
}
