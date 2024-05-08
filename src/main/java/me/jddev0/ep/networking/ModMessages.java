package me.jddev0.ep.networking;

import me.jddev0.ep.networking.packet.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.*;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModMessages {
    private ModMessages() {}

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1.0");

        //Server -> Client
        registrar.playToClient(EnergySyncS2CPacket.ID, EnergySyncS2CPacket.STREAM_CODEC,
                EnergySyncS2CPacket::handle);
        registrar.playToClient(FluidSyncS2CPacket.ID, FluidSyncS2CPacket.STREAM_CODEC,
                FluidSyncS2CPacket::handle);
        registrar.playToClient(ItemStackSyncS2CPacket.ID, ItemStackSyncS2CPacket.STREAM_CODEC,
                ItemStackSyncS2CPacket::handle);
        registrar.playToClient(OpenEnergizedPowerBookS2CPacket.ID, OpenEnergizedPowerBookS2CPacket.STREAM_CODEC,
                OpenEnergizedPowerBookS2CPacket::handle);
        registrar.playToClient(SyncPressMoldMakerRecipeListS2CPacket.ID, SyncPressMoldMakerRecipeListS2CPacket.STREAM_CODEC,
                SyncPressMoldMakerRecipeListS2CPacket::handle);
        registrar.playToClient(SyncStoneSolidifierCurrentRecipeS2CPacket.ID, SyncStoneSolidifierCurrentRecipeS2CPacket.STREAM_CODEC,
                SyncStoneSolidifierCurrentRecipeS2CPacket::handle);
        registrar.playToClient(SyncFiltrationPlantCurrentRecipeS2CPacket.ID, SyncFiltrationPlantCurrentRecipeS2CPacket.STREAM_CODEC,
                SyncFiltrationPlantCurrentRecipeS2CPacket::handle);
        registrar.playToClient(SyncFurnaceRecipeTypeS2CPacket.ID, SyncFurnaceRecipeTypeS2CPacket.STREAM_CODEC,
                SyncFurnaceRecipeTypeS2CPacket::handle);

        //Client -> Server
        registrar.playToServer(PopEnergizedPowerBookFromLecternC2SPacket.ID, PopEnergizedPowerBookFromLecternC2SPacket.STREAM_CODEC,
                PopEnergizedPowerBookFromLecternC2SPacket::handle);
        registrar.playToServer(SetAutoCrafterPatternInputSlotsC2SPacket.ID, SetAutoCrafterPatternInputSlotsC2SPacket.STREAM_CODEC,
                SetAutoCrafterPatternInputSlotsC2SPacket::handle);
        registrar.playToServer(SetAdvancedAutoCrafterPatternInputSlotsC2SPacket.ID, SetAdvancedAutoCrafterPatternInputSlotsC2SPacket.STREAM_CODEC,
                SetAdvancedAutoCrafterPatternInputSlotsC2SPacket::handle);
        registrar.playToServer(SetWeatherFromWeatherControllerC2SPacket.ID, SetWeatherFromWeatherControllerC2SPacket.STREAM_CODEC,
                SetWeatherFromWeatherControllerC2SPacket::handle);
        registrar.playToServer(SetTimeFromTimeControllerC2SPacket.ID, SetTimeFromTimeControllerC2SPacket.STREAM_CODEC,
                SetTimeFromTimeControllerC2SPacket::handle);
        registrar.playToServer(UseTeleporterC2SPacket.ID, UseTeleporterC2SPacket.STREAM_CODEC,
                UseTeleporterC2SPacket::handle);
        registrar.playToServer(SetCheckboxC2SPacket.ID, SetCheckboxC2SPacket.STREAM_CODEC,
                SetCheckboxC2SPacket::handle);
        registrar.playToServer(SetAdvancedAutoCrafterRecipeIndexC2SPacket.ID, SetAdvancedAutoCrafterRecipeIndexC2SPacket.STREAM_CODEC,
                SetAdvancedAutoCrafterRecipeIndexC2SPacket::handle);
        registrar.playToServer(CycleAutoCrafterRecipeOutputC2SPacket.ID, CycleAutoCrafterRecipeOutputC2SPacket.STREAM_CODEC,
                CycleAutoCrafterRecipeOutputC2SPacket::handle);
        registrar.playToServer(CycleAdvancedAutoCrafterRecipeOutputC2SPacket.ID, CycleAdvancedAutoCrafterRecipeOutputC2SPacket.STREAM_CODEC,
                CycleAdvancedAutoCrafterRecipeOutputC2SPacket::handle);
        registrar.playToServer(CraftPressMoldMakerRecipeC2SPacket.ID, CraftPressMoldMakerRecipeC2SPacket.STREAM_CODEC,
                CraftPressMoldMakerRecipeC2SPacket::handle);
        registrar.playToServer(ChangeStoneSolidifierRecipeIndexC2SPacket.ID, ChangeStoneSolidifierRecipeIndexC2SPacket.STREAM_CODEC,
                ChangeStoneSolidifierRecipeIndexC2SPacket::handle);
        registrar.playToServer(ChangeRedstoneModeC2SPacket.ID, ChangeRedstoneModeC2SPacket.STREAM_CODEC,
                ChangeRedstoneModeC2SPacket::handle);
        registrar.playToServer(SetFluidTankFilterC2SPacket.ID, SetFluidTankFilterC2SPacket.STREAM_CODEC,
                SetFluidTankFilterC2SPacket::handle);
        registrar.playToServer(ChangeFiltrationPlantRecipeIndexC2SPacket.ID, ChangeFiltrationPlantRecipeIndexC2SPacket.STREAM_CODEC,
                ChangeFiltrationPlantRecipeIndexC2SPacket::handle);
        registrar.playToServer(ChangeComparatorModeC2SPacket.ID, ChangeComparatorModeC2SPacket.STREAM_CODEC,
                ChangeComparatorModeC2SPacket::handle);
    }

    public static void sendToServer(CustomPacketPayload message) {
        PacketDistributor.sendToServer(message);
    }

    public static void sendToPlayer(CustomPacketPayload message, ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, message);
    }

    public static void sendToPlayersWithinXBlocks(CustomPacketPayload message, BlockPos pos, ServerLevel level, int distance) {
        PacketDistributor.sendToPlayersNear(level, null, pos.getX(), pos.getY(), pos.getZ(), distance, message);
    }

    public static void sendToAllPlayers(CustomPacketPayload message) {
        PacketDistributor.sendToAllPlayers(message);
    }
}
