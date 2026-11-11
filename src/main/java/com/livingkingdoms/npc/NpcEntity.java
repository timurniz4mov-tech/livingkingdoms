package com.livingkingdoms.npc;

import com.livingkingdoms.registry.ModItems;
import com.livingkingdoms.world.CivManager;
import com.livingkingdoms.world.KingdomState;
import com.livingkingdoms.world.Jobs;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.world.World;

public class NpcEntity extends MerchantEntity {
	private static final TrackedData<String> PROFESSION =
			DataTracker.registerData(NpcEntity.class, TrackedDataHandlerRegistry.STRING);

	public int kingdomId = -1;
	public int settlementId = -1;
	public int tier = 0;
	public int lastSettlementId = -1;
	public int travelSettlementId = -1;
	public BlockPos home;          // центр, возле которого NPC бродит / отдыхает
	public BlockPos base;          // «родное» поселение
	public BlockPos travelTarget;  // куда идёт сейчас
	public int restTicks;          // сколько ещё отдыхает после прихода

	public NpcEntity(EntityType<? extends NpcEntity> type, World world) {
		super(type, world);
		((MobNavigation) this.getNavigation()).setCanPathThroughDoors(true);
		this.getNavigation().setCanSwim(true);
		this.setPersistent();
	}

	public static DefaultAttributeContainer.Builder createNpcAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0)
				.add(EntityAttributes.GENERIC_ATTACK_KNOCKBACK, 0.0)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 40.0);
	}

	@Override
	protected void initDataTracker() {
		super.initDataTracker();
		this.dataTracker.startTracking(PROFESSION, Profession.LABORER.name());
	}

	public Profession getProfession() {
		return Profession.byName(this.dataTracker.get(PROFESSION));
	}

	public void setProfession(Profession p) {
		this.dataTracker.set(PROFESSION, p.name());
	}

	// ---------------- ИИ ----------------

	@Override
	protected void initGoals() {
		this.goalSelector.add(0, new SwimGoal(this));
		this.goalSelector.add(1, new StopFollowingCustomerGoal(this));
		this.goalSelector.add(1, new EscapeDangerGoal(this, 1.3) {
			@Override
			public boolean canStart() {
				return !getProfession().isCombatant() && super.canStart();
			}
		});
		this.goalSelector.add(2, new LookAtCustomerGoal(this));
		this.goalSelector.add(3, new MeleeAttackGoal(this, 1.15, true));
		this.goalSelector.add(4, new NpcGoals.TravelGoal(this));
		this.goalSelector.add(5, new NpcGoals.WorkGoal(this));
		this.goalSelector.add(6, new NpcGoals.HomeWanderGoal(this));
		this.goalSelector.add(7, new WanderAroundFarGoal(this, 0.6) {
			@Override
			public boolean canStart() {
				return home == null && !getProfession().isRoadMobile() && super.canStart();
			}
		});
		this.goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
		this.goalSelector.add(9, new LookAroundGoal(this));

		this.targetSelector.add(1, new RevengeGoal(this) {
			@Override
			public boolean canStart() {
				return getProfession().isCombatant() && super.canStart();
			}
		});
		this.targetSelector.add(2, new ActiveTargetGoal<>(this, LivingEntity.class, 10, true, false, this::shouldTarget));
	}

	public boolean atWarWith(NpcEntity o) {
		if (kingdomId < 0 || o.kingdomId < 0 || kingdomId == o.kingdomId) return false;
		if (getWorld().getServer() == null) return false;
		return KingdomState.get(getWorld().getServer()).isAtWar(kingdomId, o.kingdomId);
	}

	public boolean atWarNow() {
		if (kingdomId < 0 || getWorld().getServer() == null) return false;
		return !KingdomState.get(getWorld().getServer()).warsOf(kingdomId).isEmpty();
	}

	private boolean shouldTarget(LivingEntity t) {
		if (t == this || !t.isAlive()) return false;
		Profession p = getProfession();
		if (t instanceof NpcEntity n) {
			Profession np = n.getProfession();
			if (p == Profession.BANDIT) return np != Profession.BANDIT;
			if (np == Profession.BANDIT) return p == Profession.GUARD || p == Profession.SOLDIER;
			if (atWarWith(n)) {
				if (p == Profession.SOLDIER) return true;
				// стража только отбивается от вражеских воинов
				return p == Profession.GUARD && (np == Profession.SOLDIER);
			}
			return false;
		}
		if (t instanceof PlayerEntity) return p == Profession.BANDIT;
		if (t instanceof HostileEntity) return p == Profession.GUARD || p == Profession.SOLDIER;
		return false;
	}

	public boolean wantsToTravel() {
		return switch (getProfession()) {
			case TRAVELER, TRADER_ROAD -> true;
			case BANDIT -> travelTarget != null;
			case SOLDIER -> {
				if (kingdomId < 0) yield false;
				if (atWarNow()) yield true;
				if (base != null && home != null && base.getSquaredDistance(getBlockPos()) > 40 * 40) yield true;
				yield travelTarget != null;
			}
			default -> false;
		};
	}

	public void arrive() {
		home = travelTarget;
		lastSettlementId = travelSettlementId;
		travelTarget = null;
		travelSettlementId = -1;
		restTicks = getProfession() == Profession.SOLDIER ? 400 : 1200 + getRandom().nextInt(2400);
		getNavigation().stop();
	}

	@Override
	public void tick() {
		super.tick();
		if (!getWorld().isClient && restTicks > 0) restTicks--;
	}

	// ---------------- Взаимодействие и торговля ----------------

	@Override
	public ActionResult interactMob(PlayerEntity player, Hand hand) {
		if (!this.isAlive() || this.hasCustomer()) return super.interactMob(player, hand);
		Profession p = getProfession();
		if (p == Profession.BANDIT) return ActionResult.PASS;
		if (getWorld().isClient) return ActionResult.SUCCESS;
		if (p.isTrader()) {
			if (getTarget() != null) {
				player.sendMessage(Text.translatable("npc.livingkingdoms.busy"), true);
			} else {
				this.setCustomer(player);
				this.sendOffers(player, this.getDisplayName(), 1);
			}
			return ActionResult.SUCCESS;
		}
		String kingdom = "-", settlement = "-";
		if (getWorld().getServer() != null) {
			KingdomState st = KingdomState.get(getWorld().getServer());
			KingdomState.Kingdom k = st.kingdoms.get(kingdomId);
			if (k != null) kingdom = k.name;
			KingdomState.Settlement s = st.settlements.get(settlementId);
			if (s != null) settlement = s.name;
		}
		player.sendMessage(Text.translatable("npc.livingkingdoms.talk." + p.key(), kingdom, settlement), false);
		return ActionResult.SUCCESS;
	}

	@Override
	public Text getName() {
		if (this.hasCustomName()) return super.getName();
		return Text.translatable("profession.livingkingdoms." + getProfession().key());
	}

	private void offer(TradeOfferList list, net.minecraft.item.Item buy, int bc, net.minecraft.item.Item sell, int sc) {
		list.add(new TradeOffer(new ItemStack(buy, bc), new ItemStack(sell, sc), 12, 2, 0.05f));
	}

	@Override
	protected void fillRecipes() {
		TradeOfferList l = getOffers();
		var C = ModItems.COIN;
		if (getProfession() == Profession.TRADER_LOCAL) {
			// игрок продаёт торговцу — получает монеты
			offer(l, Items.WHEAT, 16, C, 1);
			offer(l, Items.COAL, 8, C, 1);
			offer(l, Items.OAK_LOG, 8, C, 1);
			offer(l, Items.COBBLESTONE, 32, C, 1);
			offer(l, Items.IRON_INGOT, 3, C, 2);
			offer(l, Items.GOLD_INGOT, 2, C, 3);
			offer(l, Items.DIAMOND, 1, C, 10);
			// игрок покупает за монеты
			offer(l, C, 1, Items.BREAD, 4);
			offer(l, C, 1, Items.TORCH, 16);
			offer(l, C, 2, Items.COOKED_BEEF, 4);
			offer(l, C, 2, Items.OAK_PLANKS, 32);
			offer(l, C, 3, Items.GLASS, 16);
			offer(l, C, 3, Items.WOODEN_PICKAXE, 1);
			offer(l, C, 6, Items.STONE_PICKAXE, 1);
			offer(l, C, 15, Items.IRON_PICKAXE, 1);
			offer(l, C, 60, Items.DIAMOND_PICKAXE, 1);
			offer(l, C, 4, Items.WOODEN_SWORD, 1);
			offer(l, C, 8, Items.STONE_SWORD, 1);
			offer(l, C, 20, Items.IRON_SWORD, 1);
			offer(l, C, 70, Items.DIAMOND_SWORD, 1);
			offer(l, C, 10, Items.LEATHER_CHESTPLATE, 1);
			offer(l, C, 25, Items.IRON_CHESTPLATE, 1);
			offer(l, C, 90, Items.DIAMOND_CHESTPLATE, 1);
			offer(l, C, 10, Items.BOW, 1);
			offer(l, C, 2, Items.ARROW, 16);
		} else {
			offer(l, Items.EMERALD, 1, C, 3);
			offer(l, Items.DIAMOND, 1, C, 12);
			offer(l, Items.GOLD_INGOT, 1, C, 2);
			offer(l, C, 10, Items.EMERALD, 1);
			offer(l, C, 12, Items.SADDLE, 1);
			offer(l, C, 10, Items.NAME_TAG, 1);
			offer(l, C, 25, Items.GOLDEN_APPLE, 1);
			offer(l, C, 20, Items.ENDER_PEARL, 1);
			offer(l, C, 15, Items.SPYGLASS, 1);
			offer(l, C, 30, Items.EXPERIENCE_BOTTLE, 8);
			offer(l, C, 8, Items.COMPASS, 1);
			offer(l, C, 8, Items.MAP, 1);
		}
	}

	@Override
	protected void afterUsing(TradeOffer offer) {
		// бонус игроку-торговцу
		if (getCustomer() instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
			Jobs.onTradeDone(sp);
		}
	}

	@Override
	public PassiveEntity createChild(ServerWorld world, PassiveEntity entity) {
		return null;
	}

	// ---------------- Смерть, звуки ----------------

	@Override
	public void onDeath(DamageSource source) {
		super.onDeath(source);
		if (!getWorld().isClient && getProfession() == Profession.BANDIT) {
			this.dropStack(new ItemStack(ModItems.COIN, 1 + getRandom().nextInt(4)));
		}
		if (!getWorld().isClient) CivManager.onNpcDeath(this, source);
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return getProfession() == Profession.BANDIT ? SoundEvents.ENTITY_PILLAGER_AMBIENT : SoundEvents.ENTITY_VILLAGER_AMBIENT;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return getProfession() == Profession.BANDIT ? SoundEvents.ENTITY_PILLAGER_HURT : SoundEvents.ENTITY_VILLAGER_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return getProfession() == Profession.BANDIT ? SoundEvents.ENTITY_PILLAGER_DEATH : SoundEvents.ENTITY_VILLAGER_DEATH;
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}

	// ---------------- Сохранение ----------------

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putString("Prof", getProfession().name());
		nbt.putInt("Kingdom", kingdomId);
		nbt.putInt("Settlement", settlementId);
		nbt.putInt("Tier", tier);
		nbt.putInt("LastSettlement", lastSettlementId);
		nbt.putInt("Rest", restTicks);
		if (home != null) nbt.putLong("Home", home.asLong());
		if (base != null) nbt.putLong("Base", base.asLong());
		if (travelTarget != null) nbt.putLong("Travel", travelTarget.asLong());
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		if (nbt.contains("Prof")) setProfession(Profession.byName(nbt.getString("Prof")));
		kingdomId = nbt.getInt("Kingdom");
		settlementId = nbt.getInt("Settlement");
		tier = nbt.getInt("Tier");
		lastSettlementId = nbt.getInt("LastSettlement");
		restTicks = nbt.getInt("Rest");
		home = nbt.contains("Home") ? BlockPos.fromLong(nbt.getLong("Home")) : null;
		base = nbt.contains("Base") ? BlockPos.fromLong(nbt.getLong("Base")) : null;
		travelTarget = nbt.contains("Travel") ? BlockPos.fromLong(nbt.getLong("Travel")) : null;
	}
}
