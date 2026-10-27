package dev.chill.commonsense;

import dev.chill.commonsense.dev.DevScenarios;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

@Mod(CommonSense.MOD_ID)
public class CommonSense {

    public static final String MOD_ID = "commonsense";

    public static final TagKey<EntityType<?>> COMPANIONS = TagKey.create(Registries.ENTITY_TYPE, id("companions"));
    public static final TagKey<EntityType<?>> EXCLUDED = TagKey.create(Registries.ENTITY_TYPE, id("excluded"));
    /** Golems, guards and other mobs that belong to a village (villagers are always included). */
    public static final TagKey<EntityType<?>> VILLAGE_MOBS = TagKey.create(Registries.ENTITY_TYPE, id("village_mobs"));
    /** Items that other mods use to lure villagers (emerald block by default). A player holding one nearby counts as leading. */
    public static final TagKey<net.minecraft.world.item.Item> VILLAGER_LURES = TagKey.create(Registries.ITEM, id("villager_lures"));

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MOD_ID);
    // STATE and TRAIL are runtime only (not saved).
    public static final Supplier<AttachmentType<CompanionState>> STATE = ATTACHMENTS.register("state", () -> AttachmentType.builder(CompanionState::new).build());
    public static final Supplier<AttachmentType<Trail>> TRAIL = ATTACHMENTS.register("trail", () -> AttachmentType.builder(Trail::new).build());
    // Saved with the mob, so a villager's bed and a golem's village survive restarts.
    public static final Supplier<AttachmentType<VillageMemory>> VILLAGE = ATTACHMENTS.register("village", () -> AttachmentType.builder(VillageMemory::new).serialize(VillageMemory.CODEC).build());

    public CommonSense(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        ATTACHMENTS.register(modBus);
        NeoForge.EVENT_BUS.register(CompanionEvents.class);
        NeoForge.EVENT_BUS.register(HerdInstinct.class);
        if (Boolean.getBoolean("commonsense.devtests")) {
            NeoForge.EVENT_BUS.register(DevScenarios.class);
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
