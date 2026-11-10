package com.livingkingdoms;

import com.livingkingdoms.command.CivCommands;
import com.livingkingdoms.registry.ModEntities;
import com.livingkingdoms.registry.ModItems;
import com.livingkingdoms.world.CivManager;
import com.livingkingdoms.world.CivPackets;
import com.livingkingdoms.world.Jobs;
import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;

public class LivingKingdoms implements ModInitializer {
	public static final String MOD_ID = "livingkingdoms";

	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ModItems.register();
		ModEntities.register();
		CivPackets.init();
		CivManager.init();
		Jobs.init();
		CivCommands.register();
	}
}
