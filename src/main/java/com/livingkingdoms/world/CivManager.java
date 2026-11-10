package com.livingkingdoms.world;

import com.livingkingdoms.npc.NpcEntity;
import com.livingkingdoms.npc.NpcFactory;
import com.livingkingdoms.npc.Profession;
import com.livingkingdoms.world.KingdomState.*;
import com.livingkingdoms.world.StructureBuilder.Plan;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.function.Consumer;

/** Сердце мода: генерация поселений, дороги, путники, бандиты, политика и войны. */
public class CivManager {
	private static final int CELL = 16;          // размер ячейки в чанках (256 блоков)
	private static final float CHANCE = 0.6f;    // шанс поселения в ячейке
	private static final int WAR_DURATION = 18000;

	private static final String[] VILLAGE_A = {"Oak", "Stone", "River", "Green", "Raven", "Iron", "Wolf", "Amber", "Birch", "Mill", "Fox", "High"};
	private static final String[] VILLAGE_B = {"ford", "wick", "ton", "haven", "brook", "field", "mere", "stead", "hollow", "ridge"};
	private static final String[] CASTLE_A = {"Black", "Grey", "Storm", "Gold", "Dragon", "Eagle", "Thorn", "Silver"};
	private static final String[] CASTLE_B = {"keep", "hold", "crown", "spire", "rock", "gate", "watch"};
	private static final String[] KINGDOMS = {"Aldoria", "Valmark", "Thornia", "Eastmere", "Dravenholt", "Solheim", "Karnak", "Westerland", "Brightvale", "Ironmarch"};
	private static final int[] COLORS = {0xD93B3B, 0x3B7BD9, 0x3BC25A, 0xE0B72E, 0x9B3BD9, 0xE07A2E, 0x2EC9C9, 0xD93B9B};
	private static final String[] WAR_REASONS = {"border_dispute", "insulted_envoy", "stolen_tribute", "succession_claim", "trade_embargo", "harboured_bandits"};

	private static class Site {
		int bx, bz;
		SType type;
		boolean force;
		int attempts;
		long key;
	}

	private static class BuildJob {
		final Plan plan;
		final Consumer<ServerWorld> onFinish;
		int idx;

		BuildJob(Plan plan, Consumer<ServerWorld> onFinish) {
			this.plan = plan;
			this.onFinish = onFinish;
		}

		boolean step(ServerWorld w, int n) {
			int end = Math.min(plan.size(), idx + n);
			for (; idx < end; idx++) w.setBlockState(plan.pos.get(idx), plan.st.get(idx), Block.NOTIFY_LISTENERS);
			return idx >= plan.size();
		}
	}

	private static class Respawn {
		int settlementId;
		Profession prof;
		long time;
	}

	private static final Deque<Site> pending = new ArrayDeque<>();
	private static final Set<Long> inflight = new HashSet<>();
	private static final Deque<BuildJob> jobs = new ArrayDeque<>();
	private static BuildJob current;
	private static final Deque<long[]> roadQueue = new ArrayDeque<>();
	private static final List<Respawn> respawns = new ArrayList<>();
	private static long forceKey = -1;

