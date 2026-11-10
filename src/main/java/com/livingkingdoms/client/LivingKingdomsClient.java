package com.livingkingdoms.client;

import com.livingkingdoms.registry.ModEntities;
import com.livingkingdoms.world.CivPackets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class LivingKingdomsClient implements ClientModInitializer {
	private static KeyBinding zoomKey;
	private static KeyBinding toggleKey;

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ModEntities.NPC, NpcRenderer::new);

		ClientPlayNetworking.registerGlobalReceiver(CivPackets.SYNC, (client, handler, buf, sender) -> {
			// разбор в потоке сети нельзя откладывать — буфер освободится, поэтому читаем сразу
			ClientData.read(buf);
		});

		zoomKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.livingkingdoms.zoom",
				InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "category.livingkingdoms"));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.livingkingdoms.toggle",
				InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "category.livingkingdoms"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (zoomKey.wasPressed()) MiniMap.cycleScale();
			while (toggleKey.wasPressed()) MiniMap.toggle();
			MiniMap.tick(client);
		});

		HudRenderCallback.EVENT.register((ctx, tickDelta) -> MiniMap.render(ctx, MinecraftClient.getInstance()));
	}
}
