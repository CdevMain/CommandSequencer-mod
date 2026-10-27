package csequencer.client.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Decodes WAV (Java Sound) and MP3 (JLayer) to 16-bit little-endian PCM. Call off the render thread. */
public final class AudioDecoder {
    /** Decoded audio. {@code pcm} is interleaved signed 16-bit little-endian. */
    public record Pcm(byte[] pcm, int sampleRate, int channels) {
        public int frameSize() { return channels * 2; }
        public long frames() { return pcm.length / frameSize(); }
        public long durationMs() { return frames() * 1000L / Math.max(1, sampleRate); }
        public AudioFormat format() { return new AudioFormat(sampleRate, 16, channels, true, false); }
    }

    /** Hard cap on decoded size (≈ 25 minutes of 48 kHz stereo). */
    private static final long MAX_PCM = 300L * 1024 * 1024;

    private AudioDecoder() {}

    public static Pcm decode(byte[] data, String format) throws IOException {
        return "mp3".equalsIgnoreCase(format) ? mp3(data) : wav(data);
    }

    private static Pcm wav(byte[] data) throws IOException {
        try (AudioInputStream raw = AudioSystem.getAudioInputStream(new BufferedInputStream(new ByteArrayInputStream(data)))) {
            AudioFormat src = raw.getFormat();
            int ch = Math.max(1, Math.min(2, src.getChannels()));
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16, src.getChannels(), src.getChannels() * 2, src.getSampleRate(), false);
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, raw)) {
                byte[] bytes = readLimited(pcm);
                if (src.getChannels() > 2) bytes = downmix(bytes, src.getChannels());
                return new Pcm(bytes, (int) src.getSampleRate(), ch);
            }
        } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
            throw new IOException("Unsupported WAV encoding: " + e.getMessage());
        }
    }

    private static Pcm mp3(byte[] data) throws IOException {
        Bitstream bs = new Bitstream(new ByteArrayInputStream(data));
        Decoder dec = new Decoder();
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 8);
        int rate = 44100, channels = 2;
        try {
            Header h;
            int frames = 0;
            while ((h = bs.readFrame()) != null) {
                SampleBuffer sb = (SampleBuffer) dec.decodeFrame(h, bs);
                rate = dec.getOutputFrequency();
                channels = dec.getOutputChannels();
                short[] buf = sb.getBuffer();
                int len = sb.getBufferLength();
                for (int i = 0; i < len; i++) {
                    out.write(buf[i] & 0xFF);
                    out.write((buf[i] >> 8) & 0xFF);
                }
                bs.closeFrame();
                if (out.size() > MAX_PCM) throw new IOException("audio too long");
                if (++frames > 2_000_000) break;
            }
        } catch (javazoom.jl.decoder.JavaLayerException e) {
            throw new IOException("MP3 decode error: " + e.getMessage());
        } finally {
            try { bs.close(); } catch (Exception ignored) {}
        }
        if (out.size() == 0) throw new IOException("MP3 contained no audio frames");
        return new Pcm(out.toByteArray(), rate, Math.max(1, Math.min(2, channels)));
    }

    private static byte[] readLimited(AudioInputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_PCM) throw new IOException("audio too long");
        }
        return out.toByteArray();
    }

    private static byte[] downmix(byte[] pcm, int channels) {
        int frames = pcm.length / (channels * 2);
        byte[] out = new byte[frames * 4];
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < 2; c++) {
                int o = (f * channels + c) * 2;
                out[f * 4 + c * 2] = pcm[o];
                out[f * 4 + c * 2 + 1] = pcm[o + 1];
            }
        }
        return out;
    }

    /** RMS amplitude per bin, normalised to [0, 1]. */
    public static float[] waveform(Pcm p, int bins) {
        float[] out = new float[bins];
        long frames = p.frames();
        if (frames == 0 || bins <= 0) return out;
        int fs = p.frameSize();
        double perBin = frames / (double) bins;
        float max = 0;
        for (int b = 0; b < bins; b++) {
            long start = (long) (b * perBin), end = Math.min(frames, (long) ((b + 1) * perBin));
            long step = Math.max(1, (end - start) / 256);
            double sum = 0;
            int n = 0;
            for (long f = start; f < end; f += step) {
                int i = (int) (f * fs);
                short s = (short) ((p.pcm()[i] & 0xFF) | (p.pcm()[i + 1] << 8));
                sum += (double) s * s;
                n++;
            }
            out[b] = n == 0 ? 0 : (float) Math.sqrt(sum / n);
            max = Math.max(max, out[b]);
        }
        if (max > 0) for (int b = 0; b < bins; b++) out[b] /= max;
        return out;
    }
}
