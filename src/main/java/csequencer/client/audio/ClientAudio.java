package csequencer.client.audio;

import com.google.gson.JsonObject;
import csequencer.CommandSequencerMod;
import csequencer.client.ClientConfig;
import csequencer.client.ClientNet;
import csequencer.client.Toasts;
import csequencer.core.json.J;
import csequencer.core.model.AudioRef;
import csequencer.core.project.FileProjectStore;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.sound.sampled.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Client audio. The server owns the clock: it sends play/pause/seek/stop with the authoritative
 * position, plus periodic "sync" heartbeats. Clients start at the position adjusted for the time
 * since the message arrived, and re-seek when drift exceeds {@link #DRIFT_MS}. Missing files are
 * downloaded from the server into a hash-addressed cache.
 */
public final class ClientAudio {
    public static final long DRIFT_MS = 150;
    private static final ExecutorService DECODE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CommandSequencer-Audio");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, AudioDecoder.Pcm> decoded = Collections.synchronizedMap(new LinkedHashMap<>(4, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, AudioDecoder.Pcm> e) { return size() > 4; }
    });
    private static final Map<String, LinePlayer> playing = new ConcurrentHashMap<>();
    /** Sessions waiting for a download/decode: session id → latest command. */
    private static final Map<String, JsonObject> pending = new ConcurrentHashMap<>();
    private static final Set<String> requested = ConcurrentHashMap.newKeySet();

    private ClientAudio() {}

    public static Path cacheDir() { return FMLPaths.GAMEDIR.get().resolve("commandsequencer").resolve("audio_cache"); }

    /** Folder where users drop WAV/MP3 files to import into projects. */
    public static Path importDir() { return FMLPaths.GAMEDIR.get().resolve("commandsequencer").resolve("audio"); }

    public static Path cached(String hash) {
        if (!AudioRef.isValidHash(hash)) return null;
        for (String f : new String[]{"wav", "mp3"}) {
            Path p = cacheDir().resolve(hash + "." + f);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    // ── Server commands ────────────────────────────────────────────────────

    public static void onServerCommand(JsonObject o) {
        String op = J.str(o, "op", "");
        String session = J.str(o, "session", "");
        o.addProperty("_recv", System.currentTimeMillis());
        switch (op) {
            case "play" -> start(o);
            case "stop" -> {
                pending.remove(session);
                LinePlayer p = playing.remove(session);
                if (p != null) p.fadeOutAndStop(J.l(o, "fadeMs", 0));
            }
            case "volume" -> {
                LinePlayer p = playing.get(session);
                if (p != null) p.rampTo(J.d(o, "volume", 1), J.l(o, "fadeMs", 0));
                else if (pending.containsKey(session)) pending.get(session).addProperty("volume", J.d(o, "volume", 1));
            }
            case "pause" -> {
                LinePlayer p = playing.get(session);
                if (p != null) { p.seek(J.l(o, "positionMs", 0)); p.paused = true; }
                else if (pending.containsKey(session)) pending.put(session, o);
                else start(o);
            }
            case "resume" -> {
                LinePlayer p = playing.get(session);
                if (p != null) { p.seek(J.l(o, "positionMs", 0)); p.paused = false; }
                else start(o);
            }
            case "seek", "sync" -> {
                LinePlayer p = playing.get(session);
                if (p == null) { if (!pending.containsKey(session)) start(o); else pending.put(session, o); return; }
                long target = J.l(o, "positionMs", 0);
                if (op.equals("seek") || Math.abs(p.positionMs() - target) > DRIFT_MS) p.seek(target);
                p.paused = J.b(o, "paused", false);
            }
            default -> {}
        }
    }

    private static void start(JsonObject o) {
        String session = J.str(o, "session", "");
        String hash = J.str(o, "hash", "");
        if (!AudioRef.isValidHash(hash)) return;
        LinePlayer old = playing.remove(session);
        if (old != null) old.halt();
        pending.put(session, o);
        Path file = cached(hash);
        if (file == null) {
            if (!ClientConfig.downloadAudio) {
                Toasts.warn("Audio '" + J.str(o, "name", "") + "' not downloaded (disabled in settings)");
                pending.remove(session);
                return;
            }
            if (requested.add(hash)) ClientNet.send("audio.request", ClientNet.obj("hash", hash));
            return;
        }
        String fmt = file.getFileName().toString().endsWith(".mp3") ? "mp3" : "wav";
        DECODE.execute(() -> {
            try {
                AudioDecoder.Pcm pcm = load(hash, file, fmt);
                Minecraft.getInstance().execute(() -> launch(session, pcm));
            } catch (Exception e) {
                pending.remove(session);
                CommandSequencerMod.LOGGER.warn("[CSQ] Audio decode failed: {}", e.getMessage());
                Minecraft.getInstance().execute(() -> Toasts.error("Audio decode failed: " + e.getMessage()));
            }
        });
    }

    private static void launch(String session, AudioDecoder.Pcm pcm) {
        JsonObject o = pending.remove(session);
        if (o == null) return; // stopped while decoding
        long elapsed = J.b(o, "paused", false) || "pause".equals(J.str(o, "op", "")) ? 0 : System.currentTimeMillis() - J.l(o, "_recv", System.currentTimeMillis());
        long pos = J.l(o, "positionMs", 0) + elapsed;
        boolean loop = J.b(o, "loop", false);
        if (!loop && pos >= pcm.durationMs()) return;
        try {
            long endMs = J.l(o, "endMs", -1);
            if (endMs >= 0 && !loop && pos >= endMs) return;
            LinePlayer p = new LinePlayer(session, pcm, J.d(o, "volume", 1), loop);
            p.endMs = endMs;
            long fadeIn = J.l(o, "fadeInMs", 0) - elapsed;
            if (fadeIn > 0) p.fadeIn(J.d(o, "volume", 1), fadeIn);
            p.paused = J.b(o, "paused", false) || "pause".equals(J.str(o, "op", ""));
            p.seek(loop && pcm.durationMs() > 0 ? pos % pcm.durationMs() : pos);
            playing.put(session, p);
            p.start();
        } catch (LineUnavailableException e) {
            Toasts.error("No audio output available: " + e.getMessage());
        }
    }

    public static void onDownloaded(String hash, String format, byte[] data) {
        requested.remove(hash);
        DECODE.execute(() -> {
            try {
                if (!csequencer.forge.AudioStore.sha1(data).equals(hash)) throw new IllegalStateException("checksum mismatch");
                FileProjectStore.writeAtomic(cacheDir().resolve(hash + "." + ("mp3".equals(format) ? "mp3" : "wav")), data);
                Minecraft.getInstance().execute(() -> {
                    for (JsonObject o : new ArrayList<>(pending.values())) if (hash.equals(J.str(o, "hash", ""))) start(o);
                });
            } catch (Exception e) {
                CommandSequencerMod.LOGGER.warn("[CSQ] Audio download rejected: {}", e.getMessage());
            }
        });
    }

    static AudioDecoder.Pcm load(String hash, Path file, String fmt) throws Exception {
        AudioDecoder.Pcm pcm = decoded.get(hash);
        if (pcm != null) return pcm;
        pcm = AudioDecoder.decode(Files.readAllBytes(file), fmt);
        decoded.put(hash, pcm);
        return pcm;
    }

    /** Decodes asynchronously for the editor (waveform, preview). Callback runs on the client thread. */
    public static void decodeAsync(String hash, Consumer<AudioDecoder.Pcm> cb) {
        Path f = cached(hash);
        if (f == null) {
            if (requested.add(hash)) ClientNet.send("audio.request", ClientNet.obj("hash", hash));
            return;
        }
        String fmt = f.getFileName().toString().endsWith(".mp3") ? "mp3" : "wav";
        DECODE.execute(() -> {
            try {
                AudioDecoder.Pcm p = load(hash, f, fmt);
                Minecraft.getInstance().execute(() -> cb.accept(p));
            } catch (Exception e) {
                CommandSequencerMod.LOGGER.warn("[CSQ] decode failed: {}", e.getMessage());
            }
        });
    }

    /** Local preview in the editor (not synchronised; stops on {@link #stopPreview()}). */
    public static void preview(String hash, long fromMs) {
        stopPreview();
        decodeAsync(hash, pcm -> {
            try {
                LinePlayer p = new LinePlayer("preview", pcm, 1, false);
                p.seek(fromMs);
                playing.put("preview", p);
                p.start();
            } catch (LineUnavailableException e) {
                Toasts.error("No audio output available");
            }
        });
    }

    public static void stopPreview() {
        LinePlayer p = playing.remove("preview");
        if (p != null) p.halt();
    }

    public static long previewPositionMs() {
        LinePlayer p = playing.get("preview");
        return p == null ? -1 : p.positionMs();
    }

    public static void stopAll() {
        pending.clear();
        requested.clear();
        for (LinePlayer p : playing.values()) p.halt();
        playing.clear();
    }

    /** Volume of the master × records categories, applied on top of the session volume. */
    static float gameVolume() {
        var o = Minecraft.getInstance().options;
        return (float) (o.getSoundSourceVolume(SoundSource.MASTER) * o.getSoundSourceVolume(SoundSource.RECORDS) * ClientConfig.audioVolume);
    }

    // ── Playback thread ────────────────────────────────────────────────────

    static final class LinePlayer extends Thread {
        private final AudioDecoder.Pcm pcm;
        private final SourceDataLine line;
        private final boolean loop;
        private volatile boolean running = true;
        volatile boolean paused;
        private volatile long seekFrame = -1;
        private long writeFrame;
        /** Volume ramp: from → to over [rampStart, rampStart + rampMs]. */
        private volatile double rampFrom, rampTo;
        private volatile long rampStart, rampMs;
        /** Stop once the current ramp finishes (fade-out). */
        private volatile boolean stopAfterRamp;
        /** Audio position (ms) at which playback stops (trimmed clip); -1 = end of file. */
        volatile long endMs = -1;

        LinePlayer(String session, AudioDecoder.Pcm pcm, double volume, boolean loop) throws LineUnavailableException {
            super("CSQ-Audio-" + session);
            setDaemon(true);
            this.pcm = pcm;
            this.loop = loop;
            this.rampFrom = volume;
            this.rampTo = volume;
            AudioFormat f = pcm.format();
            line = AudioSystem.getSourceDataLine(f);
            line.open(f, (int) (f.getFrameRate() * f.getFrameSize() / 5));
        }

        void seek(long ms) { seekFrame = Math.max(0, ms * pcm.sampleRate() / 1000); }

        long positionMs() {
            long buffered = (line.getBufferSize() - line.available()) / pcm.frameSize();
            long f = seekFrame >= 0 ? seekFrame : Math.max(0, writeFrame - buffered);
            return f * 1000 / pcm.sampleRate();
        }

        double volumeAt(long now) {
            if (rampMs <= 0 || now >= rampStart + rampMs) return rampTo;
            double k = (now - rampStart) / (double) rampMs;
            return rampFrom + (rampTo - rampFrom) * Math.max(0, Math.min(1, k));
        }

        /** Ramps the volume to {@code to} over {@code ms} (0 = immediately). */
        void rampTo(double to, long ms) {
            long now = System.currentTimeMillis();
            rampFrom = volumeAt(now);
            rampTo = to;
            rampStart = now;
            rampMs = Math.max(0, ms);
        }

        void fadeIn(double to, long ms) {
            rampFrom = 0;
            rampTo = to;
            rampStart = System.currentTimeMillis();
            rampMs = Math.max(1, ms);
        }

        /** Fades out then stops; {@code ms <= 0} stops immediately. */
        void fadeOutAndStop(long ms) {
            if (ms <= 0) { halt(); return; }
            rampTo(0, ms);
            stopAfterRamp = true;
        }

        @Override public void run() {
            try {
                line.start();
                int chunk = 1024;
                byte[] buf = new byte[pcm.frameSize() * chunk];
                long total = pcm.frames();
                while (running) {
                    long s = seekFrame;
                    if (s >= 0) {
                        seekFrame = -1;
                        line.flush();
                        writeFrame = Math.min(total, s);
                    }
                    long now = System.currentTimeMillis();
                    if (stopAfterRamp && now >= rampStart + rampMs) break;
                    if (paused) { Thread.sleep(10); continue; }
                    long endFrame = endMs >= 0 && !loop ? Math.min(total, endMs * pcm.sampleRate() / 1000) : total;
                    if (writeFrame >= endFrame) {
                        if (loop && total > 0) { writeFrame = 0; continue; }
                        line.drain();
                        break;
                    }
                    int frames = (int) Math.min(chunk, endFrame - writeFrame);
                    int bytes = frames * pcm.frameSize();
                    System.arraycopy(pcm.pcm(), (int) (writeFrame * pcm.frameSize()), buf, 0, bytes);
                    // Software gain with a per-sample linear ramp across the chunk (smooth fades everywhere).
                    double g = gameVolume();
                    double v0 = volumeAt(now) * g;
                    double v1 = volumeAt(now + frames * 1000L / pcm.sampleRate()) * g;
                    if (Math.abs(v0 - 1) > 1e-4 || Math.abs(v1 - 1) > 1e-4) {
                        int ch = pcm.channels();
                        for (int fI = 0; fI < frames; fI++) {
                            double v = v0 + (v1 - v0) * fI / frames;
                            for (int c = 0; c < ch; c++) {
                                int i = (fI * ch + c) * 2;
                                int sample = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                                int scaled = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * v)));
                                buf[i] = (byte) scaled;
                                buf[i + 1] = (byte) (scaled >> 8);
                            }
                        }
                    }
                    line.write(buf, 0, bytes);
                    writeFrame += frames;
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                CommandSequencerMod.LOGGER.warn("[CSQ] Audio playback error: {}", e.getMessage());
            } finally {
                line.stop();
                line.close();
            }
        }

        /** Stops playback and releases the line. */
        void halt() { running = false; interrupt(); }
    }
}
