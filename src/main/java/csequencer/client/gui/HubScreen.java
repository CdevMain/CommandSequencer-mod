package csequencer.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import csequencer.CommandSequencerMod;
import csequencer.client.ClientNet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Home screen: only the big buttons, no top bar. Each button is an image you can replace:
 * {@code assets/csequencer/textures/gui/home/<name>.png} (any size; 128×96 recommended, 4:3,
 * drawn pixel-perfect). The button's name is drawn under the image.
 */
public final class HubScreen extends CsqScreen {
    private record Card(Tab tab, String image, String title, String tip) {
        ResourceLocation texture() { return new ResourceLocation(CommandSequencerMod.MODID, "textures/gui/home/" + image + ".png"); }
    }

    private static final Card[] CARDS = {
            new Card(Tab.PROJECTS, "projects", "Projects", "Create, open, import and share sequences"),
            new Card(Tab.EDITOR, "editor", "Editor", "Drag pieces onto tracks and build logic"),
            new Card(Tab.LIBRARY, "library", "Pieces", "Every piece, organised by category"),
            new Card(Tab.TASKS, "tasks", "Tasks", "Inspect, pause and stop running tasks"),
            new Card(Tab.MONITOR, "monitor", "Monitor", "All sequences on the server, live"),
            new Card(Tab.HISTORY, "history", "History", "Past runs, failures and their logs"),
            new Card(Tab.SETTINGS, "settings", "Settings", "Overlay, audio, permissions and limits"),
    };
    /** Texture size of the placeholder images (used for UV mapping; real art may be any 4:3 size). */
    private static final int TEX_W = 128, TEX_H = 96, LABEL_H = 14, GAP = 10;

    public HubScreen() { super("Command Sequencer", null); }

    @Override protected Tab tab() { return Tab.HUB; }

    @Override protected boolean showTopBar() { return false; }

    private int cols() { return width >= 620 ? 4 : width >= 330 ? 3 : 2; }

    private int rows() { return (CARDS.length + cols() - 1) / cols(); }

    /** Image width: as big as fits, capped at 2× the texture, kept as whole pixels of the 4:3 art. */
    private int imgW() {
        int byWidth = (width - 20 - (cols() - 1) * GAP) / cols();
        int byHeight = (int) (((height - 20 - (rows() - 1) * GAP) / (double) rows() - LABEL_H) * 4 / 3);
        int w = Math.min(Math.min(byWidth, byHeight), TEX_W * 2);
        return Math.max(48, w - w % 4);
    }

    private int imgH() { return imgW() * 3 / 4; }

    private int[] pos(int i) {
        int cols = cols(), w = imgW(), h = imgH() + LABEL_H;
        int row = i / cols;
        int inRow = Math.min(cols, CARDS.length - row * cols);
        int rowW = inRow * w + (inRow - 1) * GAP;
        int x = (width - rowW) / 2 + (i % cols) * (w + GAP);
        int gridH = rows() * h + (rows() - 1) * GAP;
        int y = (height - gridH) / 2 + row * (h + GAP);
        return new int[]{x, y};
    }

    @Override
    protected void build() { ClientNet.send("hello", null); }

    @Override
    protected void drawContent(GuiGraphics g, int mx, int my, float pt) {
        int w = imgW(), h = imgH();
        for (int i = 0; i < CARDS.length; i++) {
            Card c = CARDS[i];
            int[] p = pos(i);
            boolean hov = in(mx, my, p[0], p[1], w, h + LABEL_H);
            int lift = hov ? 2 : 0;
            int x = p[0], y = p[1] - lift;
            g.fill(x + 2, y + 3, x + w + 2, y + h + 3, 0x66000000);
            RenderSystem.enableBlend();
            g.blit(c.texture(), x, y, w, h, 0, 0, TEX_W, TEX_H, TEX_W, TEX_H);
            RenderSystem.disableBlend();
            if (hov) {
                g.fill(x - 1, y - 1, x + w + 1, y, 0xFFFFFFFF);
                g.fill(x - 1, y + h, x + w + 1, y + h + 1, 0xFFFFFFFF);
                g.fill(x - 1, y, x, y + h, 0xFFFFFFFF);
                g.fill(x + w, y, x + w + 1, y + h, 0xFFFFFFFF);
                tipArea(p[0], p[1], w, h + LABEL_H, c::tip);
            }
            g.drawCenteredString(font, c.title, x + w / 2, y + h + 4, hov ? Theme.TEXT : Theme.TEXT_DIM);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int b) {
        if (super.mouseClicked(mx, my, b)) return true;
        int w = imgW(), h = imgH() + LABEL_H;
        for (int i = 0; i < CARDS.length; i++) {
            int[] p = pos(i);
            if (in(mx, my, p[0], p[1], w, h)) { navigate(CARDS[i].tab); return true; }
        }
        return false;
    }
}
