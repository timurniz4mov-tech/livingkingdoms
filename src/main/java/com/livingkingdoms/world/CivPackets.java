package com.livingkingdoms.world;

import com.livingkingdoms.LivingKingdoms;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/** Передача карты королевств клиенту (для миникарты). */
public class CivPackets {
	public static final Identifier SYNC = LivingKingdoms.id("sync");

	public static void init() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(handler.player, server));
	}

	public static void syncAll(MinecraftServer server) {
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) send(p, server);
	}

	public static void send(ServerPlayerEntity p, MinecraftServer server) {
		KingdomState st = KingdomState.get(server);
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(st.kingdoms.size());
		for (KingdomState.Kingdom k : st.kingdoms.values()) {
			buf.writeInt(k.id);
			buf.writeString(k.name);
			buf.writeInt(k.color);
		}
		buf.writeVarInt(st.settlements.size());
		for (KingdomState.Settlement s : st.settlements.values()) {
			buf.writeInt(s.id);
			buf.writeString(s.name);
			buf.writeVarInt(s.type.ordinal());
			buf.writeInt(s.pos.getX());
			buf.writeInt(s.pos.getZ());
			buf.writeInt(s.radius);
			buf.writeInt(s.kingdomId);
		}
		buf.writeVarInt(st.wars.size());
		for (KingdomState.War w : st.wars) {
			buf.writeInt(w.attacker);
			buf.writeInt(w.defender);
		}
		ServerPlayNetworking.send(p, SYNC, buf);
	}
}
