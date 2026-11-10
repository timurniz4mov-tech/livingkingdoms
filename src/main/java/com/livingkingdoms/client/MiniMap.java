package com.livingkingdoms.client;

import com.livingkingdoms.LivingKingdoms;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

/** Миникарта: рельеф + границы королевств + города/замки. Клавиша M — масштаб, N — вкл/выкл. */
public class MiniMap {
	private static final int SIZE = 96;
	private static final int[] SCALES = {1, 2, 4, 8};
	private static final Identifier TEX_ID = LivingKingdoms.id("minimap_dynamic");

	private static NativeImageBackedTexture texture;
	private static int scaleIdx = 1;
	public static boolean enabled = true;
	private static int tickCounter;
	private static int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE, lastScale = -1;

	public static void cycleScale() {
		scaleIdx = (scaleIdx + 1) % SCALES.length;
	}

	public static void toggle() {
		enabled = !enabled;
	}

	private static void ensureTexture(MinecraftClient mc) {
		if (texture == null) {
			texture = new NativeImageBackedTexture(SIZE, SIZE, true);
			mc.getTextureManager().registerTexture(TEX_ID, texture);
		}
	}

	public static void tick(MinecraftClient mc) {
		if (!enabled || mc.world == null || mc.player == null) return;
		ensureTexture(mc);
		tickCounter++;
		int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
		boolean moved = Math.abs(px - lastX) > 2 || Math.abs(pz - lastZ) > 2 || lastScale != scaleIdx;
		if (tickCounter % 20 != 0 && !(moved && tickCounter % 5 == 0)) return;
		lastX = px;
		lastZ = pz;
		lastScale = scaleIdx;
		redraw(mc.world, px, pz, mc.player.getBlockY());
	}

	private static void redraw(ClientWorld world, int px, int pz, int py) {
		NativeImage img = texture.getImage();
		if (img == null) return;
		int scale = SCALES[scaleIdx];
		int half = SIZE / 2;
		BlockPos.Mutable mp = new BlockPos.Mutable();
		for (int ix = 0; ix < SIZE; ix++) {
			for (int iz = 0; iz < SIZE; iz++) {
				int wx = px + (ix - half) * scale;
				int wz = pz + (iz - half) * scale;
				Chunk chunk = world.getChunk(wx >> 4, wz >> 4, ChunkStatus.FULL, false);
				int rgb = 0x202020;
				if (chunk != null) {
					int top = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, wx, wz);
					mp.set(wx, top - 1, wz);
					BlockState state = chunk.getBlockState(mp);
					int c = state.getMapColor(world, mp).color;
					if (c != 0) {
						float shade = MathHelper.clamp(0.85f + (top - py) * 0.006f, 0.6f, 1.15f);
						int r = Math.min(255, (int) (((c >> 16) & 255) * shade));
						int g = Math.min(255, (int) (((c >> 8) & 255) * shade));
						int b = Math.min(255, (int) ((c & 255) * shade));
						rgb = (r << 16) | (g << 8) | b;
					}
				}
				int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
				img.setColor(ix, iz, 0xFF000000 | (b << 16) | (g << 8) | r);
			}
		}
		texture.upload();
	}

	public static void render(DrawContext ctx, MinecraftClient mc) {
		if (!enabled || mc.world == null || mc.player == null || texture == null || mc.options.hudHidden) return;
		int sw = ctx.getScaledWindowWidth();
		int size = SIZE + 0;
		int x0 = sw - size - 8, y0 = 8;
		int scale = SCALES[scaleIdx];
		double px = mc.player.getX(), pz = mc.player.getZ();

		ctx.fill(x0 - 2, y0 - 2, x0 + size + 2, y0 + size + 2, 0xFF111111);
		ctx.drawTexture(TEX_ID, x0, y0, 0, 0, size, size, size, size);

		// территории
		for (ClientData.S s : ClientData.settlements) {
			ClientData.K k = ClientData.kingdoms.get(s.kingdom);
			int color = k == null ? 0x999999 : k.color;
			int ax = x0 + (int) ((s.x - s.radius - px) / scale + SIZE / 2.0);
			int az = y0 + (int) ((s.z - s.radius - pz) / scale + SIZE / 2.0);
			int bx = x0 + (int) ((s.x + s.radius - px) / scale + SIZE / 2.0);
			int bz = y0 + (int) ((s.z + s.radius - pz) / scale + SIZE / 2.0);
			int cx1 = Math.max(ax, x0), cz1 = Math.max(az, y0), cx2 = Math.min(bx, x0 + size), cz2 = Math.min(bz, y0 + size);
			if (cx1 < cx2 && cz1 < cz2) {
				ctx.fill(cx1, cz1, cx2, cz2, 0x44000000 | color);
				// рамка
				int border = 0xCC000000 | color;
				if (ax >= x0 && ax < x0 + size) ctx.fill(ax, cz1, ax + 1, cz2, border);
				if (bx > x0 && bx <= x0 + size) ctx.fill(bx - 1, cz1, bx, cz2, border);
				if (az >= y0 && az < y0 + size) ctx.fill(cx1, az, cx2, az + 1, border);
				if (bz > y0 && bz <= y0 + size) ctx.fill(cx1, bz - 1, cx2, bz, border);
			}
			// маркер поселения
			int mx = x0 + (int) ((s.x - px) / scale + SIZE / 2.0);
			int mz = y0 + (int) ((s.z - pz) / scale + SIZE / 2.0);
			if (mx >= x0 + 2 && mx < x0 + size - 2 && mz >= y0 + 2 && mz < y0 + size - 2) {
				int r = s.type == 1 ? 3 : 2;
				ctx.fill(mx - r - 1, mz - r - 1, mx + r + 1, mz + r + 1, 0xFFFFFFFF);
				ctx.fill(mx - r, mz - r, mx + r, mz + r, 0xFF000000 | color);
			}
		}
		// игрок
		int cx = x0 + SIZE / 2, cz = y0 + SIZE / 2;
		ctx.fill(cx - 2, cz - 2, cx + 2, cz + 2, 0xFFFFFFFF);
		ctx.fill(cx - 1, cz - 1, cx + 1, cz + 1, 0xFFFF2020);

		// подпись
		ClientData.S here = ClientData.settlementAt((int) px, (int) pz);
		int ty = y0 + size + 4;
		var font = mc.textRenderer;
		if (here != null) {
			ClientData.K k = ClientData.kingdoms.get(here.kingdom);
			String name = k == null ? here.name : here.name + " (" + k.name + ")";
			ctx.drawTextWithShadow(font, Text.literal(name), x0, ty, 0xFFFFFF);
			if (k != null && ClientData.atWar(k.id)) {
				ctx.drawTextWithShadow(font, Text.translatable("hud.livingkingdoms.war"), x0, ty + 10, 0xFF5555);
			}
		}
		ctx.drawTextWithShadow(font, Text.literal("1:" + scale), x0 + size - 20, y0 + size - 10, 0xFFFFFF);
	}
}
