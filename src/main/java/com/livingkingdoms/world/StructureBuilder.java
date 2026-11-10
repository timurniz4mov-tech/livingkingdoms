package com.livingkingdoms.world;

import net.minecraft.block.*;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/** Процедурные постройки: деревня и замок строятся списком блоков (план), который выполняется по частям. */
public class StructureBuilder {

	public static class Plan {
		public final List<BlockPos> pos = new ArrayList<>();
		public final List<BlockState> st = new ArrayList<>();

		public void set(int x, int y, int z, BlockState s) {
			pos.add(new BlockPos(x, y, z));
			st.add(s);
		}

		public void set(int x, int y, int z, Block b) {
			set(x, y, z, b.getDefaultState());
		}

		public int size() { return pos.size(); }
	}

	public record Style(BlockState wall, BlockState pillar, Block stairs, BlockState roofFull, BlockState floor) {}

	public static final Style WOOD = new Style(Blocks.OAK_PLANKS.getDefaultState(), Blocks.OAK_LOG.getDefaultState(),
			Blocks.OAK_STAIRS, Blocks.OAK_PLANKS.getDefaultState(), Blocks.SPRUCE_PLANKS.getDefaultState());
	public static final Style MANOR = new Style(Blocks.STONE_BRICKS.getDefaultState(), Blocks.SPRUCE_LOG.getDefaultState(),
			Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_PLANKS.getDefaultState(), Blocks.DARK_OAK_PLANKS.getDefaultState());
	public static final Style BARRACKS = new Style(Blocks.COBBLESTONE.getDefaultState(), Blocks.OAK_LOG.getDefaultState(),
			Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICKS.getDefaultState(), Blocks.SPRUCE_PLANKS.getDefaultState());

	private static final BlockState AIR = Blocks.AIR.getDefaultState();
	private static final BlockState PATH = Blocks.DIRT_PATH.getDefaultState();

	// ---------------------------------------------------------------

