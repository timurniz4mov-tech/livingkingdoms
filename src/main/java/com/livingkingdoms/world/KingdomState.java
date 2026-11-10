package com.livingkingdoms.world;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.*;

/** Всё состояние цивилизаций мира (сохраняется в мир). */
public class KingdomState extends PersistentState {

	public enum SType { VILLAGE, CASTLE, LORDSHIP }

	public static class Settlement {
		public int id;
		public String name;
		public SType type;
		public BlockPos pos;
		public int radius;
		public int kingdomId = -1;
		public String owner = "";   // UUID игрока-лорда, "" = NPC
		public int tier;
	}

	public static class Kingdom {
		public int id;
		public String name;
		public int color;           // RGB
		public int capitalId = -1;
		public String kingPlayer = ""; // UUID игрока-короля, "" = NPC
	}

	public static class War {
		public int attacker, defender;
		public long startTime;
		public String reason;

		public int other(int k) { return k == attacker ? defender : attacker; }
		public boolean involves(int k) { return k == attacker || k == defender; }
	}

	public static class Road {
		public int x1, z1, x2, z2;
		public Set<Long> painted = new HashSet<>();
	}

	public static class PlayerData {
		public String job = "NONE";
		public int builderCounter;
		public long lastServantPay;
	}

	public final Map<Integer, Settlement> settlements = new LinkedHashMap<>();
	public final Map<Integer, Kingdom> kingdoms = new LinkedHashMap<>();
	public final List<War> wars = new ArrayList<>();
	public final List<Road> roads = new ArrayList<>();
	public final Set<Long> cells = new HashSet<>();
	public final Map<UUID, PlayerData> players = new HashMap<>();
	public int nextId = 1;

