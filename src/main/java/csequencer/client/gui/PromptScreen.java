package csequencer.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/** Modal single-line text prompt that returns to its parent. */
public final class PromptScreen extends Screen {
    private final Screen parent;
    private final String message, initial;
    private final Consumer<String> onOk;
    private EditBox input;

    public PromptScreen(Screen parent, String title, String message, String initial, Consumer<String> onOk) {
        super(Component.literal(title));
        this.parent = parent;
        this.message = message;
        this.initial = initial;
        this.onOk = onOk;
    }

    @Override
    protected void init() {
        int w = Math.min(320, width - 40), x = (width - w) / 2, y = height / 2 - 30;
        input = new UiTextField(font, x, y + 18, w, 18, message);
        input.setValue(initial == null ? "" : initial);
        addRenderableWidget(input);
        setInitialFocus(input);
        addRenderableWidget(UiButton.of("OK", UiButton.Style.PRIMARY, x + w - 130, y + 44, 60, 18, this::ok));
        addRenderableWidget(UiButton.of("Cancel", UiButton.Style.GHOST, x + w - 64, y + 44, 64, 18, () -> minecraft.setScreen(parent)));
    }

    private void ok() {
        minecraft.setScreen(parent);
        onOk.accept(input.getValue());
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { ok(); return true; }
        if (key == GLFW.GLFW_KEY_ESCAPE) { minecraft.setScreen(parent); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xC0060810);
        int w = Math.min(320, width - 40), x = (width - w) / 2, y = height / 2 - 30;
        Theme.card(g, x - 10, y - 12, w + 20, 82, Theme.SURFACE, Theme.BORDER_HI);
        g.fill(x - 9, y - 11, x + w + 9, y - 9, Theme.ACCENT);
        g.drawString(font, title, x, y - 3, Theme.TEXT, false);
        g.drawString(font, message, x, y + 7, Theme.MUTED, false);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
