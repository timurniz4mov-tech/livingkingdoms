package com.livingkingdoms.client;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Данные о королевствах, пришедшие с сервера. */
public class ClientData {
	public static class K { public int id, color; public String name; }
	public static class S { public int id, type, x, z, radius, kingdom; public String name; }

	public static volatile Map<Integer, K> kingdoms = new HashMap<>();
	public static volatile List<S> settlements = new ArrayList<>();
	public static volatile List<int[]> wars = new ArrayList<>();

	public static void read(PacketByteBuf buf) {
		Map<Integer, K> ks = new HashMap<>();
		int n = buf.readVarInt();
		for (int i = 0; i < n; i++) {
			K k = new K();
			k.id = buf.readInt();
			k.name = buf.readString();
			k.color = buf.readInt();
			ks.put(k.id, k);
		}
		List<S> ss = new ArrayList<>();
		n = buf.readVarInt();
		for (int i = 0; i < n; i++) {
			S s = new S();
			s.id = buf.readInt();
			s.name = buf.readString();
			s.type = buf.readVarInt();
			s.x = buf.readInt();
			s.z = buf.readInt();
			s.radius = buf.readInt();
			s.kingdom = buf.readInt();
			ss.add(s);
		}
		List<int[]> ws = new ArrayList<>();
		n = buf.readVarInt();
		for (int i = 0; i < n; i++) ws.add(new int[]{buf.readInt(), buf.readInt()});
		kingdoms = ks;
		settlements = ss;
		wars = ws;
	}

	public static S settlementAt(int x, int z) {
		for (S s : settlements) if (Math.abs(s.x - x) <= s.radius && Math.abs(s.z - z) <= s.radius) return s;
		return null;
	}

	public static boolean atWar(int kingdomId) {
		for (int[] w : wars) if (w[0] == kingdomId || w[1] == kingdomId) return true;
		return false;
	}
}
