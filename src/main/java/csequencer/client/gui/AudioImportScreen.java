package csequencer.client.gui;

import csequencer.client.ClientNet;
import csequencer.client.ClientState;
import csequencer.client.Toasts;
import csequencer.client.audio.AudioDecoder;
import csequencer.client.audio.ClientAudio;
import csequencer.core.json.J;
import csequencer.core.model.AudioRef;
import csequencer.core.model.Ids;
import csequencer.core.project.FileProjectStore;
import csequencer.forge.AudioStore;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Imports a WAV or MP3 from {@code .minecraft/commandsequencer/audio}: the file is decoded locally
 * (validation + duration + waveform), uploaded to the server (content-addressed by SHA-1) and
 * added to the project. No native file dialog is used, so it works on every OS and in fullscreen.
 */
public final class AudioImportScreen extends CsqScreen {
    private final Consumer<AudioRef> onImported;
    private final List<Path> files = new ArrayList<>();
    private ListPane<Path> list;
    private String status = "";
    private boolean busy;

    public AudioImportScreen(Screen parent, Consumer<AudioRef> onImported) {
        super("Import audio", parent);
        this.onImported = onImported;
    }

    @Override protected Tab tab() { return Tab.EDITOR; }

    @Override
    protected void build() {
        scan();
        list = new ListPane<>(14, () -> files, (g, p, x, y, w, h, s, hv) -> {
            long size = 0;
            try { size = Files.size(p); } catch (Exception ignored) {}
            g.drawString(font, Theme.clip(font, p.getFileName().toString(), w - 70), x + 4, y + 3, Theme.TEXT, false);
            String sz = (size / 1024) + " KB";
            g.drawString(font, sz, x + w - font.width(sz) - 4, y + 3, Theme.SUB, false);
        });
        list.empty = "Put .wav or .mp3 files in the folder, then Refresh";
        list.bounds(10, HEADER + 40, width - 20, height - HEADER - 76);
        int y = height - 30;
        btn("Import selected", 10, y, 100, 20, this::importSelected);
        btn("Open folder", 114, y, 80, 20, () -> {
            try { Files.createDirectories(ClientAudio.importDir()); } catch (Exception ignored) {}
            Util.getPlatform().openFile(ClientAudio.importDir().toFile());
        });
        btn("Refresh", 198, y, 60, 20, this::scan);
        btn("Cancel", width - 70, y, 60, 20, () -> minecraft.setScreen(parent));
    }

    private void scan() {
        files.clear();
        try {
            Files.createDirectories(ClientAudio.importDir());
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(ClientAudio.importDir())) {
                for (Path p : ds) {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".wav") || n.endsWith(".mp3")) files.add(p);
                }
            }
        } catch (Exception e) {
            status = "Cannot read folder: " + e.getMessage();
        }
        files.sort(null);
    }

    private void importSelected() {
        Path f = list == null ? null : list.selectedItem();
        if (f == null || busy) return;
        long max = J.l(ClientState.hello, "maxAudioBytes", 32L * 1024 * 1024);
        busy = true;
        status = "Reading " + f.getFileName() + "…";
        CompletableFuture.runAsync(() -> {
            try {
                byte[] data = Files.readAllBytes(f);
                if (data.length > max) throw new IllegalStateException("file is larger than the server limit (" + (max / 1024 / 1024) + " MB)");
                String fmt = AudioStore.sniff(data);
                if (fmt == null) throw new IllegalStateException("not a WAV or MP3 file");
                AudioDecoder.Pcm pcm = AudioDecoder.decode(data, fmt);
                String hash = AudioStore.sha1(data);
                FileProjectStore.writeAtomic(ClientAudio.cacheDir().resolve(hash + "." + fmt), data);
                AudioRef ref = new AudioRef();
                String base = f.getFileName().toString();
                ref.name = base.substring(0, base.lastIndexOf('.'));
                ref.id = Ids.slug(ref.name).toLowerCase(Locale.ROOT) + "_" + hash.substring(0, 6);
                ref.hash = hash;
                ref.format = fmt;
                ref.durationMs = pcm.durationMs();
                ref.sizeBytes = data.length;
                minecraft.execute(() -> {
                    status = "Uploading " + (data.length / 1024) + " KB to the server…";
                    ClientNet.uploadAudio(data, hash, fmt, r -> {
                        busy = false;
                        if (J.b(r, "ok", false)) {
                            Toasts.success("Imported '" + ref.name + "' (" + ref.durationMs / 1000 + "s)");
                            minecraft.setScreen(parent);
                            onImported.accept(ref);
                        } else {
                            status = J.str(r, "msg", "Upload failed");
                        }
                    });
                });
            } catch (Exception e) {
                minecraft.execute(() -> { busy = false; status = "Import failed: " + e.getMessage(); });
            }
        });
    }

    @Override
    protected void drawContent(GuiGraphics g, int mx, int my, float pt) {
        g.drawString(font, "Audio folder: " + ClientAudio.importDir(), 10, HEADER + 10, Theme.SUB, false);
        g.drawString(font, "WAV and MP3 are supported. Files are sent to the server and played on clients in sync.", 10, HEADER + 22, Theme.SUB, false);
        list.render(g, font, mx, my);
        g.drawString(font, Theme.clip(font, status, width - 280), 264, height - 24, status.startsWith("Import failed") || status.contains("rejected") ? Theme.ERR : Theme.TEXT, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int b) { return super.mouseClicked(mx, my, b) || list.click(mx, my) >= 0; }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) { return list.scroll(mx, my, d); }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}
