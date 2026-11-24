package com.example.projecte_research;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(projecte_research.MODID)
public class projecte_research {
    public static final String MODID = "projecte_research";

    public projecte_research() {
        ResearchConfig.load();
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(new CommonEvents());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ResearchNetwork::register);
    }
}