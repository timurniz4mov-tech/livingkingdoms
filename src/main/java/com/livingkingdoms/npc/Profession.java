package com.livingkingdoms.npc;

public enum Profession {
	LABORER, SERVANT, MINER, FARMER, BUILDER,
	GUARD, SOLDIER,
	TRADER_LOCAL, TRADER_ROAD,
	TRAVELER, BANDIT,
	LORD, KING;

	public boolean isWorker() {
		return this == LABORER || this == SERVANT || this == MINER || this == FARMER || this == BUILDER;
	}

	public boolean isTrader() {
		return this == TRADER_LOCAL || this == TRADER_ROAD;
	}

	/** Ходят между поселениями. */
	public boolean isRoadMobile() {
		return this == TRAVELER || this == TRADER_ROAD;
	}

	public boolean isCombatant() {
		return this == GUARD || this == SOLDIER || this == BANDIT || this == LORD || this == KING;
	}

	public String key() {
		return name().toLowerCase();
	}

	public static Profession byName(String s) {
		try {
			return valueOf(s);
		} catch (IllegalArgumentException e) {
			return LABORER;
		}
	}
}
