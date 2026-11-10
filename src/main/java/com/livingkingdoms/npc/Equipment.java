package com.livingkingdoms.npc;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;

/** Экипировка по уровню (tier): 0 дерево/кожа, 1 камень/кольчуга, 2 железо, 3 алмаз. */
public class Equipment {
	private static final Item[] SWORDS = {Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD};
	private static final Item[] PICKS = {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE};
	private static final Item[] AXES = {Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE};
	private static final Item[] HOES = {Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE};
	private static final Item[] SHOVELS = {Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL};
	// шлем, нагрудник, поножи, ботинки
	private static final Item[][] ARMOR = {
			{Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS},
			{Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS},
			{Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
			{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}
	};
	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	public static void equip(NpcEntity e) {
		Profession p = e.getProfession();
		int t = MathHelper.clamp(e.tier, 0, 3);
		Random r = e.getRandom();
		for (EquipmentSlot s : EquipmentSlot.values()) e.equipStack(s, ItemStack.EMPTY);

		switch (p) {
			case LABORER -> hand(e, SHOVELS[t]);
			case MINER -> hand(e, PICKS[t]);
			case FARMER -> hand(e, HOES[t]);
			case BUILDER -> hand(e, AXES[t]);
			case SERVANT -> {
				if (r.nextInt(3) == 0) hand(e, Items.BREAD);
			}
			case GUARD -> {
				hand(e, SWORDS[t]);
				armor(e, t, 4);
			}
			case SOLDIER -> {
				hand(e, SWORDS[Math.min(3, t + (r.nextInt(3) == 0 ? 1 : 0))]);
				armor(e, t, 4);
			}
			case BANDIT -> {
				int bt = Math.min(t, 2);
				hand(e, r.nextBoolean() ? AXES[bt] : SWORDS[bt]);
				for (int i = 0; i < 4; i++) if (r.nextInt(3) == 0) e.equipStack(SLOTS[i], new ItemStack(ARMOR[Math.min(bt, 1)][i]));
			}
			case LORD -> {
				hand(e, SWORDS[Math.min(3, t + 1)]);
				e.equipStack(EquipmentSlot.CHEST, new ItemStack(ARMOR[Math.min(3, t + 1)][1]));
				e.equipStack(EquipmentSlot.LEGS, new ItemStack(ARMOR[Math.min(3, t + 1)][2]));
			}
			case KING -> {
				hand(e, Items.DIAMOND_SWORD);
				e.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
				e.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
				e.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.GOLDEN_LEGGINGS));
				e.equipStack(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
			}
			case TRAVELER -> {
				hand(e, t == 0 ? Items.STICK : PICKS[Math.min(t, 2)]);
				e.equipStack(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
			}
			case TRADER_ROAD -> e.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
			case TRADER_LOCAL -> {
			}
		}
		for (EquipmentSlot s : EquipmentSlot.values()) e.setEquipmentDropChance(s, 0.05f);
	}

	private static void hand(NpcEntity e, Item item) {
		e.equipStack(EquipmentSlot.MAINHAND, new ItemStack(item));
	}

	private static void armor(NpcEntity e, int tier, int pieces) {
		for (int i = 0; i < pieces; i++) e.equipStack(SLOTS[i], new ItemStack(ARMOR[tier][i]));
	}
}
