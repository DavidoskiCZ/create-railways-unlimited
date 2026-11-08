package com.davzelenec.railwaysunlimited.snap;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

/**
 * One player's settings in one save.
 *
 * @param snapStep   angle step straight track snaps to, in degrees
 * @param maxLength  how far (in blocks) one track placement may reach before it is "too far"
 * @param showJoints debug view: highlight the anchors (joints) of angled track around the player
 * @param clearGhosts debug: keep removing track graph nodes without track ("ghosts") around the player
 * @param gaugePreview draw the placement preview with the held track's gauge (Steam 'n' Rails wide/narrow gauge,
 *                     monorail) instead of always standard gauge
 */
public record PlayerSettings(int snapStep, int maxLength, boolean showJoints, boolean clearGhosts,
							 boolean gaugePreview) {
	/** Create's own default for {@code maxTrackPlacementLength}. */
	public static final int DEFAULT_MAX_LENGTH = 32;
	public static final int MIN_MAX_LENGTH = 32;
	/** Longer pieces are not needed and render poorly. */
	public static final int MAX_MAX_LENGTH = 512;

	public static final PlayerSettings DEFAULT =
		new PlayerSettings(SnapSettings.DEFAULT_STEP, DEFAULT_MAX_LENGTH, false, false, true);

	public static final StreamCodec<ByteBuf, PlayerSettings> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, PlayerSettings::snapStep,
		ByteBufCodecs.VAR_INT, PlayerSettings::maxLength,
		ByteBufCodecs.BOOL, PlayerSettings::showJoints,
		ByteBufCodecs.BOOL, PlayerSettings::clearGhosts,
		ByteBufCodecs.BOOL, PlayerSettings::gaugePreview,
		PlayerSettings::new);

	public static int clampMaxLength(int length) {
		return Mth.clamp(length, MIN_MAX_LENGTH, MAX_MAX_LENGTH);
	}

	public PlayerSettings sanitized() {
		return new PlayerSettings(SnapSettings.sanitize(snapStep), clampMaxLength(maxLength), showJoints, clearGhosts,
			gaugePreview);
	}

	public PlayerSettings withSnapStep(int step) {
		return new PlayerSettings(SnapSettings.sanitize(step), maxLength, showJoints, clearGhosts, gaugePreview);
	}

	public PlayerSettings withMaxLength(int length) {
		return new PlayerSettings(snapStep, clampMaxLength(length), showJoints, clearGhosts, gaugePreview);
	}

	public PlayerSettings withShowJoints(boolean show) {
		return new PlayerSettings(snapStep, maxLength, show, clearGhosts, gaugePreview);
	}

	public PlayerSettings withClearGhosts(boolean clear) {
		return new PlayerSettings(snapStep, maxLength, showJoints, clear, gaugePreview);
	}

	public PlayerSettings withGaugePreview(boolean show) {
		return new PlayerSettings(snapStep, maxLength, showJoints, clearGhosts, show);
	}

	public CompoundTag write() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("SnapStep", snapStep);
		tag.putInt("MaxLength", maxLength);
		tag.putBoolean("ShowJoints", showJoints);
		tag.putBoolean("ClearGhosts", clearGhosts);
		tag.putBoolean("GaugePreview", gaugePreview);
		return tag;
	}

	public static PlayerSettings read(CompoundTag tag) {
		int step = tag.contains("SnapStep") ? tag.getInt("SnapStep") : SnapSettings.DEFAULT_STEP;
		int length = tag.contains("MaxLength") ? tag.getInt("MaxLength") : DEFAULT_MAX_LENGTH;
		boolean gaugePreview = !tag.contains("GaugePreview") || tag.getBoolean("GaugePreview");
		return new PlayerSettings(step, length, tag.getBoolean("ShowJoints"), tag.getBoolean("ClearGhosts"),
			gaugePreview).sanitized();
	}
}