	public static KingdomState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager()
				.getOrCreate(KingdomState::fromNbt, KingdomState::new, "livingkingdoms_state");
	}

	public PlayerData player(UUID id) {
		return players.computeIfAbsent(id, k -> new PlayerData());
	}

	// ---------- запросы ----------

	public Settlement addSettlement(String name, SType type, BlockPos pos, int radius, int kingdomId, int tier) {
		Settlement s = new Settlement();
		s.id = nextId++;
		s.name = name;
		s.type = type;
		s.pos = pos;
		s.radius = radius;
		s.kingdomId = kingdomId;
		s.tier = tier;
		settlements.put(s.id, s);
		markDirty();
		return s;
	}

	public Kingdom addKingdom(String name, int color) {
		Kingdom k = new Kingdom();
		k.id = nextId++;
		k.name = name;
		k.color = color;
		kingdoms.put(k.id, k);
		markDirty();
		return k;
	}

	public Settlement nearestSettlement(BlockPos pos, double maxDist, SType onlyType) {
		Settlement best = null;
		double bd = maxDist * maxDist;
		for (Settlement s : settlements.values()) {
			if (onlyType != null && s.type != onlyType) continue;
			double d = horizSq(s.pos, pos);
			if (d < bd) { bd = d; best = s; }
		}
		return best;
	}

	public Settlement settlementAt(int x, int z) {
		for (Settlement s : settlements.values()) {
			if (Math.abs(s.pos.getX() - x) <= s.radius && Math.abs(s.pos.getZ() - z) <= s.radius) return s;
		}
		return null;
	}

	public Kingdom kingdomAt(int x, int z) {
		Settlement s = settlementAt(x, z);
		return s == null ? null : kingdoms.get(s.kingdomId);
	}

	public Kingdom nearestKingdom(BlockPos pos, double maxDist) {
		Kingdom best = null;
		double bd = maxDist * maxDist;
		for (Settlement s : settlements.values()) {
			Kingdom k = kingdoms.get(s.kingdomId);
			if (k == null) continue;
			double d = horizSq(s.pos, pos);
			if (d < bd) { bd = d; best = k; }
		}
		return best;
	}

	public Kingdom kingdomByName(String name) {
		for (Kingdom k : kingdoms.values()) if (k.name.equalsIgnoreCase(name.trim())) return k;
		return null;
	}

	public Kingdom kingdomOfPlayerKing(UUID id) {
		for (Kingdom k : kingdoms.values()) if (k.kingPlayer.equals(id.toString())) return k;
		return null;
	}

	public boolean isAtWar(int a, int b) {
		if (a < 0 || b < 0 || a == b) return false;
		for (War w : wars) if (w.involves(a) && w.involves(b)) return true;
		return false;
	}

	public List<War> warsOf(int k) {
		List<War> r = new ArrayList<>();
		for (War w : wars) if (w.involves(k)) r.add(w);
		return r;
	}

	public static double horizSq(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	// ---------- NBT ----------

	public static KingdomState fromNbt(NbtCompound nbt) {
		KingdomState st = new KingdomState();
		st.nextId = nbt.getInt("NextId");
		for (NbtElement e : nbt.getList("Settlements", NbtElement.COMPOUND_TYPE)) {
			NbtCompound c = (NbtCompound) e;
			Settlement s = new Settlement();
			s.id = c.getInt("Id");
			s.name = c.getString("Name");
			s.type = SType.valueOf(c.getString("Type"));
			s.pos = BlockPos.fromLong(c.getLong("Pos"));
			s.radius = c.getInt("Radius");
			s.kingdomId = c.getInt("Kingdom");
			s.owner = c.getString("Owner");
			s.tier = c.getInt("Tier");
			st.settlements.put(s.id, s);
		}
		for (NbtElement e : nbt.getList("Kingdoms", NbtElement.COMPOUND_TYPE)) {
			NbtCompound c = (NbtCompound) e;
			Kingdom k = new Kingdom();
			k.id = c.getInt("Id");
			k.name = c.getString("Name");
			k.color = c.getInt("Color");
			k.capitalId = c.getInt("Capital");
			k.kingPlayer = c.getString("King");
			st.kingdoms.put(k.id, k);
		}
		for (NbtElement e : nbt.getList("Wars", NbtElement.COMPOUND_TYPE)) {
			NbtCompound c = (NbtCompound) e;
			War w = new War();
			w.attacker = c.getInt("A");
			w.defender = c.getInt("D");
			w.startTime = c.getLong("Start");
			w.reason = c.getString("Reason");
			st.wars.add(w);
		}
		for (NbtElement e : nbt.getList("Roads", NbtElement.COMPOUND_TYPE)) {
			NbtCompound c = (NbtCompound) e;
			Road r = new Road();
			r.x1 = c.getInt("X1"); r.z1 = c.getInt("Z1"); r.x2 = c.getInt("X2"); r.z2 = c.getInt("Z2");
			for (long l : c.getLongArray("Painted")) r.painted.add(l);
			st.roads.add(r);
		}
		for (long l : nbt.getLongArray("Cells")) st.cells.add(l);
		for (NbtElement e : nbt.getList("Players", NbtElement.COMPOUND_TYPE)) {
			NbtCompound c = (NbtCompound) e;
			PlayerData p = new PlayerData();
			p.job = c.getString("Job");
			p.builderCounter = c.getInt("Builder");
			p.lastServantPay = c.getLong("Servant");
			st.players.put(UUID.fromString(c.getString("Id")), p);
		}
		return st;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		nbt.putInt("NextId", nextId);
		NbtList sl = new NbtList();
		for (Settlement s : settlements.values()) {
			NbtCompound c = new NbtCompound();
			c.putInt("Id", s.id);
			c.putString("Name", s.name);
			c.putString("Type", s.type.name());
			c.putLong("Pos", s.pos.asLong());
			c.putInt("Radius", s.radius);
			c.putInt("Kingdom", s.kingdomId);
			c.putString("Owner", s.owner);
			c.putInt("Tier", s.tier);
			sl.add(c);
		}
		nbt.put("Settlements", sl);
		NbtList kl = new NbtList();
		for (Kingdom k : kingdoms.values()) {
			NbtCompound c = new NbtCompound();
			c.putInt("Id", k.id);
			c.putString("Name", k.name);
			c.putInt("Color", k.color);
			c.putInt("Capital", k.capitalId);
			c.putString("King", k.kingPlayer);
			kl.add(c);
		}
		nbt.put("Kingdoms", kl);
		NbtList wl = new NbtList();
		for (War w : wars) {
			NbtCompound c = new NbtCompound();
			c.putInt("A", w.attacker);
			c.putInt("D", w.defender);
			c.putLong("Start", w.startTime);
			c.putString("Reason", w.reason);
			wl.add(c);
		}
		nbt.put("Wars", wl);
		NbtList rl = new NbtList();
		for (Road r : roads) {
			NbtCompound c = new NbtCompound();
			c.putInt("X1", r.x1); c.putInt("Z1", r.z1); c.putInt("X2", r.x2); c.putInt("Z2", r.z2);
			long[] arr = new long[r.painted.size()];
			int i = 0;
			for (long l : r.painted) arr[i++] = l;
			c.putLongArray("Painted", arr);
			rl.add(c);
		}
		nbt.put("Roads", rl);
		long[] cs = new long[cells.size()];
		int i = 0;
		for (long l : cells) cs[i++] = l;
		nbt.putLongArray("Cells", cs);
		NbtList pl = new NbtList();
		for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
			NbtCompound c = new NbtCompound();
			c.putString("Id", e.getKey().toString());
			c.putString("Job", e.getValue().job);
			c.putInt("Builder", e.getValue().builderCounter);
			c.putLong("Servant", e.getValue().lastServantPay);
			pl.add(c);
		}
		nbt.put("Players", pl);
		return nbt;
	}
}
