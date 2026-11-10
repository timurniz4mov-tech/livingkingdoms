package com.livingkingdoms.registry;

import com.livingkingdoms.LivingKingdoms;
import com.livingkingdoms.npc.NpcEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public class ModEntities {
	public static EntityType<NpcEntity> NPC;

	public static void register() {
		NPC = Registry.register(Registries.ENTITY_TYPE, LivingKingdoms.id("npc"),
				FabricEntityTypeBuilder.create(SpawnGroup.MISC, NpcEntity::new)
						.dimensions(EntityDimensions.fixed(0.6f, 1.95f))
						.trackRangeBlocks(64)
						.build());
		FabricDefaultAttributeRegistry.register(NPC, NpcEntity.createNpcAttributes());
	}
}