	public static void flatten(Plan p, int cx, int cz, int r, int h, BlockState surface, BlockState fill) {
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				int x = cx + dx, z = cz + dz;
				for (int y = h - 8; y < h; y++) p.set(x, y, z, fill);
				p.set(x, h, z, surface);
				for (int y = h + 1; y <= h + 14; y++) p.set(x, y, z, AIR);
			}
		}
	}

	/** Дом. Пол лежит на высоте y, стены y+1..y+3. */
	public static void house(Plan p, int x0, int y, int z0, int w, int d, boolean doorSouth, Style s) {
		for (int x = x0; x < x0 + w; x++) {
			for (int z = z0; z < z0 + d; z++) {
				p.set(x, y, z, s.floor());
				p.set(x, y - 1, z, Blocks.COBBLESTONE);
				boolean edge = x == x0 || x == x0 + w - 1 || z == z0 || z == z0 + d - 1;
				boolean corner = (x == x0 || x == x0 + w - 1) && (z == z0 || z == z0 + d - 1);
				for (int yy = 1; yy <= 3; yy++) {
					if (edge) p.set(x, y + yy, z, corner ? s.pillar() : s.wall());
					else p.set(x, y + yy, z, AIR);
				}
			}
		}
		// окна
		int mz = z0 + d / 2, mx = x0 + w / 2;
		p.set(x0, y + 2, mz, Blocks.GLASS_PANE);
		p.set(x0 + w - 1, y + 2, mz, Blocks.GLASS_PANE);
		p.set(mx, y + 2, doorSouth ? z0 : z0 + d - 1, Blocks.GLASS_PANE);
		// дверь
		int dz = doorSouth ? z0 + d - 1 : z0;
		Direction face = doorSouth ? Direction.SOUTH : Direction.NORTH;
		BlockState door = Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING, face);
		p.set(mx, y + 1, dz, door.with(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		p.set(mx, y + 2, dz, door.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		p.set(mx + 1, y + 2, dz + (doorSouth ? 1 : -1),
				Blocks.WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, face));
		// крыша (конёк вдоль Z)
		for (int i = 0; ; i++) {
			int left = x0 - 1 + i;
			int right = x0 + w - i;
			if (left > right) break;
			int yy = y + 4 + i;
			for (int z = z0 - 1; z <= z0 + d; z++) {
				if (left == right) {
					p.set(left, yy, z, s.roofFull());
				} else {
					p.set(left, yy, z, s.stairs().getDefaultState().with(StairsBlock.FACING, Direction.EAST));
					p.set(right, yy, z, s.stairs().getDefaultState().with(StairsBlock.FACING, Direction.WEST));
				}
			}
			// фронтоны
			for (int x = left + 1; x < right; x++) {
				p.set(x, yy, z0, s.roofFull());
				p.set(x, yy, z0 + d - 1, s.roofFull());
			}
		}
		// интерьер: кровать, верстак, сундук
		int bedZ = doorSouth ? z0 + 1 : z0 + d - 2;
		int footZ = bedZ + (doorSouth ? 1 : -1);
		Direction toHead = doorSouth ? Direction.NORTH : Direction.SOUTH;
		BlockState bed = Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, toHead);
		p.set(x0 + 1, y + 1, bedZ, bed.with(BedBlock.PART, BedPart.HEAD));
		p.set(x0 + 1, y + 1, footZ, bed.with(BedBlock.PART, BedPart.FOOT));
		p.set(x0 + w - 2, y + 1, bedZ, Blocks.CRAFTING_TABLE);
		if (d >= 5) p.set(x0 + w - 2, y + 1, footZ, Blocks.CHEST);
	}

	public static void well(Plan p, int cx, int h, int cz) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) {
					p.set(cx, h, cz, Blocks.WATER);
				} else {
					p.set(cx + dx, h, cz + dz, Blocks.COBBLESTONE);
					p.set(cx + dx, h + 1, cz + dz, Blocks.COBBLESTONE);
				}
			}
		}
		for (int sx = -1; sx <= 1; sx += 2) {
			for (int sz = -1; sz <= 1; sz += 2) {
				p.set(cx + sx, h + 2, cz + sz, Blocks.OAK_FENCE);
				p.set(cx + sx, h + 3, cz + sz, Blocks.OAK_FENCE);
			}
		}
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++)
				p.set(cx + dx, h + 4, cz + dz, Blocks.OAK_SLAB);
	}

	public static void farm(Plan p, int x0, int h, int z0) {
		for (int x = x0; x < x0 + 5; x++) {
			for (int z = z0; z < z0 + 5; z++) {
				if (x == x0 + 2 && z == z0 + 2) {
					p.set(x, h, z, Blocks.WATER);
				} else {
					p.set(x, h, z, Blocks.FARMLAND.getDefaultState().with(FarmlandBlock.MOISTURE, 7));
					p.set(x, h + 1, z, Blocks.WHEAT.getDefaultState().with(CropBlock.AGE, 7));
				}
			}
		}
	}

	// ---------------------------------------------------------------

	public static void village(Plan p, int cx, int h, int cz, BlockState surface, BlockState fill) {
		flatten(p, cx, cz, 20, h, surface, fill);
		for (int i = -19; i <= 19; i++) {
			p.set(cx + i, h, cz, PATH);
			p.set(cx + i, h, cz + 1, PATH);
			p.set(cx, h, cz + i, PATH);
			p.set(cx + 1, h, cz + i, PATH);
		}
		well(p, cx, h, cz);
		// северный ряд, двери на улицу (на юг)
		house(p, cx - 16, h, cz - 14, 5, 5, true, WOOD);
		house(p, cx - 8, h, cz - 14, 5, 5, true, WOOD);
		house(p, cx + 4, h, cz - 14, 5, 5, true, WOOD);
		house(p, cx + 11, h, cz - 14, 5, 5, true, WOOD);
		// южный ряд, двери на улицу (на север)
		house(p, cx - 16, h, cz + 8, 5, 5, false, WOOD);
		house(p, cx - 8, h, cz + 8, 5, 5, false, WOOD);
		house(p, cx + 4, h, cz + 8, 9, 7, false, MANOR); // усадьба лорда
		// поля
		farm(p, cx - 19, h, cz - 6);
		farm(p, cx + 14, h, cz - 6);
	}

	public static void castle(Plan p, int cx, int h, int cz, BlockState surface, BlockState fill) {
		flatten(p, cx, cz, 20, h, surface, fill);
		BlockState sb = Blocks.STONE_BRICKS.getDefaultState();

		// внешняя стена
		for (int i = -16; i <= 16; i++) {
			for (int y = 1; y <= 6; y++) {
				p.set(cx + i, h + y, cz - 16, sb);
				p.set(cx + i, h + y, cz + 16, sb);
				p.set(cx - 16, h + y, cz + i, sb);
				p.set(cx + 16, h + y, cz + i, sb);
			}
			if ((i & 1) == 0) {
				p.set(cx + i, h + 7, cz - 16, sb);
				p.set(cx + i, h + 7, cz + 16, sb);
				p.set(cx - 16, h + 7, cz + i, sb);
				p.set(cx + 16, h + 7, cz + i, sb);
			}
		}
		// ворота на юге
		for (int x = -1; x <= 1; x++) for (int y = 1; y <= 4; y++) p.set(cx + x, h + y, cz + 16, AIR);
		p.set(cx - 2, h + 1, cz + 18, Blocks.TORCH);
		// угловые башни
		for (int sx = -1; sx <= 1; sx += 2) {
			for (int sz = -1; sz <= 1; sz += 2) {
				int tx = cx + sx * 16, tz = cz + sz * 16;
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
						for (int y = 1; y <= 9; y++) p.set(tx + dx, h + y, tz + dz, edge ? sb : AIR);
						p.set(tx + dx, h + 10, tz + dz, sb);
						if (edge && ((dx + dz) & 1) == 0) p.set(tx + dx, h + 11, tz + dz, sb);
					}
				}
			}
		}
		// донжон (главная башня)
		for (int x = -5; x <= 5; x++) {
			for (int z = -5; z <= 5; z++) {
				boolean edge = Math.abs(x) == 5 || Math.abs(z) == 5;
				p.set(cx + x, h, cz + z, Blocks.DARK_OAK_PLANKS);
				for (int y = 1; y <= 10; y++) p.set(cx + x, h + y, cz + z, edge ? sb : AIR);
				p.set(cx + x, h + 11, cz + z, sb);
				if (edge && ((x + z) & 1) == 0) p.set(cx + x, h + 12, cz + z, sb);
			}
		}
		for (int xx : new int[]{-3, 3}) {
			for (int yy : new int[]{4, 8}) {
				p.set(cx + xx, h + yy, cz - 5, Blocks.IRON_BARS);
				p.set(cx + xx, h + yy, cz + 5, Blocks.IRON_BARS);
				p.set(cx - 5, h + yy, cz + xx, Blocks.IRON_BARS);
				p.set(cx + 5, h + yy, cz + xx, Blocks.IRON_BARS);
			}
		}
		for (int x = -1; x <= 1; x++) for (int y = 1; y <= 3; y++) p.set(cx + x, h + y, cz + 5, AIR);
		for (int z = -3; z <= 5; z++) p.set(cx, h + 1, cz + z, Blocks.RED_CARPET);
		p.set(cx, h + 1, cz - 4, Blocks.GOLD_BLOCK); // трон
		for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) p.set(cx + sx * 4, h + 1, cz + sz * 4, Blocks.TORCH);
		// дорожка от ворот к донжону
		for (int z = 6; z <= 15; z++) for (int x = -1; x <= 1; x++) p.set(cx + x, h, cz + z, Blocks.GRAVEL);
		// казармы и дом слуг во дворе
		house(p, cx - 13, h, cz - 2, 5, 5, true, BARRACKS);
		house(p, cx + 9, h, cz - 2, 5, 5, true, BARRACKS);
		house(p, cx - 13, h, cz + 8, 5, 5, false, WOOD);
		house(p, cx + 9, h, cz + 8, 5, 5, false, WOOD);
	}
}
