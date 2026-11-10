package com.livingkingdoms.client;

import com.livingkingdoms.LivingKingdoms;
import com.livingkingdoms.npc.NpcEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

import java.util.EnumMap;
import java.util.Map;

public class NpcRenderer extends BipedEntityRenderer<NpcEntity, PlayerEntityModel<NpcEntity>> {
	private static final Map<com.livingkingdoms.npc.Profession, Identifier> TEXTURES = new EnumMap<>(com.livingkingdoms.npc.Profession.class);

	static {
		for (com.livingkingdoms.npc.Profession p : com.livingkingdoms.npc.Profession.values())
			TEXTURES.put(p, LivingKingdoms.id("textures/entity/npc/" + p.key() + ".png"));
	}

	public NpcRenderer(EntityRendererFactory.Context ctx) {
		super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
		this.addFeature(new ArmorFeatureRenderer<>(this,
				new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
				new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
				ctx.getModelManager()));
	}

	@Override
	public Identifier getTexture(NpcEntity entity) {
		return TEXTURES.get(entity.getProfession());
	}
}
