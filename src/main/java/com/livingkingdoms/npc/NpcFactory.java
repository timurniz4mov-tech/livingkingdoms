package com.livingkingdoms.npc;

import com.livingkingdoms.registry.ModEntities;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public class NpcFactory {
	public static NpcEntity spawn(ServerWorld w, Profession p, double x, double y, double z,
	                              int kingdomId, int settlementId, int tier, BlockPos home) {
		NpcEntity e = ModEntities.NPC.create(w);
		if (e == null) return null;
		e.refreshPositionAndAngles(x, y, z, w.getRandom().nextFloat() * 360f, 0f);
		e.setProfession(p);
		e.kingdomId = kingdomId;
		e.settlementId = settlementId;
		e.tier = tier;
		e.home = home;
		e.base = home;
		Equipment.equip(e);
		w.spawnEntity(e);
		return e;
	}
}
