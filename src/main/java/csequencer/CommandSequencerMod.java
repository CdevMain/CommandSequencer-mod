package csequencer;

import com.mojang.logging.LogUtils;
import csequencer.forge.ForgePermissions;
import csequencer.forge.ServerEvents;
import csequencer.net.CSQNetwork;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Forge 1.20.1 entry point. Wires the loader-independent core ({@code csequencer.core}) to Forge:
 * server events and permissions on the Forge bus, networking at common setup, and the client
 * layer (screens, overlay, audio, keybinds) only on the physical client.
 */
@Mod(CommandSequencerMod.MODID)
public class CommandSequencerMod {
    public static final String MODID = "csequencer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CommandSequencerMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(ServerEvents.class);
        MinecraftForge.EVENT_BUS.register(ForgePermissions.class);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> csequencer.client.ClientSetup.init(modBus));
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(CSQNetwork::init);
    }
}
