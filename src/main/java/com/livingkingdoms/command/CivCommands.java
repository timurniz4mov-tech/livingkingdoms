package com.livingkingdoms.command;

import com.livingkingdoms.npc.NpcEntity;
import com.livingkingdoms.npc.NpcFactory;
import com.livingkingdoms.npc.Profession;
import com.livingkingdoms.world.CivManager;
import com.livingkingdoms.world.CivPackets;
import com.livingkingdoms.world.Jobs;
import com.livingkingdoms.world.Jobs.PlayerJob;
import com.livingkingdoms.world.KingdomState;
import com.livingkingdoms.world.KingdomState.*;
import com.livingkingdoms.world.StructureBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class CivCommands {
	public static final int LAND_COST = 300;
	public static final int HOUSE_COST = 40;
	public static final int KINGDOM_COST = 1500;

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
			LiteralArgumentBuilder<ServerCommandSource> root = literal("civ");

			root.then(literal("info").executes(c -> info(c.getSource().getPlayerOrThrow())));

			LiteralArgumentBuilder<ServerCommandSource> job = literal("job");
			for (PlayerJob j : PlayerJob.values()) {
				job.then(literal(j.name().toLowerCase()).executes(c -> {
					ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
					Jobs.setJob(p, j);
					p.sendMessage(Text.translatable("cmd.livingkingdoms.job_set", Text.translatable("job.livingkingdoms." + j.name().toLowerCase())), false);
					return 1;
				}));
			}
			root.then(job);

			root.then(literal("buyland").executes(c -> buyLand(c.getSource().getPlayerOrThrow())));
			root.then(literal("buyhouse").executes(c -> buyHouse(c.getSource().getPlayerOrThrow())));

			root.then(literal("kingdom")
					.then(literal("found").then(argument("name", StringArgumentType.greedyString())
							.executes(c -> foundKingdom(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "name")))))
					.then(literal("list").executes(c -> listKingdoms(c.getSource()))));

			root.then(literal("war").then(argument("kingdom", StringArgumentType.greedyString())
					.executes(c -> declareWar(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "kingdom")))));
			root.then(literal("peace").executes(c -> peace(c.getSource().getPlayerOrThrow())));

			// --- администраторские ---
			root.then(literal("gen").requires(s -> s.hasPermissionLevel(2))
					.then(literal("village").executes(c -> gen(c.getSource().getPlayerOrThrow(), SType.VILLAGE)))
					.then(literal("castle").executes(c -> gen(c.getSource().getPlayerOrThrow(), SType.CASTLE))));
			LiteralArgumentBuilder<ServerCommandSource> spawn = literal("spawn").requires(s -> s.hasPermissionLevel(2));
			for (Profession pr : Profession.values()) {
				spawn.then(literal(pr.key())
						.executes(c -> spawnNpc(c.getSource().getPlayerOrThrow(), pr, 1))
						.then(argument("tier", IntegerArgumentType.integer(0, 3))
								.executes(c -> spawnNpc(c.getSource().getPlayerOrThrow(), pr, IntegerArgumentType.getInteger(c, "tier")))));
			}
			root.then(spawn);
			root.then(literal("randomwar").requires(s -> s.hasPermissionLevel(2)).executes(c -> {
				boolean ok = CivManager.triggerRandomWar(c.getSource().getServer(), KingdomState.get(c.getSource().getServer()));
				if (!ok) c.getSource().sendError(Text.translatable("cmd.livingkingdoms.no_war_possible"));
				return ok ? 1 : 0;
			}));

			dispatcher.register(root);
		});
	}

	private static void msg(ServerPlayerEntity p, String key, Object... args) {
		p.sendMessage(Text.translatable(key, args), false);
	}

	private static int info(ServerPlayerEntity p) {
		KingdomState st = KingdomState.get(p.getServer());
		msg(p, "cmd.livingkingdoms.info_job", Text.translatable("job.livingkingdoms." + Jobs.jobOf(p).name().toLowerCase()));
		msg(p, "cmd.livingkingdoms.info_coins", Jobs.countCoins(p));
		Settlement s = st.settlementAt(p.getBlockX(), p.getBlockZ());
		if (s == null) {
			msg(p, "cmd.livingkingdoms.info_wild");
		} else {
			Kingdom k = st.kingdoms.get(s.kingdomId);
			msg(p, "cmd.livingkingdoms.info_here", s.name, k == null ? "-" : k.name);
		}
		Kingdom own = st.kingdomOfPlayerKing(p.getUuid());
		if (own != null) msg(p, "cmd.livingkingdoms.info_king", own.name);
		for (Settlement o : st.settlements.values())
			if (o.owner.equals(p.getUuid().toString())) msg(p, "cmd.livingkingdoms.info_lord", o.name);
		return 1;
	}

	private static int buyLand(ServerPlayerEntity p) {
		KingdomState st = KingdomState.get(p.getServer());
		BlockPos pos = p.getBlockPos();
		if (st.settlementAt(pos.getX(), pos.getZ()) != null || st.nearestSettlement(pos, 70, null) != null) {
			msg(p, "cmd.livingkingdoms.land_taken");
			return 0;
		}
		Kingdom k = st.nearestKingdom(pos, 700);
		if (k == null) {
			msg(p, "cmd.livingkingdoms.no_king");
			return 0;
		}
		if (!Jobs.takeCoins(p, LAND_COST)) {
			msg(p, "cmd.livingkingdoms.not_enough", LAND_COST);
			return 0;
		}
		Settlement s = st.addSettlement("Lordship of " + p.getName().getString(),
				SType.LORDSHIP, pos, 40, k.id, 0);
		s.owner = p.getUuid().toString();
		st.markDirty();
		CivPackets.syncAll(p.getServer());
		msg(p, "cmd.livingkingdoms.land_bought", k.name);
		return 1;
	}

	private static int buyHouse(ServerPlayerEntity p) {
		if (!Jobs.takeCoins(p, HOUSE_COST)) {
			msg(p, "cmd.livingkingdoms.not_enough", HOUSE_COST);
			return 0;
		}
		Direction f = p.getHorizontalFacing();
		BlockPos c = p.getBlockPos().offset(f, 5);
		int y = p.getBlockY() - 1;
		StructureBuilder.Plan plan = new StructureBuilder.Plan();
		for (int x = c.getX() - 4; x <= c.getX() + 4; x++)
			for (int z = c.getZ() - 4; z <= c.getZ() + 4; z++)
				for (int yy = y + 1; yy <= y + 9; yy++) plan.set(x, yy, z, Blocks.AIR);
		StructureBuilder.house(plan, c.getX() - 2, y, c.getZ() - 2, 5, 5, true, StructureBuilder.WOOD);
		CivManager.enqueue(plan);
		msg(p, "cmd.livingkingdoms.house_bought");
		return 1;
	}

	private static int foundKingdom(ServerPlayerEntity p, String name) {
		KingdomState st = KingdomState.get(p.getServer());
		if (Jobs.jobOf(p) != PlayerJob.TRADER) {
			msg(p, "cmd.livingkingdoms.need_trader");
			return 0;
		}
		BlockPos pos = p.getBlockPos();
		if (st.kingdomOfPlayerKing(p.getUuid()) != null) {
			msg(p, "cmd.livingkingdoms.already_king");
			return 0;
		}
		if (st.kingdomByName(name) != null) {
			msg(p, "cmd.livingkingdoms.name_taken");
			return 0;
		}
		if (st.nearestSettlement(pos, 100, null) != null) {
			msg(p, "cmd.livingkingdoms.land_taken");
			return 0;
		}
		if (!Jobs.takeCoins(p, KINGDOM_COST)) {
			msg(p, "cmd.livingkingdoms.not_enough", KINGDOM_COST);
			return 0;
		}
		Kingdom k = st.addKingdom(name.trim(), 0x7A2ED9 + st.kingdoms.size() * 0x1234);
		k.kingPlayer = p.getUuid().toString();
		Settlement s = st.addSettlement(name.trim() + " Capital", SType.LORDSHIP, pos, 60, k.id, 1);
		s.owner = p.getUuid().toString();
		k.capitalId = s.id;
		st.markDirty();
		ServerWorld w = (ServerWorld) p.getWorld();
		for (int i = 0; i < 2; i++)
			NpcFactory.spawn(w, Profession.GUARD, pos.getX() + 2 + i, pos.getY(), pos.getZ() + 2, k.id, s.id, 1, pos);
		CivPackets.syncAll(p.getServer());
		p.getServer().getPlayerManager().broadcast(Text.translatable("event.livingkingdoms.kingdom_founded", p.getName(), k.name), false);
		return 1;
	}

	private static int listKingdoms(ServerCommandSource src) {
		KingdomState st = KingdomState.get(src.getServer());
		if (st.kingdoms.isEmpty()) {
			src.sendFeedback(() -> Text.translatable("cmd.livingkingdoms.no_kingdoms"), false);
			return 0;
		}
		for (Kingdom k : st.kingdoms.values()) {
			long count = st.settlements.values().stream().filter(s -> s.kingdomId == k.id).count();
			boolean war = !st.warsOf(k.id).isEmpty();
			src.sendFeedback(() -> Text.translatable(war ? "cmd.livingkingdoms.kingdom_line_war" : "cmd.livingkingdoms.kingdom_line",
					k.name, count), false);
		}
		return 1;
	}

	private static int declareWar(ServerPlayerEntity p, String target) {
		KingdomState st = KingdomState.get(p.getServer());
		Kingdom own = st.kingdomOfPlayerKing(p.getUuid());
		if (own == null) {
			msg(p, "cmd.livingkingdoms.not_king");
			return 0;
		}
		Kingdom k = st.kingdomByName(target);
		if (k == null || k.id == own.id) {
			msg(p, "cmd.livingkingdoms.unknown_kingdom");
			return 0;
		}
		if (st.isAtWar(own.id, k.id)) {
			msg(p, "cmd.livingkingdoms.already_war");
			return 0;
		}
		CivManager.declareWar(p.getServer(), st, own.id, k.id, CivManager.randomReason(((ServerWorld) p.getWorld()).getRandom()));
		return 1;
	}

	private static int peace(ServerPlayerEntity p) {
		KingdomState st = KingdomState.get(p.getServer());
		Kingdom own = st.kingdomOfPlayerKing(p.getUuid());
		if (own == null) {
			msg(p, "cmd.livingkingdoms.not_king");
			return 0;
		}
		for (War w : new java.util.ArrayList<>(st.warsOf(own.id))) CivManager.makePeace(p.getServer(), st, w);
		return 1;
	}

	private static int gen(ServerPlayerEntity p, SType type) {
		CivManager.forceGenerate(p.getBlockPos(), type);
		msg(p, "cmd.livingkingdoms.generating");
		return 1;
	}

	private static int spawnNpc(ServerPlayerEntity p, Profession pr, int tier) {
		KingdomState st = KingdomState.get(p.getServer());
		Kingdom k = st.nearestKingdom(p.getBlockPos(), 700);
		NpcEntity e = NpcFactory.spawn((ServerWorld) p.getWorld(), pr, p.getX(), p.getY(), p.getZ(),
				k == null ? -1 : k.id, -1, tier, p.getBlockPos());
		return e == null ? 0 : 1;
	}
}
