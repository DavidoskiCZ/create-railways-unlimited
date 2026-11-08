package com.davzelenec.railwaysunlimited.client;

import java.util.List;

import com.davzelenec.railwaysunlimited.snap.PlayerSettings;
import com.davzelenec.railwaysunlimited.snap.SnapSettings;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.neoforged.fml.ModList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Track settings for the current save, opened with the key while wearing goggles and holding track.
 * Sections: the basic placement settings, and a debug section. Number fields behave like NeoForge's config screens:
 * prefilled with the current value, red while the input is not a valid value, the allowed range in the tooltip.
 */
public class SnapScreen extends Screen {
	private static final int BUTTON_WIDTH = 50;
	private static final int GAP = 6;
	private static final int ROW_WIDTH = 4 * BUTTON_WIDTH + 3 * GAP;
	private static final int LENGTH_BOX_WIDTH = 60;

	private int top;
	private int left;
	private EditBox lengthBox;
	/** Rows added above the debug section (the Steam 'n' Rails gauge toggle). */
	private int debugShift;

	public SnapScreen() {
		super(Component.translatable("railways_unlimited.screen.title"));
	}

	@Override
	protected void init() {
		boolean steamNRails = ModList.get().isLoaded("railways");
		int extra = steamNRails ? 24 : 0;
		debugShift = extra;
		top = height / 2 - 104 - extra / 2;
		left = width / 2 - ROW_WIDTH / 2;
		PlayerSettings settings = SnapSettings.getClientSettings();

		// ---- basic: angle step
		List<Integer> steps = SnapSettings.STEPS;
		int x = left;
		for (int step : steps) {
			Component label = Component.literal(step == settings.snapStep() ? "[" + step + "°]" : step + "°");
			Button button = Button.builder(label, b -> {
					ClientSnap.chooseStep(step);
					rebuildWidgets();
				})
				.bounds(x, top + 46, BUTTON_WIDTH, 20)
				.tooltip(Tooltip.create(Component.translatable("railways_unlimited.screen.step_tooltip", step)))
				.build();
			button.active = step != settings.snapStep();
			addRenderableWidget(button);
			x += BUTTON_WIDTH + GAP;
		}

		// ---- basic: maximum segment length
		lengthBox = new EditBox(font, left + ROW_WIDTH - LENGTH_BOX_WIDTH - 54, top + 76, LENGTH_BOX_WIDTH, 20,
			Component.translatable("railways_unlimited.screen.max_length"));
		lengthBox.setMaxLength(4);
		lengthBox.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
		lengthBox.setValue(String.valueOf(settings.maxLength()));
		lengthBox.setTooltip(Tooltip.create(Component.translatable("railways_unlimited.screen.max_length_tooltip")
			.append("\n\n")
			.append(Component.translatable("neoforge.configuration.uitext.rangetooltip",
					PlayerSettings.MIN_MAX_LENGTH + " ~ " + PlayerSettings.MAX_MAX_LENGTH)
				.withStyle(ChatFormatting.GRAY))));
		lengthBox.setResponder(this::onLengthTyped);
		addRenderableWidget(lengthBox);

		// ---- basic: preview by track type (only Steam 'n' Rails adds other gauges)
		if (steamNRails)
			addRenderableWidget(CycleButton.onOffBuilder(settings.gaugePreview())
				.withTooltip(value -> Tooltip.create(
					Component.translatable("railways_unlimited.screen.gauge_preview_tooltip")))
				.create(left, top + 102, ROW_WIDTH, 20, Component.translatable("railways_unlimited.screen.gauge_preview"),
					(button, value) -> ClientSnap.setGaugePreview(value)));

		// ---- debug
		addRenderableWidget(CycleButton.onOffBuilder(settings.showJoints())
			.withTooltip(value -> Tooltip.create(Component.translatable("railways_unlimited.screen.show_joints_tooltip")))
			.create(left, top + 128 + extra, ROW_WIDTH, 20, Component.translatable("railways_unlimited.screen.show_joints"),
				(button, value) -> ClientSnap.setShowJoints(value)));
		addRenderableWidget(CycleButton.onOffBuilder(settings.clearGhosts())
			.withTooltip(value -> Tooltip.create(Component.translatable("railways_unlimited.screen.clear_ghosts_tooltip")))
			.create(left, top + 152 + extra, ROW_WIDTH, 20, Component.translatable("railways_unlimited.screen.clear_ghosts"),
				(button, value) -> ClientSnap.setClearGhosts(value)));

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
			.bounds(width / 2 - 50, top + 186 + extra, 100, 20)
			.build());
	}

	/** Applied as soon as the input is a valid length; otherwise shown in red, like NeoForge's config screens. */
	private void onLengthTyped(String text) {
		try {
			int value = Integer.parseInt(text);
			if (value >= PlayerSettings.MIN_MAX_LENGTH && value <= PlayerSettings.MAX_MAX_LENGTH) {
				lengthBox.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
				if (value != SnapSettings.getClientSettings().maxLength())
					ClientSnap.setMaxLength(value);
				return;
			}
		} catch (NumberFormatException ignored) {
			// empty while typing
		}
		lengthBox.setTextColor(0xFFFF0000);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		PlayerSettings settings = SnapSettings.getClientSettings();
		graphics.drawCenteredString(font, title, width / 2, top, 0xFFFFFF);

		section(graphics, "railways_unlimited.screen.section.basic", top + 18);
		graphics.drawString(font, Component.translatable("railways_unlimited.screen.current", settings.snapStep()),
			left, top + 34, 0xAAAAAA);
		graphics.drawString(font, Component.translatable("railways_unlimited.screen.max_length"), left, top + 82,
			0xFFFFFF);
		graphics.drawString(font, Component.translatable("railways_unlimited.screen.max_length_current",
			settings.maxLength()), lengthBox.getX() + LENGTH_BOX_WIDTH + 6, top + 82, 0xAAAAAA);

		section(graphics, "railways_unlimited.screen.section.debug", top + 112 + debugShift);
	}

	private void section(GuiGraphics graphics, String key, int y) {
		graphics.drawString(font, Component.translatable(key).withStyle(ChatFormatting.UNDERLINE), left, y,
			0xFFFFFF);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
