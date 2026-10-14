package com.vyrriox.justreplay.replay;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomReportDetailsPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundServerLinksPacket;
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundHorseScreenOpenPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatEndPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatEnterPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRecipePacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.network.protocol.game.ClientboundTickingStepPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;

import java.util.Set;

/**
 * Packets that only make sense for the player who recorded (their screens, inventory, camera, connection
 * state) are dropped during playback; everything else reaches the client unchanged.
 *
 * @author vyrriox
 */
public final class PacketFilter {
    private static final Set<Class<?>> DROPPED = Set.of(
            ClientboundKeepAlivePacket.class,
            ClientboundPingPacket.class,
            ClientboundDisconnectPacket.class,
            ClientboundTransferPacket.class,
            ClientboundStoreCookiePacket.class,
            ClientboundCookieRequestPacket.class,
            ClientboundResourcePackPushPacket.class,
            ClientboundResourcePackPopPacket.class,
            ClientboundServerLinksPacket.class,
            ClientboundCustomReportDetailsPacket.class,
            ClientboundStartConfigurationPacket.class,
            ClientboundPlayerPositionPacket.class,
            ClientboundPlayerLookAtPacket.class,
            ClientboundMoveVehiclePacket.class,
            ClientboundSetCameraPacket.class,
            ClientboundSetHealthPacket.class,
            ClientboundSetExperiencePacket.class,
            ClientboundPlayerAbilitiesPacket.class,
            ClientboundSetCarriedItemPacket.class,
            ClientboundPlayerCombatEnterPacket.class,
            ClientboundPlayerCombatEndPacket.class,
            ClientboundPlayerCombatKillPacket.class,
            ClientboundOpenScreenPacket.class,
            ClientboundHorseScreenOpenPacket.class,
            ClientboundOpenBookPacket.class,
            ClientboundOpenSignEditorPacket.class,
            ClientboundMerchantOffersPacket.class,
            ClientboundContainerClosePacket.class,
            ClientboundContainerSetContentPacket.class,
            ClientboundContainerSetDataPacket.class,
            ClientboundContainerSetSlotPacket.class,
            ClientboundCooldownPacket.class,
            ClientboundPlaceGhostRecipePacket.class,
            ClientboundRecipePacket.class,
            ClientboundUpdateRecipesPacket.class,
            ClientboundCommandsPacket.class,
            ClientboundCommandSuggestionsPacket.class,
            ClientboundCustomChatCompletionsPacket.class,
            ClientboundBlockChangedAckPacket.class,
            ClientboundChunkBatchStartPacket.class,
            ClientboundChunkBatchFinishedPacket.class,
            ClientboundTickingStatePacket.class,
            ClientboundTickingStepPacket.class,
            net.minecraft.network.protocol.game.ClientboundDeleteChatPacket.class
    );

    private PacketFilter() {
    }

    private static final Set<Class<?>> TRANSIENT = Set.of(
            net.minecraft.network.protocol.game.ClientboundSoundPacket.class,
            net.minecraft.network.protocol.game.ClientboundSoundEntityPacket.class,
            net.minecraft.network.protocol.game.ClientboundStopSoundPacket.class,
            net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket.class,
            net.minecraft.network.protocol.game.ClientboundLevelEventPacket.class,
            net.minecraft.network.protocol.game.ClientboundExplodePacket.class,
            net.minecraft.network.protocol.game.ClientboundAnimatePacket.class,
            net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket.class,
            net.minecraft.network.protocol.game.ClientboundDamageEventPacket.class,
            net.minecraft.network.protocol.game.ClientboundBlockEventPacket.class,
            net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket.class,
            net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket.class,
            net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket.class,
            net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket.class
    );

    /** Packets whose only effect is momentary (sound, particles, animations, titles): skipped while seeking. */
    public static boolean isTransient(Packet<?> packet) {
        return TRANSIENT.contains(packet.getClass());
    }

    public static boolean shouldDrop(Packet<?> packet) {
        if (DROPPED.contains(packet.getClass())) {
            return true;
        }
        if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                && (custom.payload() instanceof net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload
                || custom.payload() instanceof net.neoforged.neoforge.network.payload.AdvancedContainerSetDataPayload)) {
            return true;
        }
        if (packet instanceof ClientboundGameEventPacket event) {
            ClientboundGameEventPacket.Type type = event.getEvent();
            return type == ClientboundGameEventPacket.CHANGE_GAME_MODE
                    || type == ClientboundGameEventPacket.WIN_GAME
                    || type == ClientboundGameEventPacket.DEMO_EVENT
                    || type == ClientboundGameEventPacket.IMMEDIATE_RESPAWN
                    || type == ClientboundGameEventPacket.LIMITED_CRAFTING;
        }
        return false;
    }
}
