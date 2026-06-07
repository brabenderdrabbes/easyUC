package de.msph.noriskpotionhud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class NoRiskPotionHudClient implements ClientModInitializer {
	private static final String MOD_ID = "easyuc";
	private static final String OLD_MOD_ID = "norisk_potion_hud";
	private static final int PANEL_WIDTH = 112;
	private static final int ROW_HEIGHT = 24;
	private static final int ICON_SIZE = 18;
	private static final int RIGHT_MARGIN = 6;
	private static final int TOP_MARGIN = 34;
	private static final int MINE_WIDTH = 120;
	private static final int MINE_HEIGHT = 12;
	private static final int[] COLORS = {
			0xFFFFFFFF,
			0xFFFFD966,
			0xFF7ED957,
			0xFF66CCFF,
			0xFFFF66CC,
			0xFFFF6666
	};
	private static final String[] COLOR_NAMES = {
			"White",
			"Gold",
			"Green",
			"Blue",
			"Pink",
			"Red"
	};
	private static final Pattern MINE_PAYDAY_PATTERN = Pattern.compile("\\[PayDay\\] Du bekommst deine Mine Einnahmen von ([\\d.,]+)\\$ am PayDay ausgezahlt\\.");
	private static final String PAYDAY_RESET_TEXT = "Zeit seit PayDay: 0/60 Minuten";
	private static final int COMMAND_SEND_DELAY_TICKS = 10;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HudConfig CONFIG = new HudConfig();
	private static final Queue<String> PENDING_COMMANDS = new ArrayDeque<>();
	private static long minePaydayMoney;
	private static int commandSendCooldown;
	private static KeyBinding openMenuKey;

	@Override
	public void onInitializeClient() {
		CONFIG.load();
		openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.easyuc.open_menu",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_PAGE_DOWN,
				KeyBinding.Category.MISC
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openMenuKey.wasPressed()) {
				client.setScreen(new PotionHudConfigScreen());
			}

			sendPendingCommand(client);
		});

		HudRenderCallback.EVENT.register(NoRiskPotionHudClient::renderPotionHud);
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> handleIncomingMessage(message.getString()));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> handleIncomingMessage(message.getString()));
		registerShortcutCommands();
	}

	private static void handleIncomingMessage(String message) {
		if (message.contains(PAYDAY_RESET_TEXT)) {
			minePaydayMoney = 0;
			return;
		}

		Matcher matcher = MINE_PAYDAY_PATTERN.matcher(message);
		if (matcher.find()) {
			minePaydayMoney += parseMoney(matcher.group(1));
		}
	}

	private static long parseMoney(String moneyText) {
		String digits = moneyText.replaceAll("[^0-9]", "");

		if (digits.isEmpty()) {
			return 0;
		}

		try {
			return Long.parseLong(digits);
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	private static void registerShortcutCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(
				ClientCommandManager.literal("gd")
						.then(ClientCommandManager.argument("player", StringArgumentType.word())
								.suggests((context, builder) -> CommandSource.suggestMatching(context.getSource().getPlayerNames(), builder))
								.then(ClientCommandManager.argument("item", StringArgumentType.word())
										.suggests((context, builder) -> CommandSource.suggestMatching(new String[] { "Koks", "Gras" }, builder))
										.then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(1))
												.executes(context -> executeDrugShortcut(
														context.getSource(),
														StringArgumentType.getString(context, "player"),
														StringArgumentType.getString(context, "item"),
														IntegerArgumentType.getInteger(context, "amount")
												)))))
			);
			dispatcher.register(
					ClientCommandManager.literal("vm")
							.then(ClientCommandManager.argument("players", StringArgumentType.greedyString())
									.suggests((context, builder) -> suggestPlayerList(context.getSource(), builder.getInput(), builder))
									.executes(context -> executeAttemptedMurderShortcut(
											context.getSource(),
											StringArgumentType.getString(context, "players")
									)))
			);
			dispatcher.register(
					ClientCommandManager.literal("lb")
							.executes(context -> executeMedicWarningShortcut(context.getSource()))
			);
			dispatcher.register(
					ClientCommandManager.literal("raus")
							.executes(context -> executeLeaveAreaShortcut(context.getSource()))
			);
		});
	}

	private static int executeDrugShortcut(FabricClientCommandSource source, String player, String item, int amount) {
		source.getPlayer().networkHandler.sendChatCommand("selldrug " + player + " " + item + " " + CONFIG.gdRarity + " " + amount + " 0");
		return 1;
	}

	private static int executeAttemptedMurderShortcut(FabricClientCommandSource source, String playersText) {
		String[] players = playersText.trim().split("\\s+");
		int queuedCommands = 0;

		for (String player : players) {
			if (!player.isBlank()) {
				PENDING_COMMANDS.add("asu " + player + " Versuchter Mord");
				queuedCommands++;
			}
		}

		if (queuedCommands > 0) {
			source.sendFeedback(Text.literal("Queued " + queuedCommands + " Versuchter Mord command(s)."));
		}

		return queuedCommands;
	}

	private static int executeMedicWarningShortcut(FabricClientCommandSource source) {
		source.getPlayer().networkHandler.sendChatCommand("s JEDER MEDIC DER PROBIERT ZU REVIVEN WIRD STERBEN");
		return 1;
	}

	private static int executeLeaveAreaShortcut(FabricClientCommandSource source) {
		source.getPlayer().networkHandler.sendChatCommand("s ALLE DIE NICHT ZU MEINEN KOLLEGEN UND MIR GEHÖREN VERPISSEN SICH");
		return 1;
	}

	private static void sendPendingCommand(MinecraftClient client) {
		if (client.player == null || client.player.networkHandler == null || PENDING_COMMANDS.isEmpty()) {
			return;
		}

		if (commandSendCooldown > 0) {
			commandSendCooldown--;
			return;
		}

		client.player.networkHandler.sendChatCommand(PENDING_COMMANDS.poll());
		commandSendCooldown = COMMAND_SEND_DELAY_TICKS;
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPlayerList(
			FabricClientCommandSource source,
			String input,
			com.mojang.brigadier.suggestion.SuggestionsBuilder builder
	) {
		int cursor = input.length();
		int lastSpace = input.lastIndexOf(' ', cursor - 1);
		int currentTokenStart = Math.max(builder.getStart(), lastSpace + 1);
		String playerPrefix = input.substring(currentTokenStart);
		com.mojang.brigadier.suggestion.SuggestionsBuilder playerBuilder = builder.createOffset(currentTokenStart);

		String lowerPrefix = playerPrefix.toLowerCase();
		for (String playerName : source.getPlayerNames()) {
			if (CommandSource.shouldSuggest(lowerPrefix, playerName.toLowerCase())) {
				playerBuilder.suggest(playerName);
			}
		}

		return playerBuilder.buildFuture();
	}

	private static void renderPotionHud(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (client.player == null || client.options.hudHidden) {
			return;
		}

		if (CONFIG.effectsEnabled) {
			List<StatusEffectInstance> effects = new ArrayList<>(client.player.getStatusEffects());
			effects.sort(Comparator
					.comparing((StatusEffectInstance effect) -> effect.getEffectType().value().getCategory().ordinal())
					.thenComparing(StatusEffectInstance::getTranslationKey));

			TextRenderer textRenderer = client.textRenderer;
			int x = CONFIG.effectX(context.getScaledWindowWidth());
			int y = CONFIG.effectY(context.getScaledWindowHeight());

			for (StatusEffectInstance effect : effects) {
				renderEffectRow(context, textRenderer, effect, x, y);
				y += ROW_HEIGHT;
			}
		}

		renderMinePaydayHud(context, client.textRenderer);
	}

	private static void renderMinePaydayHud(DrawContext context, TextRenderer textRenderer) {
		if (!CONFIG.mineEnabled || minePaydayMoney <= 0) {
			return;
		}

		String text = "Mine PayDay: " + formatMoney(minePaydayMoney) + "$";
		context.drawText(textRenderer, text, CONFIG.mineX(context.getScaledWindowWidth()), CONFIG.mineY(context.getScaledWindowHeight()), color(CONFIG.mineColorIndex), true);
	}

	private static String formatMoney(long value) {
		return String.format("%,d", value).replace(',', '.');
	}

	private static void renderEffectRow(
			DrawContext context,
			TextRenderer textRenderer,
			StatusEffectInstance effect,
			int x,
			int y
	) {
		context.drawGuiTexture(
				RenderPipelines.GUI_TEXTURED,
				InGameHud.getEffectTexture(effect.getEffectType()),
				x + 3,
				y + 2,
				ICON_SIZE,
				ICON_SIZE
		);

		String name = Text.translatable(effect.getTranslationKey()).getString();
		String amplifier = amplifierText(effect.getAmplifier());
		String title = amplifier.isEmpty() ? name : name + " " + amplifier;
		String time = durationText(effect);

		int textX = x + 25;
		context.drawText(textRenderer, trimToWidth(textRenderer, title, PANEL_WIDTH - 30), textX, y + 3, color(CONFIG.effectColorIndex), true);
		context.drawText(textRenderer, time, textX, y + 13, durationColor(effect), true);
	}

	private static String trimToWidth(TextRenderer textRenderer, String text, int maxWidth) {
		if (textRenderer.getWidth(text) <= maxWidth) {
			return text;
		}

		return textRenderer.trimToWidth(text, maxWidth - textRenderer.getWidth("...")) + "...";
	}

	private static String durationText(StatusEffectInstance effect) {
		if (effect.isInfinite()) {
			return "**:**";
		}

		int totalSeconds = Math.max(0, effect.getDuration() / 20);
		int minutes = totalSeconds / 60;
		int seconds = totalSeconds % 60;

		if (minutes >= 60) {
			int hours = minutes / 60;
			int remainingMinutes = minutes % 60;
			return hours + "h " + remainingMinutes + "m";
		}

		return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
	}

	private static int durationColor(StatusEffectInstance effect) {
		if (!effect.isInfinite() && effect.getDuration() <= 20 * 10) {
			return 0xFFFF6666;
		}

		return color(CONFIG.effectColorIndex);
	}

	private static int color(int index) {
		return COLORS[Math.floorMod(index, COLORS.length)];
	}

	private static String colorName(int index) {
		return COLOR_NAMES[Math.floorMod(index, COLOR_NAMES.length)];
	}

	private static String amplifierText(int amplifier) {
		return switch (amplifier) {
			case 0 -> "";
			case 1 -> "II";
			case 2 -> "III";
			case 3 -> "IV";
			case 4 -> "V";
			default -> String.valueOf(amplifier + 1);
		};
	}

	private static final class PotionHudConfigScreen extends Screen {
		private static final int SAMPLE_HEIGHT = 45;
		private static final int DRAG_EFFECTS = 1;
		private static final int DRAG_MINE = 2;
		private boolean dragging;
		private int draggingTarget;
		private int dragOffsetX;
		private int dragOffsetY;

		private PotionHudConfigScreen() {
			super(Text.literal("easyUC"));
		}

		@Override
		protected void init() {
			int buttonY = this.height - 28;
			addDrawableChild(ButtonWidget.builder(Text.literal("Effects: " + onOff(CONFIG.effectsEnabled)), button -> {
				CONFIG.effectsEnabled = !CONFIG.effectsEnabled;
				CONFIG.save();
				clearAndInit();
			}).dimensions(this.width / 2 - 158, buttonY - 72, 104, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Effect Color: " + colorName(CONFIG.effectColorIndex)), button -> {
				CONFIG.effectColorIndex++;
				CONFIG.save();
				clearAndInit();
			}).dimensions(this.width / 2 - 50, buttonY - 72, 150, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Mine: " + onOff(CONFIG.mineEnabled)), button -> {
				CONFIG.mineEnabled = !CONFIG.mineEnabled;
				CONFIG.save();
				clearAndInit();
			}).dimensions(this.width / 2 - 158, buttonY - 48, 104, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Mine Color: " + colorName(CONFIG.mineColorIndex)), button -> {
				CONFIG.mineColorIndex++;
				CONFIG.save();
				clearAndInit();
			}).dimensions(this.width / 2 - 50, buttonY - 48, 150, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("GD Rarity: " + CONFIG.gdRarity), button -> {
				CONFIG.gdRarity = (CONFIG.gdRarity + 1) % 4;
				CONFIG.save();
				clearAndInit();
			}).dimensions(this.width / 2 - 158, buttonY - 24, 104, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Reset"), button -> {
				CONFIG.reset(this.width, this.height);
				CONFIG.save();
			}).dimensions(this.width / 2 - 50, buttonY - 24, 74, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> this.close())
					.dimensions(this.width / 2 + 30, buttonY - 24, 74, 20)
					.build());
		}

		@Override
		public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
			super.render(context, mouseX, mouseY, deltaTicks);
			context.drawCenteredTextWithShadow(this.textRenderer, "easyUC HUD Positionen", this.width / 2, 12, 0xFFFFFFFF);
			context.drawCenteredTextWithShadow(this.textRenderer, "Drag Effects or Mine. Arrow keys move the last selected HUD.", this.width / 2, 24, 0xFFDDDDDD);
			renderEffectPreview(context);
			renderMinePreview(context);
		}

		@Override
		public boolean mouseClicked(Click click, boolean doubled) {
			if (super.mouseClicked(click, doubled)) {
				return true;
			}

			if (isOverEffectPreview(click.x(), click.y())) {
				dragging = true;
				draggingTarget = DRAG_EFFECTS;
				dragOffsetX = (int) click.x() - CONFIG.effectX(this.width);
				dragOffsetY = (int) click.y() - CONFIG.effectY(this.height);
				return true;
			}

			if (isOverMinePreview(click.x(), click.y())) {
				dragging = true;
				draggingTarget = DRAG_MINE;
				dragOffsetX = (int) click.x() - CONFIG.mineX(this.width);
				dragOffsetY = (int) click.y() - CONFIG.mineY(this.height);
				return true;
			}

			return false;
		}

		@Override
		public boolean mouseDragged(Click click, double offsetX, double offsetY) {
			if (dragging) {
				if (draggingTarget == DRAG_EFFECTS) {
					CONFIG.setEffects((int) click.x() - dragOffsetX, (int) click.y() - dragOffsetY, this.width, this.height);
				} else if (draggingTarget == DRAG_MINE) {
					CONFIG.setMine((int) click.x() - dragOffsetX, (int) click.y() - dragOffsetY, this.width, this.height);
				}
				return true;
			}

			return super.mouseDragged(click, offsetX, offsetY);
		}

		@Override
		public boolean mouseReleased(Click click) {
			if (dragging) {
				dragging = false;
				CONFIG.save();
				return true;
			}

			return super.mouseReleased(click);
		}

		@Override
		public boolean keyPressed(KeyInput keyInput) {
			int step = (keyInput.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? 10 : 1;

			return switch (keyInput.key()) {
				case GLFW.GLFW_KEY_LEFT -> {
					move(-step, 0);
					yield true;
				}
				case GLFW.GLFW_KEY_RIGHT -> {
					move(step, 0);
					yield true;
				}
				case GLFW.GLFW_KEY_UP -> {
					move(0, -step);
					yield true;
				}
				case GLFW.GLFW_KEY_DOWN -> {
					move(0, step);
					yield true;
				}
				default -> super.keyPressed(keyInput);
			};
		}

		@Override
		public void close() {
			CONFIG.save();
			super.close();
		}

		private void move(int x, int y) {
			if (draggingTarget == DRAG_MINE) {
				CONFIG.setMine(CONFIG.mineX(this.width) + x, CONFIG.mineY(this.height) + y, this.width, this.height);
			} else {
				CONFIG.setEffects(CONFIG.effectX(this.width) + x, CONFIG.effectY(this.height) + y, this.width, this.height);
			}
			CONFIG.save();
		}

		private void renderEffectPreview(DrawContext context) {
			int x = CONFIG.effectX(this.width);
			int y = CONFIG.effectY(this.height);
			context.fill(x, y, x + PANEL_WIDTH, y + SAMPLE_HEIGHT, 0x55000000);
			context.fill(x, y, x + PANEL_WIDTH, y + 21, 0xAA111111);
			context.fill(x, y + ROW_HEIGHT, x + PANEL_WIDTH, y + ROW_HEIGHT + 21, 0xAA111111);
			context.drawText(this.textRenderer, "Speed II", x + 25, y + 3, color(CONFIG.effectColorIndex), true);
			context.drawText(this.textRenderer, "1:23", x + 25, y + 13, color(CONFIG.effectColorIndex), true);
			context.drawText(this.textRenderer, "Strength", x + 25, y + ROW_HEIGHT + 3, color(CONFIG.effectColorIndex), true);
			context.drawText(this.textRenderer, "0:42", x + 25, y + ROW_HEIGHT + 13, color(CONFIG.effectColorIndex), true);
			context.fill(x + 3, y + 2, x + 21, y + 20, 0xFF7ED957);
			context.fill(x + 3, y + ROW_HEIGHT + 2, x + 21, y + ROW_HEIGHT + 20, 0xFFFF6666);
		}

		private void renderMinePreview(DrawContext context) {
			int x = CONFIG.mineX(this.width);
			int y = CONFIG.mineY(this.height);
			context.fill(x - 2, y - 2, x + MINE_WIDTH, y + MINE_HEIGHT, 0x55000000);
			context.drawText(this.textRenderer, "Mine PayDay: 1.250$", x, y, color(CONFIG.mineColorIndex), true);
		}

		private boolean isOverEffectPreview(double mouseX, double mouseY) {
			int x = CONFIG.effectX(this.width);
			int y = CONFIG.effectY(this.height);
			return mouseX >= x && mouseX <= x + PANEL_WIDTH && mouseY >= y && mouseY <= y + SAMPLE_HEIGHT;
		}

		private boolean isOverMinePreview(double mouseX, double mouseY) {
			int x = CONFIG.mineX(this.width);
			int y = CONFIG.mineY(this.height);
			return mouseX >= x - 2 && mouseX <= x + MINE_WIDTH && mouseY >= y - 2 && mouseY <= y + MINE_HEIGHT;
		}

		private String onOff(boolean value) {
			return value ? "On" : "Off";
		}
	}

	private static final class HudConfig {
		private boolean effectsEnabled = true;
		private boolean mineEnabled = true;
		private int effectColorIndex = 0;
		private int mineColorIndex = 1;
		private int gdRarity = 0;
		private int effectX = -1;
		private int effectY = TOP_MARGIN;
		private int mineX = 6;
		private int mineY = TOP_MARGIN;

		private void reset(int screenWidth, int screenHeight) {
			setEffects(screenWidth - PANEL_WIDTH - RIGHT_MARGIN, TOP_MARGIN, screenWidth, screenHeight);
			setMine(6, TOP_MARGIN, screenWidth, screenHeight);
		}

		private int effectX(int screenWidth) {
			if (effectX < 0) {
				return screenWidth - PANEL_WIDTH - RIGHT_MARGIN;
			}

			return clamp(effectX, 0, Math.max(0, screenWidth - PANEL_WIDTH));
		}

		private int effectY(int screenHeight) {
			return clamp(effectY, 0, Math.max(0, screenHeight - ROW_HEIGHT));
		}

		private int mineX(int screenWidth) {
			return clamp(mineX, 0, Math.max(0, screenWidth - MINE_WIDTH));
		}

		private int mineY(int screenHeight) {
			return clamp(mineY, 0, Math.max(0, screenHeight - MINE_HEIGHT));
		}

		private void setEffects(int x, int y, int screenWidth, int screenHeight) {
			this.effectX = clamp(x, 0, Math.max(0, screenWidth - PANEL_WIDTH));
			this.effectY = clamp(y, 0, Math.max(0, screenHeight - ROW_HEIGHT));
		}

		private void setMine(int x, int y, int screenWidth, int screenHeight) {
			this.mineX = clamp(x, 0, Math.max(0, screenWidth - MINE_WIDTH));
			this.mineY = clamp(y, 0, Math.max(0, screenHeight - MINE_HEIGHT));
		}

		private void load() {
			Path path = path();

			if (!Files.exists(path)) {
				path = oldPath();
				if (!Files.exists(path)) {
					return;
				}
			}

			try {
				HudConfig loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), HudConfig.class);
				if (loaded != null) {
					effectsEnabled = loaded.effectsEnabled;
					mineEnabled = loaded.mineEnabled;
					effectColorIndex = loaded.effectColorIndex;
					mineColorIndex = loaded.mineColorIndex;
					gdRarity = clamp(loaded.gdRarity, 0, 3);
					effectX = loaded.effectX;
					effectY = loaded.effectY;
					mineX = loaded.mineX;
					mineY = loaded.mineY;
				}
			} catch (IOException | JsonSyntaxException ignored) {
			}
		}

		private void save() {
			Path path = path();

			try {
				Files.createDirectories(path.getParent());
				Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
			} catch (IOException ignored) {
			}
		}

		private Path path() {
			return MinecraftClient.getInstance().runDirectory.toPath().resolve("config").resolve(MOD_ID + ".json");
		}

		private Path oldPath() {
			return MinecraftClient.getInstance().runDirectory.toPath().resolve("config").resolve(OLD_MOD_ID + ".json");
		}

		private static int clamp(int value, int min, int max) {
			return Math.max(min, Math.min(max, value));
		}

	}
}
