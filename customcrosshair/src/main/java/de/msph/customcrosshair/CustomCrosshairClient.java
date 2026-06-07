package de.msph.customcrosshair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class CustomCrosshairClient implements ClientModInitializer {
	private static final String MOD_ID = "customcrosshair";
	private static final String SHARE_PREFIX = "customcrosshair:";
	private static final int GRID_SIZE = 15;
	private static final int EDITOR_CELL_SIZE = 12;
	private static final int EDITOR_SIZE = GRID_SIZE * EDITOR_CELL_SIZE;
	private static final int[] COLORS = {
			0xFFFFFFFF,
			0xFFD9D9D9,
			0xFFAAAAAA,
			0xFF666666,
			0xFFFFD966,
			0xFFFFAA00,
			0xFF7ED957,
			0xFF00FF66,
			0xFF66CCFF,
			0xFF00FFFF,
			0xFF3366FF,
			0xFFFF66CC,
			0xFFFF6666,
			0xFFFF0000,
			0xFF8A2BE2,
			0xFF000000
	};
	private static final String[] COLOR_NAMES = {
			"White",
			"Light Gray",
			"Gray",
			"Dark Gray",
			"Gold",
			"Orange",
			"Green",
			"Lime",
			"Blue",
			"Cyan",
			"Royal Blue",
			"Pink",
			"Red",
			"Bright Red",
			"Purple",
			"Black"
	};
	private static final double MIN_SCALE = 0.35;
	private static final double MAX_SCALE = 4.0;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final CrosshairConfig CONFIG = new CrosshairConfig();
	private static final DateTimeFormatter DEBUG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static KeyBinding openEditorKey;
	private static boolean renderedOnce;
	private static int openEditorTicks;
	private static String openEditorReason = "unknown";

	@Override
	public void onInitializeClient() {
		debug("onInitializeClient start");
		CONFIG.load();
		debug("config loaded | enabled=" + CONFIG.enabled + " | colorIndex=" + CONFIG.colorIndex + " | scale=" + CONFIG.scale);
		openEditorKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.customcrosshair.open_editor",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				KeyBinding.Category.MISC
		));
		debug("keybinding registered");

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openEditorKey.wasPressed()) {
				requestOpenEditor("keybinding");
			}

			if (openEditorTicks > 0) {
				openEditorTicks--;
				if (openEditorTicks == 0) {
					debug("opening editor on client tick | reason=" + openEditorReason + " | currentScreen="
							+ (client.currentScreen == null ? "null" : client.currentScreen.getClass().getName()));
					client.setScreen(new CrosshairEditorScreen());
				}
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("customcrosshair")
						.executes(context -> {
							debug("command /customcrosshair executed");
							requestOpenEditor("command");
							return 1;
						})
		));
		debug("client command callback registered");
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanillaCrosshair -> (context, tickCounter) -> {
			if (!CONFIG.enabled) {
				vanillaCrosshair.render(context, tickCounter);
			}
		});
		HudRenderCallback.EVENT.register(CustomCrosshairClient::renderCrosshair);
		debug("hud callbacks registered");
	}

	private static void renderCrosshair(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!CONFIG.enabled || client.options.hudHidden) {
			return;
		}

		if (!renderedOnce) {
			renderedOnce = true;
			debug("first custom crosshair render | screen=" + (client.currentScreen == null ? "null" : client.currentScreen.getClass().getName()));
		}
		renderPattern(
				context,
				context.getScaledWindowWidth() / 2,
				context.getScaledWindowHeight() / 2,
				color(CONFIG.colorIndex),
				CONFIG.scale,
				CONFIG.rotation
		);
	}

	private static void renderPattern(DrawContext context, int centerX, int centerY, int color, double scale, int rotation) {
		CONFIG.ensurePixels();
		context.getMatrices().pushMatrix();
		context.getMatrices().scaleAround((float) scale, centerX, centerY);
		if (rotation != 0) {
			context.getMatrices().rotateAbout((float) Math.toRadians(rotation), centerX, centerY);
		}
		renderPatternUnscaled(context, centerX, centerY, color);
		context.getMatrices().popMatrix();
	}

	private static void renderPatternUnscaled(DrawContext context, int centerX, int centerY, int color) {
		int half = GRID_SIZE / 2;

		for (int row = 0; row < GRID_SIZE; row++) {
			for (int column = 0; column < GRID_SIZE; column++) {
				if (CONFIG.pixels[row * GRID_SIZE + column]) {
					int x = centerX + column - half;
					int y = centerY + row - half;
					context.fill(x, y, x + 1, y + 1, color);
				}
			}
		}
	}

	private static void requestOpenEditor(String reason) {
		openEditorReason = reason;
		openEditorTicks = 2;
		debug("editor open requested | reason=" + reason);
	}

	private static int color(int index) {
		return COLORS[Math.floorMod(index, COLORS.length)];
	}

	private static String colorName(int index) {
		return COLOR_NAMES[Math.floorMod(index, COLOR_NAMES.length)];
	}

	private static double clampScale(double scale) {
		return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
	}

	private static double sliderValueToScale(double value) {
		return clampScale(MIN_SCALE + (MAX_SCALE - MIN_SCALE) * value);
	}

	private static double scaleToSliderValue(double scale) {
		return (clampScale(scale) - MIN_SCALE) / (MAX_SCALE - MIN_SCALE);
	}

	private static String scaleText(double scale) {
		return String.format(java.util.Locale.ROOT, "%.2fx", clampScale(scale));
	}

	private static final class CrosshairEditorScreen extends Screen {
		private boolean drawing;
		private boolean drawValue;

		private CrosshairEditorScreen() {
			super(Text.literal("CustomCrosshair"));
			debug("CrosshairEditorScreen created");
		}

		@Override
		protected void init() {
			int y = this.height - 74;
			addDrawableChild(ButtonWidget.builder(Text.literal("Enabled: " + onOff(CONFIG.enabled)), button -> {
				CONFIG.enabled = !CONFIG.enabled;
				CONFIG.save();
				clearAndInit();
			}).dimensions(this.width / 2 - 158, y, 104, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Color: " + colorName(CONFIG.colorIndex)), button -> {
				CONFIG.colorIndex++;
				CONFIG.save();
				clearAndInit();
			}).dimensions(this.width / 2 - 50, y, 150, 20).build());
			addDrawableChild(new ScaleSliderWidget(this.width / 2 - 158, y + 24, 180, 20));
			addDrawableChild(ButtonWidget.builder(Text.literal("Rot: " + CONFIG.rotation), button -> {
				CONFIG.rotation = (CONFIG.rotation + 90) % 360;
				CONFIG.save();
				clearAndInit();
			}).dimensions(this.width / 2 + 26, y, 70, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Mirror"), button -> {
				mirrorHorizontal();
				CONFIG.save();
			}).dimensions(this.width / 2 + 26, y + 24, 70, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Clear"), button -> {
				CONFIG.pixels = new boolean[GRID_SIZE * GRID_SIZE];
				CONFIG.save();
			}).dimensions(this.width / 2 + 100, y + 24, 58, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Default"), button -> {
				CONFIG.pixels = presetDefault();
				CONFIG.save();
			}).dimensions(this.width / 2 - 158, y + 48, 62, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Circle"), button -> {
				CONFIG.pixels = presetCircle();
				CONFIG.colorIndex = 0;
				CONFIG.save();
			}).dimensions(this.width / 2 - 92, y + 48, 58, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Tex"), button -> {
				CONFIG.pixels = textureTwoCrosshairPixels();
				CONFIG.colorIndex = 0;
				CONFIG.scale = 1.0;
				CONFIG.save();
			}).dimensions(this.width / 2 - 30, y + 48, 48, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Export"), button -> exportCrosshair())
					.dimensions(this.width / 2 + 22, y + 48, 62, 20)
					.build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Import"), button -> importCrosshair())
					.dimensions(this.width / 2 + 88, y + 48, 62, 20)
					.build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> this.close())
					.dimensions(this.width / 2 + 100, y, 58, 20)
					.build());
		}

		@Override
		public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
			super.render(context, mouseX, mouseY, deltaTicks);
			context.drawCenteredTextWithShadow(this.textRenderer, "CustomCrosshair", this.width / 2, 12, 0xFFFFFFFF);
			context.drawCenteredTextWithShadow(this.textRenderer, "Click or drag pixels. Use /customcrosshair to reopen.", this.width / 2, 24, 0xFFDDDDDD);
			renderEditor(context);
			renderPreview(context);
		}

		@Override
		public boolean mouseClicked(Click click, boolean doubled) {
			if (super.mouseClicked(click, doubled)) {
				return true;
			}

			int index = pixelIndex(click.x(), click.y());
			if (index >= 0) {
				CONFIG.ensurePixels();
				drawing = true;
				drawValue = !CONFIG.pixels[index];
				CONFIG.pixels[index] = drawValue;
				return true;
			}

			return false;
		}

		@Override
		public boolean mouseDragged(Click click, double offsetX, double offsetY) {
			if (drawing) {
				int index = pixelIndex(click.x(), click.y());
				if (index >= 0) {
					CONFIG.pixels[index] = drawValue;
				}
				return true;
			}

			return super.mouseDragged(click, offsetX, offsetY);
		}

		@Override
		public boolean mouseReleased(Click click) {
			if (drawing) {
				drawing = false;
				CONFIG.save();
				return true;
			}

			return super.mouseReleased(click);
		}

		@Override
		public void close() {
			CONFIG.save();
			super.close();
		}

		private void renderEditor(DrawContext context) {
			CONFIG.ensurePixels();
			int startX = editorX();
			int startY = editorY();
			context.fill(startX - 1, startY - 1, startX + EDITOR_SIZE + 1, startY + EDITOR_SIZE + 1, 0xFF333333);
			context.fill(startX, startY, startX + EDITOR_SIZE, startY + EDITOR_SIZE, 0xFF060606);

			for (int row = 0; row < GRID_SIZE; row++) {
				for (int column = 0; column < GRID_SIZE; column++) {
					int x = startX + column * EDITOR_CELL_SIZE;
					int y = startY + row * EDITOR_CELL_SIZE;
					if (CONFIG.pixels[row * GRID_SIZE + column]) {
						context.fill(x, y, x + EDITOR_CELL_SIZE, y + EDITOR_CELL_SIZE, color(CONFIG.colorIndex));
					} else if (row == GRID_SIZE / 2 || column == GRID_SIZE / 2) {
						context.fill(x, y, x + EDITOR_CELL_SIZE, y + EDITOR_CELL_SIZE, 0xFF151515);
					}
				}
			}
		}

		private void renderPreview(DrawContext context) {
			int centerX = this.width / 2 + EDITOR_SIZE / 2 + 54;
			int centerY = editorY() + EDITOR_SIZE / 2;
			context.fill(centerX - 28, centerY - 28, centerX + 29, centerY + 29, 0x55000000);
			renderPattern(context, centerX, centerY, color(CONFIG.colorIndex), CONFIG.scale, CONFIG.rotation);
		}

		private int pixelIndex(double mouseX, double mouseY) {
			int startX = editorX();
			int startY = editorY();
			if (mouseX < startX || mouseY < startY || mouseX >= startX + EDITOR_SIZE || mouseY >= startY + EDITOR_SIZE) {
				return -1;
			}

			int column = ((int) mouseX - startX) / EDITOR_CELL_SIZE;
			int row = ((int) mouseY - startY) / EDITOR_CELL_SIZE;
			return row * GRID_SIZE + column;
		}

		private int editorX() {
			return this.width / 2 - EDITOR_SIZE / 2;
		}

		private int editorY() {
			return 42;
		}

		private void mirrorHorizontal() {
			CONFIG.ensurePixels();
			for (int row = 0; row < GRID_SIZE; row++) {
				for (int column = 0; column < GRID_SIZE / 2; column++) {
					boolean value = CONFIG.pixels[row * GRID_SIZE + column];
					CONFIG.pixels[row * GRID_SIZE + (GRID_SIZE - 1 - column)] = value;
				}
			}
		}

		private String onOff(boolean value) {
			return value ? "On" : "Off";
		}

		private void exportCrosshair() {
			MinecraftClient.getInstance().keyboard.setClipboard(encodePixels(CONFIG.pixels));
			debug("crosshair exported to clipboard");
		}

		private void importCrosshair() {
			boolean[] imported = decodePixels(MinecraftClient.getInstance().keyboard.getClipboard());
			if (imported != null) {
				CONFIG.pixels = imported;
				CONFIG.save();
				debug("crosshair imported from clipboard");
			} else {
				debug("crosshair import failed: invalid clipboard");
			}
		}
	}

	private static final class ScaleSliderWidget extends SliderWidget {
		private ScaleSliderWidget(int x, int y, int width, int height) {
			super(x, y, width, height, Text.empty(), scaleToSliderValue(CONFIG.scale));
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Text.literal("Scale: " + scaleText(CONFIG.scale)));
		}

		@Override
		protected void applyValue() {
			CONFIG.scale = sliderValueToScale(this.value);
			CONFIG.save();
			updateMessage();
		}
	}

	private static final class CrosshairConfig {
		private boolean enabled = true;
		private int colorIndex = 0;
		private double scale = 1.0;
		private int rotation = 0;
		@SuppressWarnings("unused")
		private int scaleIndex = 0;
		@SuppressWarnings("unused")
		private int pixelScale = 1;
		private boolean[] pixels = presetDefault();

		private void load() {
			Path path = path();
			if (!Files.exists(path)) {
				debug("config not found, using defaults | path=" + path);
				return;
			}

			try {
				CrosshairConfig loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), CrosshairConfig.class);
				if (loaded != null) {
					enabled = loaded.enabled;
					colorIndex = loaded.colorIndex;
					scale = clampScale(loaded.scale);
					if (loaded.scale <= 0.0) {
						scale = switch (loaded.scaleIndex) {
							case 1 -> 1.5;
							case 2 -> 2.0;
							case 3 -> 3.0;
							case 4 -> 4.0;
							default -> loaded.pixelScale > 0 ? loaded.pixelScale : 1.0;
						};
					}
					scale = clampScale(scale);
					rotation = Math.floorMod(loaded.rotation, 360);
					pixels = loaded.pixels;
					ensurePixels();
				}
			} catch (IOException | JsonSyntaxException ignored) {
				debug("config load failed | " + ignored.getClass().getSimpleName() + ": " + ignored.getMessage());
			}
		}

		private void save() {
			Path path = path();
			try {
				Files.createDirectories(path.getParent());
				Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
			} catch (IOException ignored) {
				debug("config save failed | " + ignored.getClass().getSimpleName() + ": " + ignored.getMessage());
			}
		}

		private Path path() {
			return MinecraftClient.getInstance().runDirectory.toPath().resolve("config").resolve(MOD_ID + ".json");
		}

		private void ensurePixels() {
			if (pixels == null || pixels.length != GRID_SIZE * GRID_SIZE) {
				pixels = presetDefault();
			}
		}
	}

	private static void debug(String message) {
		try {
			Path path = MinecraftClient.getInstance().runDirectory.toPath()
					.resolve("config")
					.resolve(MOD_ID)
					.resolve("_debug.txt");
			Files.createDirectories(path.getParent());
			String line = DEBUG_TIME.format(LocalDateTime.now()) + " | " + message + System.lineSeparator();
			Files.writeString(path, line, StandardCharsets.UTF_8,
					Files.exists(path) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
		} catch (IOException ignored) {
		}
	}

	private static boolean[] presetDefault() {
		boolean[] pixels = new boolean[GRID_SIZE * GRID_SIZE];
		int center = GRID_SIZE / 2;

		for (int offset = -5; offset <= 5; offset++) {
			if (Math.abs(offset) > 1) {
				pixels[center * GRID_SIZE + center + offset] = true;
				pixels[(center + offset) * GRID_SIZE + center] = true;
			}
		}

		return pixels;
	}

	private static boolean[] presetCircle() {
		return pixelsFromCenter(
				0, -4,
				-1, -4,
				1, -4,
				-3, -3,
				3, -3,
				-4, -1,
				-4, 0,
				-4, 1,
				4, -1,
				4, 0,
				4, 1,
				-3, 3,
				3, 3,
				-1, 4,
				0, 4,
				1, 4
		);
	}

	private static boolean[] textureTwoCrosshairPixels() {
		return centeredPixelsFromPattern(
				"....######",
				"....######",
				"........##",
				"........##",
				"##..##..##",
				"##..##..##",
				"##........",
				"##........",
				"######....",
				"######...."
		);
	}

	private static boolean[] pixelsFromCenter(int... xyPairs) {
		boolean[] pixels = new boolean[GRID_SIZE * GRID_SIZE];
		int center = GRID_SIZE / 2;
		for (int i = 0; i + 1 < xyPairs.length; i += 2) {
			int x = center + xyPairs[i];
			int y = center + xyPairs[i + 1];
			if (x >= 0 && y >= 0 && x < GRID_SIZE && y < GRID_SIZE) {
				pixels[y * GRID_SIZE + x] = true;
			}
		}

		return pixels;
	}

	private static boolean[] pixelsFromPattern(String... rows) {
		boolean[] pixels = new boolean[GRID_SIZE * GRID_SIZE];
		for (int row = 0; row < Math.min(GRID_SIZE, rows.length); row++) {
			String line = rows[row];
			for (int column = 0; column < Math.min(GRID_SIZE, line.length()); column++) {
				pixels[row * GRID_SIZE + column] = line.charAt(column) == '#';
			}
		}

		return pixels;
	}

	private static boolean[] centeredPixelsFromPattern(String... rows) {
		boolean[] pixels = new boolean[GRID_SIZE * GRID_SIZE];
		int rowOffset = Math.max(0, (GRID_SIZE - rows.length) / 2);
		int maxWidth = 0;
		for (String row : rows) {
			maxWidth = Math.max(maxWidth, row.length());
		}

		int columnOffset = Math.max(0, (GRID_SIZE - maxWidth) / 2);
		for (int row = 0; row < Math.min(GRID_SIZE - rowOffset, rows.length); row++) {
			String line = rows[row];
			for (int column = 0; column < Math.min(GRID_SIZE - columnOffset, line.length()); column++) {
				pixels[(row + rowOffset) * GRID_SIZE + column + columnOffset] = line.charAt(column) == '#';
			}
		}

		return pixels;
	}

	private static String encodePixels(boolean[] pixels) {
		byte[] bytes = new byte[(GRID_SIZE * GRID_SIZE + 7) / 8];
		for (int i = 0; i < GRID_SIZE * GRID_SIZE && i < pixels.length; i++) {
			if (pixels[i]) {
				bytes[i / 8] |= (byte) (1 << (i % 8));
			}
		}

		return SHARE_PREFIX + Base64.getEncoder().encodeToString(bytes);
	}

	private static boolean[] decodePixels(String input) {
		if (input == null || !input.startsWith(SHARE_PREFIX)) {
			return null;
		}

		try {
			byte[] bytes = Base64.getDecoder().decode(input.substring(SHARE_PREFIX.length()));
			boolean[] pixels = new boolean[GRID_SIZE * GRID_SIZE];
			for (int i = 0; i < pixels.length; i++) {
				pixels[i] = i / 8 < bytes.length && (bytes[i / 8] & (1 << (i % 8))) != 0;
			}

			return pixels;
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}
}
