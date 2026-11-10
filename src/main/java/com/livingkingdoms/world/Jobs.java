package com.livingkingdoms.world;

import com.livingkingdoms.npc.NpcEntity;
import com.livingkingdoms.npc.Profession;
import com.livingkingdoms.registry.ModItems;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;

/** Работы игрока и заработок монет. */
public class Jobs {

	public enum PlayerJob { NONE, LABORER, MINER, FARMER, BUILDER, SERVANT, GUARD, SOLDIER, TRADER }

	public static PlayerJob jobOf(ServerPlayerEntity p) {
		try {
			return PlayerJob.valueOf(KingdomState.get(p.getServer()).player(p.getUuid()).job);
		} catch (IllegalArgumentException e) {
			return PlayerJob.NONE;
		}
	}

	public static void setJob(ServerPlayerEntity p, PlayerJob j) {
		KingdomState st = KingdomState.get(p.getServer());
		st.player(p.getUuid()).job = j.name();
		st.markDirty();
	}

	// ---------- монеты ----------

	public static int countCoins(ServerPlayerEntity p) {
		int n = 0;
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack s = p.getInventory().getStack(i);
			if (s.isOf(ModItems.COIN)) n += s.getCount();
		}
		return n;
	}

	public static boolean takeCoins(ServerPlayerEntity p, int amount) {
		if (countCoins(p) < amount) return false;
		int left = amount;
		for (int i = 0; i < p.getInventory().size() && left > 0; i++) {
			ItemStack s = p.getInventory().getStack(i);
			if (s.isOf(ModItems.COIN)) {
				int t = Math.min(left, s.getCount());
				s.decrement(t);
				left -= t;
			}
		}
		return true;
	}

	public static void pay(ServerPlayerEntity p, int amount) {
		if (amount <= 0) return;
		ItemStack s = new ItemStack(ModItems.COIN, amount);
		if (!p.getInventory().insertStack(s)) p.dropItem(s, false);
		p.sendMessage(Text.translatable("job.livingkingdoms.paid", amount), true);
	}

	public static void onTradeDone(ServerPlayerEntity p) {
		if (jobOf(p) == PlayerJob.TRADER) pay(p, 1);
	}

	// ---------- события ----------

	public static void init() {
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
			if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return;
			PlayerJob job = jobOf(sp);
			if (job == PlayerJob.NONE) return;
			Block b = state.getBlock();
			String path = Registries.BLOCK.getId(b).getPath();
			var r = world.getRandom();
			switch (job) {
				case MINER -> {
					if (path.endsWith("_ore")) pay(sp, 2);
					else if (isStone(state) && r.nextInt(10) == 0) pay(sp, 1);
				}
				case FARMER -> {
					if (b instanceof CropBlock c && c.isMature(state)) pay(sp, 1);
				}
				case LABORER -> {
					if ((state.isOf(Blocks.DIRT) || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.SAND)
							|| state.isOf(Blocks.CLAY) || path.endsWith("_log")) && r.nextInt(8) == 0) pay(sp, 1);
				}
				default -> {
				}
			}
		});

		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
			if (player.getStackInHand(hand).getItem() instanceof BlockItem && jobOf(sp) == PlayerJob.BUILDER) {
				KingdomState st = KingdomState.get(sp.getServer());
				KingdomState.PlayerData d = st.player(sp.getUuid());
				d.builderCounter++;
				if (d.builderCounter >= 12) {
					d.builderCounter = 0;
					pay(sp, 1);
				}
				st.markDirty();
			}
			return ActionResult.PASS;
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(source.getAttacker() instanceof ServerPlayerEntity sp)) return;
			PlayerJob job = jobOf(sp);
			if (job != PlayerJob.GUARD && job != PlayerJob.SOLDIER) return;
			KingdomState st = KingdomState.get(sp.getServer());
			boolean inSettlement = st.settlementAt(sp.getBlockX(), sp.getBlockZ()) != null;
			if (entity instanceof NpcEntity n) {
				Profession np = n.getProfession();
				if (np == Profession.BANDIT) pay(sp, 3);
				else if (job == PlayerJob.SOLDIER && n.kingdomId >= 0 && !st.warsOf(n.kingdomId).isEmpty()
						&& np == Profession.SOLDIER) pay(sp, 4);
			} else if (entity instanceof HostileEntity && (inSettlement || job == PlayerJob.SOLDIER)) {
				pay(sp, 1);
			}
		});

		// слуги получают плату, пока находятся в поселении
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTicks() % 1200 != 0) return;
			KingdomState st = KingdomState.get(server);
			for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
				if (jobOf(p) == PlayerJob.SERVANT && st.settlementAt(p.getBlockX(), p.getBlockZ()) != null) pay(p, 2);
			}
		});
	}

	private static boolean isStone(BlockState s) {
		return s.isOf(Blocks.STONE) || s.isOf(Blocks.DEEPSLATE) || s.isOf(Blocks.COBBLESTONE) || s.isOf(Blocks.ANDESITE)
				|| s.isOf(Blocks.DIORITE) || s.isOf(Blocks.GRANITE);
	}
}
