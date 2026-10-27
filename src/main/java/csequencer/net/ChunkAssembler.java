package csequencer.net;

import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reassembles chunked transfers with strict limits: bounded concurrent transfers, ordered chunks,
 * maximum total size and an idle timeout. Used on both sides.
 */
public final class ChunkAssembler {
    public record Done(int transfer, String kind, String meta, byte[] data) {}

    private static final class Partial {
        final String kind, meta;
        final int total;
        int next;
        long lastMs;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Partial(String kind, String meta, int total) {
            this.kind = kind;
            this.meta = meta;
            this.total = total;
        }
    }

    private final Map<Integer, Partial> partials = new LinkedHashMap<>();
    private final int maxConcurrent;
    private final long timeoutMs;

    public ChunkAssembler(int maxConcurrent, long timeoutMs) {
        this.maxConcurrent = maxConcurrent;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Accepts a chunk. Returns the completed transfer when the last chunk arrives, null otherwise.
     * Throws IllegalArgumentException for protocol violations (the transfer is discarded).
     */
    public synchronized Done accept(int transfer, String kind, int index, int total, String meta, byte[] data, int maxBytes) {
        long now = System.currentTimeMillis();
        for (Iterator<Partial> it = partials.values().iterator(); it.hasNext(); ) if (now - it.next().lastMs > timeoutMs) it.remove();
        if (total <= 0 || total > 100_000 || index < 0 || index >= total) throw new IllegalArgumentException("bad chunk index");
        Partial p = partials.get(transfer);
        if (index == 0) {
            if (p != null) partials.remove(transfer);
            if (partials.size() >= maxConcurrent) throw new IllegalArgumentException("too many concurrent transfers");
            p = new Partial(kind, meta, total);
            partials.put(transfer, p);
        }
        if (p == null || !p.kind.equals(kind) || p.total != total || p.next != index) {
            partials.remove(transfer);
            throw new IllegalArgumentException("out-of-order or unknown chunk");
        }
        if (p.bytes.size() + data.length > maxBytes) {
            partials.remove(transfer);
            throw new IllegalArgumentException("transfer exceeds " + maxBytes + " bytes");
        }
        p.bytes.write(data, 0, data.length);
        p.next++;
        p.lastMs = now;
        if (p.next == p.total) {
            partials.remove(transfer);
            return new Done(transfer, p.kind, p.meta, p.bytes.toByteArray());
        }
        return null;
    }

    public synchronized void clear() { partials.clear(); }
}
