package com.livingkingdoms.npc;

import com.livingkingdoms.world.CivManager;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.EnumSet;

public class NpcGoals {

	/** Путешествие между поселениями (путники, дорожные торговцы, солдаты на войне, рейды бандитов). */
	public static class TravelGoal extends Goal {
		private final NpcEntity npc;
		private int repath;

		public TravelGoal(NpcEntity npc) {
			this.npc = npc;
			setControls(EnumSet.of(Control.MOVE));
		}

		@Override
		public boolean canStart() {
			if (npc.hasCustomer() || npc.getTarget() != null || npc.restTicks > 0) return false;
			return npc.wantsToTravel();
		}

		@Override
		public boolean shouldContinue() {
			return canStart();
		}

		@Override
		public void tick() {
			if (--repath > 0) return;
			repath = 20;
			ServerWorld w = (ServerWorld) npc.getWorld();
			if (npc.travelTarget == null) {
				npc.travelTarget = CivManager.pickDestination(npc);
				if (npc.travelTarget == null) {
					npc.travelTarget = npc.getBlockPos().add(npc.getRandom().nextInt(61) - 30, 0, npc.getRandom().nextInt(61) - 30);
					npc.travelSettlementId = -1;
				}
			}
			BlockPos t = npc.travelTarget;
			double dx = t.getX() + 0.5 - npc.getX();
			double dz = t.getZ() + 0.5 - npc.getZ();
			double dist = Math.sqrt(dx * dx + dz * dz);
			if (dist < 8) {
				npc.arrive();
				return;
			}
			double step = Math.min(dist, 18);
			int wx = (int) (npc.getX() + dx / dist * step);
			int wz = (int) (npc.getZ() + dz / dist * step);
			BlockPos probe = new BlockPos(wx, 64, wz);
			if (!w.isChunkLoaded(probe)) return; // дальше мир не загружен — ждём
			int wy = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, wx, wz);
			npc.getNavigation().startMovingTo(wx + 0.5, wy, wz + 0.5, 1.0);
		}

		@Override
		public void stop() {
			npc.getNavigation().stop();
		}
	}

	/** «Работа»: ходит по рабочим точкам вокруг дома и машет инструментом (днём). */
	public static class WorkGoal extends Goal {
		private final NpcEntity npc;
		private BlockPos spot;
		private int workTimer;

		public WorkGoal(NpcEntity npc) {
			this.npc = npc;
			setControls(EnumSet.of(Control.MOVE, Control.LOOK));
		}

		@Override
		public boolean canStart() {
			return npc.getProfession().isWorker() && npc.home != null && npc.getTarget() == null
					&& !npc.hasCustomer() && npc.getWorld().isDay();
		}

		@Override
		public boolean shouldContinue() {
			return canStart();
		}

		@Override
		public void start() {
			pickSpot();
		}

		private void pickSpot() {
			BlockPos h = npc.home;
			int x = h.getX() + npc.getRandom().nextInt(25) - 12;
			int z = h.getZ() + npc.getRandom().nextInt(25) - 12;
			int y = npc.getWorld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
			spot = new BlockPos(x, y, z);
			workTimer = 0;
			npc.getNavigation().startMovingTo(x + 0.5, y, z + 0.5, 0.8);
		}

		@Override
		public void tick() {
			if (spot == null) {
				pickSpot();
				return;
			}
			double d = npc.squaredDistanceTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
			if (d < 6.0 || npc.getNavigation().isIdle()) {
				npc.getLookControl().lookAt(spot.getX() + 0.5, spot.getY() - 0.5, spot.getZ() + 0.5);
				workTimer++;
				if (workTimer % 14 == 0) npc.swingHand(Hand.MAIN_HAND);
				if (workTimer > 120) pickSpot();
			}
		}
	}

	/** Бродит вокруг «дома» (деревня / замок / лагерь / место отдыха). */
	public static class HomeWanderGoal extends Goal {
		private final NpcEntity npc;

		public HomeWanderGoal(NpcEntity npc) {
			this.npc = npc;
			setControls(EnumSet.of(Control.MOVE));
		}

		private int radius() {
			return switch (npc.getProfession()) {
				case LORD, KING -> 5;
				case GUARD -> 16;
				case BANDIT -> 10;
				default -> 12;
			};
		}

		@Override
		public boolean canStart() {
			if (npc.home == null || npc.getTarget() != null || npc.hasCustomer()) return false;
			if (npc.getProfession().isRoadMobile() && npc.restTicks <= 0) return false;
			if (npc.getRandom().nextInt(50) != 0) return false;
			return npc.getNavigation().isIdle();
		}

		@Override
		public boolean shouldContinue() {
			return !npc.getNavigation().isIdle() && npc.getTarget() == null;
		}

		@Override
		public void start() {
			BlockPos h = npc.home;
			double dh = npc.squaredDistanceTo(h.getX() + 0.5, h.getY(), h.getZ() + 0.5);
			int r = radius();
			int x, z;
			if (dh > 30 * 30) { // слишком далеко — возвращаемся
				x = h.getX();
				z = h.getZ();
			} else {
				x = h.getX() + npc.getRandom().nextInt(2 * r + 1) - r;
				z = h.getZ() + npc.getRandom().nextInt(2 * r + 1) - r;
			}
			BlockPos probe = new BlockPos(x, 64, z);
			if (!npc.getWorld().isChunkLoaded(probe)) return;
			int y = npc.getWorld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
			npc.getNavigation().startMovingTo(x + 0.5, y, z + 0.5, 0.7);
		}
	}
}
