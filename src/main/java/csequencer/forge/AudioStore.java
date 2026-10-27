package csequencer.forge;

import csequencer.core.model.AudioRef;
import csequencer.core.project.FileProjectStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Server-side audio files, content addressed: {@code audio/<sha1>.<wav|mp3>}. */
public final class AudioStore {
    private final Path dir;

    public AudioStore(Path dir) { this.dir = dir; }

    public Path file(String hash, String format) { return dir.resolve(hash + "." + format); }

    public boolean has(String hash) {
        if (!AudioRef.isValidHash(hash)) return false;
        return Files.exists(file(hash, "wav")) || Files.exists(file(hash, "mp3"));
    }

    public Path find(String hash) {
        if (!AudioRef.isValidHash(hash)) return null;
        for (String f : new String[]{"wav", "mp3"}) if (Files.exists(file(hash, f))) return file(hash, f);
        return null;
    }

    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Detects the real format from magic bytes (never trusts the file name). Returns null if unsupported. */
    public static String sniff(byte[] d) {
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'A' && d[10] == 'V' && d[11] == 'E') return "wav";
        if (d.length >= 3 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') return "mp3";
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xE0) == 0xE0) return "mp3";
        return null;
    }

    /** Validates and stores an upload. Returns the format, or throws with a user-facing message. */
    public String save(byte[] data, String claimedHash, String claimedFormat) throws IOException {
        String hash = sha1(data);
        if (!hash.equals(claimedHash)) throw new IOException("checksum mismatch");
        String fmt = sniff(data);
        if (fmt == null) throw new IOException("not a WAV or MP3 file");
        if (claimedFormat != null && !claimedFormat.isEmpty() && !fmt.equals(claimedFormat.toLowerCase(Locale.ROOT)))
            throw new IOException("file content is " + fmt + ", not " + claimedFormat);
        if (!has(hash)) FileProjectStore.writeAtomic(file(hash, fmt), data);
        return fmt;
    }
}
