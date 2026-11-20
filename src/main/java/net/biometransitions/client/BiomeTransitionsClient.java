package net.biometransitions.client;

import net.biometransitions.BiomeTransitions;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = BiomeTransitions.MODID, dist = Dist.CLIENT)
public final class BiomeTransitionsClient {
    public BiomeTransitionsClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new ConfigScreen(parent));
    }
}
