package de.msph.aktienobserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

public class AktienObserverClient implements ClientModInitializer {
	private static final int PHASE_TIMEOUT_TICKS = 20 * 12;
	private static final Pattern COURSE_PATTERN = Pattern.compile("Kurs:\\s*(.+)", Pattern.CASE_INSENSITIVE);
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final String OUTPUT_FOLDER = "aktienobserver";

	private static Phase phase = Phase.IDLE;
	private static int phaseTicks;
	private static int stockCount;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(AktienObserverClient::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("observa")
						.executes(context -> triggerObservation(context.getSource()))
		));
	}

	private static int triggerObservation(FabricClientCommandSource source) {
		if (phase != Phase.IDLE) {
			source.sendFeedback(Text.literal("AktienObserver läuft bereits."));
			return 0;
		}

		MinecraftClient client = source.getClient();
		if (client.player == null || client.world == null) {
			source.sendError(Text.literal("AktienObserver kann gerade nicht starten."));
			return 0;
		}

		ensureOutputDirectory(client);
		writeDebug(client, "Start via /observa");
		client.player.networkHandler.sendChatCommand("mobile");
		phase = Phase.WAIT_MOBILE;
		phaseTicks = 0;
		source.sendFeedback(Text.literal("AktienObserver startet."));
		return 1;
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.interactionManager == null) {
			resetToIdle();
			return;
		}

		if (phase == Phase.IDLE) {
			return;
		}

		phaseTicks++;
		if (phaseTicks > PHASE_TIMEOUT_TICKS) {
			writeDebug(client, "Timeout in phase " + phase);
			closeScreen(client);
			resetToIdle();
			return;
		}

		switch (phase) {
			case WAIT_MOBILE -> clickMenuItem(client, new String[] { "apps", "app" }, Phase.WAIT_APPS);
			case WAIT_APPS -> clickMenuItem(client, new String[] { "aktien", "aktie", "stock" }, Phase.WAIT_STOCKS);
			case WAIT_STOCKS -> scanStocks(client);
			default -> {
			}
		}
	}

	private static void clickMenuItem(MinecraftClient client, String[] needles, Phase nextPhase) {
		if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
			return;
		}

		ScreenHandler handler = screen.getScreenHandler();
		dumpCurrentMenu(client, handler, "Looking for " + String.join("/", needles), false);

		for (Slot slot : handler.slots) {
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) {
				continue;
			}

			if (stackMatchesAny(client, stack, needles)) {
				writeDebug(client, "Click " + String.join("/", needles) + " via item " + stack.getName().getString());
				client.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, client.player);
				phase = nextPhase;
				phaseTicks = 0;
				return;
			}
		}
	}

	private static boolean stackMatchesAny(MinecraftClient client, ItemStack stack, String[] needles) {
		for (String needle : needles) {
			if (stackMatches(client, stack, needle.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}

		return false;
	}

	private static void dumpCurrentMenu(MinecraftClient client, ScreenHandler handler, String reason, boolean force) {
		if (!force && phaseTicks != 1 && phaseTicks != 20) {
			return;
		}

		writeDebug(client, reason + " | slots=" + handler.slots.size());
		for (Slot slot : handler.slots) {
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) {
				continue;
			}

			writeDebug(client, "slot " + slot.id + " item=" + stack.getName().getString());
			for (Text line : tooltip(client, stack)) {
				writeDebug(client, "  tooltip=" + line.getString());
			}
		}
	}

	private static boolean stackMatches(MinecraftClient client, ItemStack stack, String lowerNeedle) {
		if (stack.getName().getString().toLowerCase(Locale.ROOT).contains(lowerNeedle)) {
			return true;
		}

		for (Text line : tooltip(client, stack)) {
			if (line.getString().toLowerCase(Locale.ROOT).contains(lowerNeedle)) {
				return true;
			}
		}

		return false;
	}

	private static void scanStocks(MinecraftClient client) {
		if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
			return;
		}

		if (phaseTicks < 10) {
			return;
		}

		ScreenHandler handler = screen.getScreenHandler();
		dumpCurrentMenu(client, handler, "Scanning stocks", true);
		stockCount = 0;

		for (Slot slot : handler.slots) {
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) {
				continue;
			}

			StockCourse course = readStockCourse(client, stack);
			if (course != null) {
				saveCourse(client, course);
				stockCount++;
			}
		}

		closeScreen(client);
		writeDebug(client, "Scan complete. Found " + stockCount + " stock course item(s).");
		resetToIdle();
	}

	private static StockCourse readStockCourse(MinecraftClient client, ItemStack stack) {
		String stockName = cleanFileName(stack.getName().getString());

		for (Text line : tooltip(client, stack)) {
			Matcher matcher = COURSE_PATTERN.matcher(line.getString());
			if (matcher.find()) {
				return new StockCourse(stockName, matcher.group(1).trim());
			}
		}

		return null;
	}

	private static List<Text> tooltip(MinecraftClient client, ItemStack stack) {
		return stack.getTooltip(Item.TooltipContext.create(client.world), client.player, TooltipType.BASIC);
	}

	private static void saveCourse(MinecraftClient client, StockCourse course) {
		Path directory = outputDirectory(client);
		Path file = directory.resolve(course.fileName() + ".txt");
		String line = TIME_FORMAT.format(LocalDateTime.now()) + " | Kurs: " + course.value() + System.lineSeparator();

		try {
			Files.createDirectories(directory);
			Files.writeString(file, line, StandardCharsets.UTF_8,
					Files.exists(file)
							? java.nio.file.StandardOpenOption.APPEND
							: java.nio.file.StandardOpenOption.CREATE);
		} catch (IOException ignored) {
		}
	}

	private static void ensureOutputDirectory(MinecraftClient client) {
		try {
			Files.createDirectories(outputDirectory(client));
		} catch (IOException ignored) {
		}
	}

	private static void writeDebug(MinecraftClient client, String message) {
		Path file = outputDirectory(client).resolve("_debug.txt");
		String line = TIME_FORMAT.format(LocalDateTime.now()) + " | " + message + System.lineSeparator();

		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, line, StandardCharsets.UTF_8,
					Files.exists(file)
							? java.nio.file.StandardOpenOption.APPEND
							: java.nio.file.StandardOpenOption.CREATE);
		} catch (IOException ignored) {
		}
	}

	private static Path outputDirectory(MinecraftClient client) {
		return client.runDirectory.toPath().resolve("config").resolve(OUTPUT_FOLDER);
	}

	private static String cleanFileName(String name) {
		String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "").trim();
		cleaned = cleaned.replaceAll("\\s+", "_");
		return cleaned.isBlank() ? "Unbekannte_Aktie" : cleaned;
	}

	private static void closeScreen(MinecraftClient client) {
		if (client.player != null) {
			client.player.closeHandledScreen();
		}
	}

	private static void resetToIdle() {
		phase = Phase.IDLE;
		phaseTicks = 0;
	}

	private enum Phase {
		IDLE,
		WAIT_MOBILE,
		WAIT_APPS,
		WAIT_STOCKS
	}

	private record StockCourse(String fileName, String value) {
	}
}
