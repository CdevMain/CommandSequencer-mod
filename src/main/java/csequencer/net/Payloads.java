package csequencer.net;

import com.google.gson.JsonObject;
import csequencer.core.json.J;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** GZIP + JSON helpers with hard decompression limits (protects against zip bombs). */
public final class Payloads {
    private Payloads() {}

    public static byte[] gzip(byte[] raw) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(raw);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Decompresses at most {@code maxBytes}; throws if the payload is larger. */
    public static byte[] gunzip(byte[] data, int maxBytes) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0, n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) throw new IOException("Payload exceeds " + maxBytes + " bytes");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    public static byte[] json(JsonObject o) { return gzip(J.COMPACT.toJson(o).getBytes(StandardCharsets.UTF_8)); }

    public static JsonObject json(byte[] gz, int maxBytes) throws IOException {
        return J.parseObject(new String(gunzip(gz, maxBytes), StandardCharsets.UTF_8));
    }
}
