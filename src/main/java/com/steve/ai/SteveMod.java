package com.steve.ai;

import com.mojang.logging.LogUtils;
import com.steve.ai.command.SteveCommands;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import com.steve.ai.network.SteveNetwork;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

@Mod(SteveMod.MODID)
public class SteveMod {
    public static final String MODID = "steve";
    public static final Logger LOGGER = LogUtils.getLogger();

    static {
        LOGGER.info("SteveMod class loaded");
    }

    public static final DeferredRegister<EntityType<?>> ENTITIES = 
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);

    public static final RegistryObject<EntityType<SteveEntity>> STEVE_ENTITY = ENTITIES.register("steve",
        () -> EntityType.Builder.of(SteveEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(10)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MODID, "steve"))));

    private static SteveManager steveManager;

    public SteveMod() {
        LOGGER.info("SteveMod constructor start");
        BusGroup modBusGroup = FMLJavaModLoadingContext.get().getModBusGroup();

        ENTITIES.register(modBusGroup);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SteveConfig.SPEC);

        FMLCommonSetupEvent.getBus(modBusGroup).addListener(this::commonSetup);
        EntityAttributeCreationEvent.getBus(modBusGroup).addListener(this::entityAttributes);

        RegisterCommandsEvent.BUS.addListener(this::onCommandRegister);
        
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent.getBus(modBusGroup).addListener(
                com.steve.ai.client.ClientSetup::onClientSetup
            );
            net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers.BUS.addListener(
                com.steve.ai.client.ClientSetup::registerRenderers
            );
            com.steve.ai.client.SteveGUI.registerOverlayLayer();
            net.minecraftforge.event.TickEvent.ClientTickEvent.Post.BUS.addListener(
                com.steve.ai.client.ClientEventHandler::onClientTick
            );
            net.minecraftforge.client.event.RegisterKeyMappingsEvent.BUS.addListener(
                com.steve.ai.client.KeyBindings::registerKeys
            );
        }
        
        steveManager = new SteveManager();
        LOGGER.info("SteveMod constructor complete");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("SteveMod common setup");
        event.enqueueWork(com.steve.ai.network.SteveNetworkHandler::register);

    }

    private void entityAttributes(EntityAttributeCreationEvent event) {
        event.put(STEVE_ENTITY.get(), SteveEntity.createAttributes().build());
    }

    public void onCommandRegister(RegisterCommandsEvent event) {
        LOGGER.info("SteveMod registering commands");
        SteveCommands.register(event.getDispatcher());
    }

    public static SteveManager getSteveManager() {
        return steveManager;
    }
}