	public static void init() {
		ServerChunkEvents.CHUNK_LOAD.register(CivManager::onChunkLoad);
		ServerTickEvents.END_SERVER_TICK.register(CivManager::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			pending.clear();
			inflight.clear();
			jobs.clear();
			current = null;
			roadQueue.clear();
			respawns.clear();
		});
	}

	// ======================= Генерация =======================

	private static void onChunkLoad(ServerWorld world, WorldChunk chunk) {
		if (world.getRegistryKey() != World.OVERWORLD || world.getServer() == null) return;
		ChunkPos cp = chunk.getPos();
		KingdomState st = KingdomState.get(world.getServer());
		int cellX = Math.floorDiv(cp.x, CELL), cellZ = Math.floorDiv(cp.z, CELL);
		long key = ChunkPos.toLong(cellX, cellZ);
		if (!st.cells.contains(key) && !inflight.contains(key)) {
			java.util.Random r = new java.util.Random(world.getSeed() * 31L + key * 0x9E3779B97F4A7C15L);
			int sx = cellX * CELL + 2 + r.nextInt(CELL - 4);
			int sz = cellZ * CELL + 2 + r.nextInt(CELL - 4);
			if (sx == cp.x && sz == cp.z) {
				if (r.nextFloat() < CHANCE) {
					Site s = new Site();
					s.bx = cp.x * 16 + 8;
					s.bz = cp.z * 16 + 8;
					s.type = r.nextFloat() < 0.3f ? SType.CASTLE : SType.VILLAGE;
					s.key = key;
					inflight.add(key);
					pending.add(s);
				} else {
					st.cells.add(key);
					st.markDirty();
				}
			}
		}
		// дороги, затрагивающие этот чанк
		long cl = cp.toLong();
		for (int i = 0; i < st.roads.size(); i++) {
			Road rd = st.roads.get(i);
			if (!rd.painted.contains(cl) && roadNearChunk(rd, cp)) roadQueue.add(new long[]{i, cl});
		}
	}

	private static boolean roadNearChunk(Road rd, ChunkPos cp) {
		double cx = cp.getStartX() + 8, cz = cp.getStartZ() + 8;
		double dx = rd.x2 - rd.x1, dz = rd.z2 - rd.z1;
		double len2 = dx * dx + dz * dz;
		double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((cx - rd.x1) * dx + (cz - rd.z1) * dz) / len2));
		double px = rd.x1 + t * dx, pz = rd.z1 + t * dz;
		return Math.hypot(cx - px, cz - pz) < 14;
	}

	/** Для команды /civ gen: поставить поселение прямо у игрока. */
	public static void forceGenerate(BlockPos pos, SType type) {
		Site s = new Site();
		s.bx = pos.getX();
		s.bz = pos.getZ();
		s.type = type;
		s.force = true;
		s.key = forceKey--;
		pending.add(s);
	}

	public static void enqueue(Plan plan) {
		jobs.add(new BuildJob(plan, null));
	}

	private static void tick(MinecraftServer server) {
		ServerWorld w = server.getOverworld();
		if (w == null) return;
		KingdomState st = KingdomState.get(server);
		long t = server.getTicks();

		if (current == null && !jobs.isEmpty()) current = jobs.poll();
		if (current != null) {
			if (current.step(w, 2500)) {
				BuildJob j = current;
				current = null;
				if (j.onFinish != null) j.onFinish.accept(w);
				CivPackets.syncAll(server);
			}
		} else if (!pending.isEmpty() && t % 5 == 0) {
			processSite(w, st);
		}
		if (!roadQueue.isEmpty()) paintRoad(w, st);

		if (t % 100 == 0) tickSpawns(w, st);
		if (t % 200 == 0) tickRespawns(w, st);
		if (t % 600 == 0) tickWarParties(w, st);
		if (t % 1200 == 0) tickPolitics(server, w, st);
		if (t % 400 == 0) CivPackets.syncAll(server);
	}

	private static boolean chunksLoaded(ServerWorld w, int bx, int bz, int r) {
		for (int cx = (bx - r) >> 4; cx <= (bx + r) >> 4; cx++)
			for (int cz = (bz - r) >> 4; cz <= (bz + r) >> 4; cz++)
				if (!w.isChunkLoaded(new BlockPos(cx * 16, 64, cz * 16))) return false;
		return true;
	}

	private static void processSite(ServerWorld w, KingdomState st) {
		Site s = pending.poll();
		if (s == null) return;
		if (!chunksLoaded(w, s.bx, s.bz, 24)) {
			if (++s.attempts < 120) pending.add(s);
			else inflight.remove(s.key);
			return;
		}
		int min = 999, max = -999, sum = 0, n = 0;
		boolean bad = false;
		BlockState center = Blocks.GRASS_BLOCK.getDefaultState();
		for (int dx = -16; dx <= 16; dx += 16) {
			for (int dz = -16; dz <= 16; dz += 16) {
				int x = s.bx + dx, z = s.bz + dz;
				int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockState g = w.getBlockState(new BlockPos(x, top - 1, z));
				if (!g.getFluidState().isEmpty()) bad = true;
				if (dx == 0 && dz == 0) center = g;
				min = Math.min(min, top);
				max = Math.max(max, top);
				sum += top;
				n++;
			}
		}
		int h = s.force ? w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, s.bx, s.bz) - 1 : Math.round((float) sum / n) - 1;
		if (!s.force && (bad || max - min > 8 || h < w.getSeaLevel() - 1)) {
			st.cells.add(s.key);
			st.markDirty();
			inflight.remove(s.key);
			return;
		}
		boolean sand = center.isOf(Blocks.SAND) || center.isOf(Blocks.RED_SAND) || center.isOf(Blocks.SANDSTONE);
		BlockState surface = sand ? Blocks.SAND.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState();
		BlockState fill = sand ? Blocks.SANDSTONE.getDefaultState() : Blocks.DIRT.getDefaultState();

		Random r = w.getRandom();
		BlockPos pos = new BlockPos(s.bx, h, s.bz);
		Settlement set;
		boolean capital = false;
		if (s.type == SType.CASTLE) {
			Kingdom k = st.nearestKingdom(pos, 700);
			if (k == null) {
				k = st.addKingdom(uniqueKingdomName(st, r), COLORS[st.kingdoms.size() % COLORS.length]);
				capital = true;
			}
			set = st.addSettlement(pick(CASTLE_A, r) + pick(CASTLE_B, r), SType.CASTLE, pos, 22, k.id, 1 + r.nextInt(3));
			if (capital) k.capitalId = set.id;
		} else {
			Kingdom k = st.nearestKingdom(pos, 600);
			set = st.addSettlement(pick(VILLAGE_A, r) + pick(VILLAGE_B, r), SType.VILLAGE, pos, 24, k == null ? -1 : k.id, r.nextInt(2));
		}
		st.cells.add(s.key);
		inflight.remove(s.key);

		// дорога к ближайшему соседу
		Settlement near = null;
		double bd = 420.0 * 420.0;
		for (Settlement o : st.settlements.values()) {
			if (o.id == set.id) continue;
			double d = KingdomState.horizSq(o.pos, pos);
			if (d < bd) { bd = d; near = o; }
		}
		if (near != null) {
			Road rd = new Road();
			rd.x1 = pos.getX(); rd.z1 = pos.getZ(); rd.x2 = near.pos.getX(); rd.z2 = near.pos.getZ();
			st.roads.add(rd);
		}
		st.markDirty();

		Plan plan = new Plan();
		if (s.type == SType.CASTLE) StructureBuilder.castle(plan, s.bx, h, s.bz, surface, fill);
		else StructureBuilder.village(plan, s.bx, h, s.bz, surface, fill);
		final boolean cap = capital;
		final int sid = set.id;
		jobs.add(new BuildJob(plan, world -> {
			KingdomState state = KingdomState.get(world.getServer());
			Settlement fs = state.settlements.get(sid);
			if (fs != null) spawnInhabitants(world, fs, cap);
			world.getServer().getPlayerManager().broadcast(Text.translatable(
					fs != null && fs.type == SType.CASTLE ? "event.livingkingdoms.castle_found" : "event.livingkingdoms.village_found",
					fs == null ? "?" : fs.name), false);
		}));
	}

	private static String pick(String[] a, Random r) {
		return a[r.nextInt(a.length)];
	}

	private static String uniqueKingdomName(KingdomState st, Random r) {
		for (int i = 0; i < 20; i++) {
			String n = pick(KINGDOMS, r);
			if (st.kingdomByName(n) == null) return n;
		}
		return pick(KINGDOMS, r) + " " + (st.kingdoms.size() + 2);
	}

	// ======================= Жители =======================

	private static NpcEntity spawn(ServerWorld w, Profession p, int x, int y, int z, Settlement s, BlockPos home) {
		return NpcFactory.spawn(w, p, x + 0.5, y, z + 0.5, s.kingdomId, s.id, s.tier, home);
	}

	public static BlockPos rulerSpot(Settlement s) {
		if (s.type == SType.CASTLE) return new BlockPos(s.pos.getX(), s.pos.getY() + 1, s.pos.getZ() - 2);
		return new BlockPos(s.pos.getX() + 8, s.pos.getY() + 1, s.pos.getZ() + 11);
	}

	private static void spawnInhabitants(ServerWorld w, Settlement s, boolean capital) {
		Random r = w.getRandom();
		int bx = s.pos.getX(), h = s.pos.getY(), bz = s.pos.getZ();
		BlockPos rs = rulerSpot(s);
		Profession ruler = (s.type == SType.CASTLE && capital) ? Profession.KING : Profession.LORD;
		spawn(w, ruler, rs.getX(), rs.getY(), rs.getZ(), s, new BlockPos(rs.getX(), h, rs.getZ()));
		if (s.type == SType.VILLAGE) {
			Profession[] crew = {Profession.LABORER, Profession.LABORER, Profession.FARMER, Profession.FARMER, Profession.FARMER,
					Profession.MINER, Profession.BUILDER, Profession.SERVANT, Profession.GUARD, Profession.GUARD, Profession.TRADER_LOCAL};
			for (Profession p : crew) {
				BlockPos sp = streetSpot(bx, bz, r);
				spawn(w, p, sp.getX(), h + 1, sp.getZ(), s, s.pos);
			}
		} else {
			Profession[] crew = {Profession.SOLDIER, Profession.SOLDIER, Profession.SOLDIER, Profession.SOLDIER,
					Profession.GUARD, Profession.GUARD, Profession.GUARD, Profession.GUARD,
					Profession.SERVANT, Profession.SERVANT, Profession.SERVANT, Profession.LABORER, Profession.LABORER,
					Profession.BUILDER, Profession.TRADER_LOCAL};
			for (Profession p : crew) {
				int x = bx + r.nextInt(15) - 7;
				int z = bz + 7 + r.nextInt(8);
				spawn(w, p, x, h + 1, z, s, s.pos);
			}
		}
	}

	private static BlockPos streetSpot(int bx, int bz, Random r) {
		for (int i = 0; i < 20; i++) {
			int x, z;
			if (r.nextBoolean()) { x = bx + r.nextInt(33) - 16; z = bz + r.nextInt(2); }
			else { x = bx + r.nextInt(2); z = bz + r.nextInt(33) - 16; }
			if (Math.abs(x - bx) <= 2 && Math.abs(z - bz) <= 2) continue;
			return new BlockPos(x, 0, z);
		}
		return new BlockPos(bx + 5, 0, bz);
	}

	// ======================= Дороги =======================

	private static void paintRoad(ServerWorld w, KingdomState st) {
		long[] e = roadQueue.poll();
		if (e == null) return;
		Road rd = st.roads.get((int) e[0]);
		long cl = e[1];
		if (rd.painted.contains(cl)) return;
		ChunkPos cp = new ChunkPos(cl);
		if (!w.isChunkLoaded(new BlockPos(cp.getStartX(), 64, cp.getStartZ()))) return;
		double dx = rd.x2 - rd.x1, dz = rd.z2 - rd.z1;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1) return;
		double ux = dx / len, uz = dz / len;
		for (double t = 0; t <= len; t += 0.5) {
			double px = rd.x1 + ux * t, pz = rd.z1 + uz * t;
			for (int o = -1; o <= 1; o++) {
				int bx = (int) Math.floor(px - uz * o);
				int bz = (int) Math.floor(pz + ux * o);
				if ((bx >> 4) != cp.x || (bz >> 4) != cp.z) continue;
				int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, bx, bz);
				BlockPos g = new BlockPos(bx, top - 1, bz);
				BlockState gs = w.getBlockState(g);
				if (gs.isOf(Blocks.GRASS_BLOCK) || gs.isOf(Blocks.DIRT) || gs.isOf(Blocks.COARSE_DIRT) || gs.isOf(Blocks.PODZOL)) {
					BlockState up = w.getBlockState(g.up());
					if (!up.isAir() && !up.isReplaceable()) continue;
					if (!up.isAir()) w.setBlockState(g.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
					w.setBlockState(g, Blocks.DIRT_PATH.getDefaultState(), Block.NOTIFY_LISTENERS);
				}
			}
		}
		rd.painted.add(cl);
		st.markDirty();
	}

	// ======================= Путники и бандиты =======================

	private static BlockPos ringPos(ServerWorld w, BlockPos center, Random r, int minD, int maxD) {
		double ang = r.nextDouble() * Math.PI * 2;
		double d = minD + r.nextInt(maxD - minD + 1);
		int x = center.getX() + (int) (Math.cos(ang) * d);
		int z = center.getZ() + (int) (Math.sin(ang) * d);
		if (!w.isChunkLoaded(new BlockPos(x, 64, z))) return null;
		int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos g = new BlockPos(x, top - 1, z);
		BlockState gs = w.getBlockState(g);
		if (!gs.getFluidState().isEmpty() || !gs.isSolid()) return null;
		return new BlockPos(x, top, z);
	}

	private static void tickSpawns(ServerWorld w, KingdomState st) {
		if (st.settlements.isEmpty()) return;
		Random r = w.getRandom();
		for (ServerPlayerEntity p : w.getPlayers()) {
			if (p.isSpectator()) continue;
			BlockPos pp = p.getBlockPos();
			if (st.nearestSettlement(pp, 350, null) == null) continue;
			List<NpcEntity> around = w.getEntitiesByClass(NpcEntity.class, new Box(pp).expand(96), e -> true);
			long trav = around.stream().filter(e -> e.getProfession().isRoadMobile()).count();
			long bandits = around.stream().filter(e -> e.getProfession() == Profession.BANDIT).count();

			if (trav < 3 && r.nextFloat() < 0.4f) {
				BlockPos sp = ringPos(w, pp, r, 48, 80);
				if (sp != null) {
					Profession prof = r.nextFloat() < 0.6f ? Profession.TRAVELER : Profession.TRADER_ROAD;
					NpcFactory.spawn(w, prof, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, -1, -1, r.nextInt(3), null);
				}
			} else if (bandits == 0 && r.nextFloat() < 0.07f) {
				BlockPos sp = ringPos(w, pp, r, 55, 85);
				if (sp != null) {
					boolean raid = r.nextFloat() < 0.15f;
					int n = raid ? 6 + r.nextInt(4) : 2 + r.nextInt(3);
					Settlement target = raid ? st.nearestSettlement(sp, 220, SType.VILLAGE) : null;
					for (int i = 0; i < n; i++) {
						int x = sp.getX() + r.nextInt(7) - 3, z = sp.getZ() + r.nextInt(7) - 3;
						if (!w.isChunkLoaded(new BlockPos(x, 64, z))) continue;
						int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
						NpcEntity b = NpcFactory.spawn(w, Profession.BANDIT, x + 0.5, y, z + 0.5, -1, -1, r.nextInt(3), sp);
						if (b != null && target != null) {
							b.travelTarget = target.pos;
							b.travelSettlementId = target.id;
						}
					}
					if (target != null) p.sendMessage(Text.translatable("event.livingkingdoms.raid", target.name), false);
				}
			}
		}
	}

	/** Куда идёт путник/солдат/рейд. */
	public static BlockPos pickDestination(NpcEntity npc) {
		ServerWorld w = (ServerWorld) npc.getWorld();
		KingdomState st = KingdomState.get(w.getServer());
		Profession p = npc.getProfession();
		BlockPos me = npc.getBlockPos();
		if (p == Profession.SOLDIER) {
			Settlement best = null;
			double bd = Double.MAX_VALUE;
			if (npc.kingdomId >= 0) {
				for (War war : st.warsOf(npc.kingdomId)) {
					int enemy = war.other(npc.kingdomId);
					for (Settlement s : st.settlements.values()) {
						if (s.kingdomId != enemy) continue;
						double d = KingdomState.horizSq(s.pos, me);
						if (d < bd) { bd = d; best = s; }
					}
				}
			}
			if (best != null) {
				npc.travelSettlementId = best.id;
				return best.pos;
			}
			npc.travelSettlementId = -1;
			return npc.base;
		}
		List<Settlement> list = new ArrayList<>(st.settlements.values());
		list.removeIf(s -> s.id == npc.lastSettlementId || KingdomState.horizSq(s.pos, me) < 40 * 40
				|| KingdomState.horizSq(s.pos, me) > 450 * 450);
		list.sort(Comparator.comparingDouble(s -> KingdomState.horizSq(s.pos, me)));
		if (list.isEmpty()) return null;
		List<Settlement> top = list.subList(0, Math.min(4, list.size()));
		Settlement c = top.get(npc.getRandom().nextInt(top.size()));
		npc.travelSettlementId = c.id;
		return c.pos;
	}

	// ======================= Политика и войны =======================

	public static void declareWar(MinecraftServer server, KingdomState st, int a, int b, String reason) {
		War w = new War();
		w.attacker = a;
		w.defender = b;
		w.startTime = server.getOverworld().getTime();
		w.reason = reason;
		st.wars.add(w);
		st.markDirty();
		Kingdom ka = st.kingdoms.get(a), kb = st.kingdoms.get(b);
		server.getPlayerManager().broadcast(Text.translatable("event.livingkingdoms.war",
				ka.name, kb.name, Text.translatable("war.reason." + reason)), false);
		CivPackets.syncAll(server);
	}

	public static String randomReason(Random r) {
		return WAR_REASONS[r.nextInt(WAR_REASONS.length)];
	}

	public static void makePeace(MinecraftServer server, KingdomState st, War war) {
		st.wars.remove(war);
		st.markDirty();
		Kingdom ka = st.kingdoms.get(war.attacker), kb = st.kingdoms.get(war.defender);
		if (ka != null && kb != null)
			server.getPlayerManager().broadcast(Text.translatable("event.livingkingdoms.peace", ka.name, kb.name), false);
		CivPackets.syncAll(server);
	}

	public static void tickPolitics(MinecraftServer server, ServerWorld w, KingdomState st) {
		long now = w.getTime();
		for (War war : new ArrayList<>(st.wars)) {
			if (now - war.startTime > WAR_DURATION) makePeace(server, st, war);
		}
		if (st.kingdoms.size() < 2 || st.wars.size() >= Math.max(1, st.kingdoms.size() / 3)) return;
		if (w.getRandom().nextFloat() > 0.12f) return;
		triggerRandomWar(server, st);
	}

	public static boolean triggerRandomWar(MinecraftServer server, KingdomState st) {
		Random r = server.getOverworld().getRandom();
		List<Kingdom> npcKingdoms = new ArrayList<>();
		for (Kingdom k : st.kingdoms.values()) if (k.kingPlayer.isEmpty()) npcKingdoms.add(k);
		if (npcKingdoms.isEmpty() || st.kingdoms.size() < 2) return false;
		Kingdom a = npcKingdoms.get(r.nextInt(npcKingdoms.size()));
		Settlement ac = st.settlements.get(a.capitalId);
		Kingdom best = null;
		double bd = 1500.0 * 1500.0;
		for (Kingdom k : st.kingdoms.values()) {
			if (k.id == a.id || st.isAtWar(a.id, k.id)) continue;
			Settlement kc = st.settlements.get(k.capitalId);
			if (ac == null || kc == null) continue;
			double d = KingdomState.horizSq(ac.pos, kc.pos);
			if (d < bd) { bd = d; best = k; }
		}
		if (best == null) return false;
		declareWar(server, st, a.id, best.id, randomReason(r));
		return true;
	}

	/** Рядом с игроком во время войны появляются отряды сторон, идущие в бой. */
	private static void tickWarParties(ServerWorld w, KingdomState st) {
		if (st.wars.isEmpty()) return;
		Random r = w.getRandom();
		for (War war : st.wars) {
			for (ServerPlayerEntity p : w.getPlayers()) {
				BlockPos pp = p.getBlockPos();
				boolean close = false;
				for (Settlement s : st.settlements.values())
					if ((s.kingdomId == war.attacker || s.kingdomId == war.defender) && KingdomState.horizSq(s.pos, pp) < 300 * 300) close = true;
				if (!close) continue;
				long soldiers = w.getEntitiesByClass(NpcEntity.class, new Box(pp).expand(100),
						e -> e.getProfession() == Profession.SOLDIER).size();
				if (soldiers >= 14) continue;
				BlockPos sp = ringPos(w, pp, r, 50, 70);
				if (sp == null) continue;
				spawnParty(w, st, war.attacker, sp, 0, r);
				spawnParty(w, st, war.defender, sp, 18, r);
				break;
			}
		}
	}

	private static void spawnParty(ServerWorld w, KingdomState st, int kingdomId, BlockPos at, int offset, Random r) {
		Kingdom k = st.kingdoms.get(kingdomId);
		if (k == null) return;
		Settlement cap = st.settlements.get(k.capitalId);
		int tier = cap != null ? Math.min(3, cap.tier + r.nextInt(2)) : 1;
		for (int i = 0; i < 3; i++) {
			int x = at.getX() + offset + r.nextInt(5), z = at.getZ() + r.nextInt(5);
			if (!w.isChunkLoaded(new BlockPos(x, 64, z))) continue;
			int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
			NpcFactory.spawn(w, Profession.SOLDIER, x + 0.5, y, z + 0.5, kingdomId, cap == null ? -1 : cap.id, tier,
					cap == null ? null : cap.pos);
		}
	}

	// ======================= Правители =======================

	public static void onNpcDeath(NpcEntity npc, DamageSource source) {
		Profession p = npc.getProfession();
		if ((p == Profession.KING || p == Profession.LORD) && npc.settlementId >= 0 && npc.getWorld().getServer() != null) {
			Respawn rs = new Respawn();
			rs.settlementId = npc.settlementId;
			rs.prof = p;
			rs.time = npc.getWorld().getTime() + 24000;
			respawns.add(rs);
			if (source.getAttacker() instanceof ServerPlayerEntity sp) {
				KingdomState st = KingdomState.get(npc.getWorld().getServer());
				Settlement s = st.settlements.get(npc.settlementId);
				npc.getWorld().getServer().getPlayerManager().broadcast(Text.translatable("event.livingkingdoms.ruler_slain",
						sp.getName(), Text.translatable("profession.livingkingdoms." + p.key()), s == null ? "?" : s.name), false);
			}
		}
	}

	private static void tickRespawns(ServerWorld w, KingdomState st) {
		Iterator<Respawn> it = respawns.iterator();
		while (it.hasNext()) {
			Respawn rs = it.next();
			if (w.getTime() < rs.time) continue;
			Settlement s = st.settlements.get(rs.settlementId);
			if (s == null) { it.remove(); continue; }
			if (!w.isChunkLoaded(s.pos)) continue;
			BlockPos spot = rulerSpot(s);
			spawn(w, rs.prof, spot.getX(), spot.getY(), spot.getZ(), s, new BlockPos(spot.getX(), s.pos.getY(), spot.getZ()));
			it.remove();
		}
	}
}
