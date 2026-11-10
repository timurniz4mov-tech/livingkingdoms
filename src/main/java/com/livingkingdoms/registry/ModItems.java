package com.livingkingdoms.registry;

import com.livingkingdoms.LivingKingdoms;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public class ModItems {
	public static final Item COIN = new Item(new FabricItemSettings());

	public static void register() {
		Registry.register(Registries.ITEM, LivingKingdoms.id("coin"), COIN);
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.INGREDIENTS).register(entries -> entries.add(COIN));
	}
}
