package csequencer.net;

import csequencer.CommandSequencerMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * The single CommandSequencer channel. Four packet types carry everything:
 * <ul>
 *   <li>{@link C2SAction}: small JSON request (op + payload)</li>
 *   <li>{@link C2SChunk}: one chunk of an upload (project JSON, audio file)</li>
 *   <li>{@link S2CMessage}: JSON message for a topic (overlay, tasks, monitor, ...)</li>
 *   <li>{@link S2CChunk}: one chunk of a download (project JSON, audio file)</li>
 * </ul>
 * Handlers run on the main thread. Server handlers validate everything in {@code ServerNet}.
 */
public final class CSQNetwork {
    public static final String PROTOCOL = "csq2";
    /** Client→server packets are capped by vanilla at 32767 bytes. */
    public static final int MAX_C2S = 30000;
    public static final int MAX_S2C = 900_000;
    public static final int CHUNK_C2S = 24 * 1024;
    public static final int CHUNK_S2C = 256 * 1024;

    public static SimpleChannel CHANNEL;

    private CSQNetwork() {}

    public static void init() {
        CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(CommandSequencerMod.MODID, "main"),
                () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
        int id = 0;
        CHANNEL.messageBuilder(C2SAction.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SAction::encode).decoder(C2SAction::decode).consumerMainThread(C2SAction::handle).add();
        CHANNEL.messageBuilder(C2SChunk.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SChunk::encode).decoder(C2SChunk::decode).consumerMainThread(C2SChunk::handle).add();
        CHANNEL.messageBuilder(S2CMessage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CMessage::encode).decoder(S2CMessage::decode).consumerMainThread(S2CMessage::handle).add();
        CHANNEL.messageBuilder(S2CChunk.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CChunk::encode).decoder(S2CChunk::decode).consumerMainThread(S2CChunk::handle).add();
    }

    public static void toPlayer(ServerPlayer p, Object msg) {
        if (CHANNEL != null && p != null) CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), msg);
    }

    public static void toServer(Object msg) {
        if (CHANNEL != null) CHANNEL.sendToServer(msg);
    }

    // ── Packets ────────────────────────────────────────────────────────────

    public record C2SAction(String op, byte[] data) {
        static void encode(C2SAction m, FriendlyByteBuf b) {
            b.writeUtf(m.op, 64);
            b.writeByteArray(m.data);
        }

        static C2SAction decode(FriendlyByteBuf b) { return new C2SAction(b.readUtf(64), b.readByteArray(MAX_C2S)); }

        static void handle(C2SAction m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) csequencer.forge.ServerNet.onAction(sender, m.op, m.data);
            ctx.get().setPacketHandled(true);
        }
    }

    public record C2SChunk(int transfer, String kind, int index, int total, String meta, byte[] data) {
        static void encode(C2SChunk m, FriendlyByteBuf b) {
            b.writeVarInt(m.transfer);
            b.writeUtf(m.kind, 32);
            b.writeVarInt(m.index);
            b.writeVarInt(m.total);
            b.writeUtf(m.meta, 2048);
            b.writeByteArray(m.data);
        }

        static C2SChunk decode(FriendlyByteBuf b) {
            return new C2SChunk(b.readVarInt(), b.readUtf(32), b.readVarInt(), b.readVarInt(), b.readUtf(2048), b.readByteArray(CHUNK_C2S + 64));
        }

        static void handle(C2SChunk m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) csequencer.forge.ServerNet.onChunk(sender, m);
            ctx.get().setPacketHandled(true);
        }
    }

    public record S2CMessage(String topic, byte[] data) {
        static void encode(S2CMessage m, FriendlyByteBuf b) {
            b.writeUtf(m.topic, 64);
            b.writeByteArray(m.data);
        }

        static S2CMessage decode(FriendlyByteBuf b) { return new S2CMessage(b.readUtf(64), b.readByteArray(MAX_S2C)); }

        static void handle(S2CMessage m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> csequencer.client.ClientNet.onMessage(m.topic, m.data));
            ctx.get().setPacketHandled(true);
        }
    }

    public record S2CChunk(int transfer, String kind, int index, int total, String meta, byte[] data) {
        static void encode(S2CChunk m, FriendlyByteBuf b) {
            b.writeVarInt(m.transfer);
            b.writeUtf(m.kind, 32);
            b.writeVarInt(m.index);
            b.writeVarInt(m.total);
            b.writeUtf(m.meta, 4096);
            b.writeByteArray(m.data);
        }

        static S2CChunk decode(FriendlyByteBuf b) {
            return new S2CChunk(b.readVarInt(), b.readUtf(32), b.readVarInt(), b.readVarInt(), b.readUtf(4096), b.readByteArray(CHUNK_S2C + 64));
        }

        static void handle(S2CChunk m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> csequencer.client.ClientNet.onChunk(m));
            ctx.get().setPacketHandled(true);
        }
    }
}
