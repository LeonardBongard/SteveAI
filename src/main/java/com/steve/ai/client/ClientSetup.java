package com.steve.ai.client;

import com.steve.ai.SteveMod;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.EntityRenderersEvent;
import com.steve.ai.entity.SteveEntity;

/**
 * Client-side setup for entity renderers and other client-only initialization
 */
public class ClientSetup {

    private static final Identifier STEVE_TEXTURE =
        Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");

    public static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
        });
    }

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SteveMod.STEVE_ENTITY.get(), context ->
            new HumanoidMobRenderer<SteveEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>>(
                context,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)),
                0.5F
            ) {
                @Override
                public HumanoidRenderState createRenderState() {
                    return new HumanoidRenderState();
                }

                @Override
                public Identifier getTextureLocation(HumanoidRenderState state) {
                    return STEVE_TEXTURE;
                }
            }
        );
    }
}
