package dev.chill.commonsense;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** In-game config screen (Mods list > Common Sense > Config, or via Configured). Values are read live. */
@Mod(value = CommonSense.MOD_ID, dist = Dist.CLIENT)
public class CommonSenseClient {

    public CommonSenseClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
