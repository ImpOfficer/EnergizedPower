package me.jddev0.ep.block.entity;

import me.jddev0.ep.block.PoweredLampBlock;
import me.jddev0.ep.config.ModConfigs;
import me.jddev0.ep.energy.EnergyStoragePacketUpdate;
import me.jddev0.ep.networking.ModMessages;
import me.jddev0.ep.networking.packet.EnergySyncS2CPacket;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import team.reborn.energy.api.base.LimitingEnergyStorage;
import me.jddev0.ep.energy.EnergizedPowerEnergyStorage;

public class PoweredLampBlockEntity extends BlockEntity implements EnergyStoragePacketUpdate {
    public static final long MAX_RECEIVE = ModConfigs.COMMON_POWERED_LAMP_TRANSFER_RATE.getValue();

    final LimitingEnergyStorage energyStorage;
    private final EnergizedPowerEnergyStorage internalEnergyStorage;

    public PoweredLampBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(ModBlockEntities.POWERED_LAMP_ENTITY, blockPos, blockState);

        internalEnergyStorage = new EnergizedPowerEnergyStorage(MAX_RECEIVE, MAX_RECEIVE, MAX_RECEIVE) {
            @Override
            protected void onFinalCommit() {
                markDirty();

                if(world != null && !world.isClient()) {
                    ModMessages.sendServerPacketToPlayersWithinXBlocks(
                            getPos(), (ServerWorld)world, 32,
                            new EnergySyncS2CPacket(amount, capacity, getPos())
                    );
                }
            }
        };
        energyStorage = new LimitingEnergyStorage(internalEnergyStorage, MAX_RECEIVE, 0);
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        nbt.putLong("energy", internalEnergyStorage.amount);

        super.writeNbt(nbt, registries);
    }

    @Override
    protected void readNbt(@NotNull NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.readNbt(nbt, registries);

        internalEnergyStorage.amount = nbt.getLong("energy");
    }

    public static void tick(World level, BlockPos blockPos, BlockState state, PoweredLampBlockEntity blockEntity) {
        if(level.isClient())
            return;

        boolean isEmptyFlag = blockEntity.internalEnergyStorage.amount == 0;
        int levelValue = Math.min(MathHelper.floor((float)blockEntity.internalEnergyStorage.amount / blockEntity.energyStorage.getCapacity() * 14.f) + (isEmptyFlag?0:1), 15);

        if(state.get(PoweredLampBlock.LEVEL) != levelValue)
            level.setBlockState(blockPos, state.with(PoweredLampBlock.LEVEL, levelValue), 3);

        try(Transaction transaction = Transaction.openOuter()) {
            blockEntity.internalEnergyStorage.extract(blockEntity.internalEnergyStorage.amount, transaction);
            transaction.commit();
        }
    }

    public long getEnergy() {
        return internalEnergyStorage.amount;
    }

    public long getCapacity() {
        return internalEnergyStorage.capacity;
    }

    @Override
    public void setEnergy(long energy) {
        internalEnergyStorage.amount = energy;
    }

    @Override
    public void setCapacity(long capacity) {
        internalEnergyStorage.capacity = capacity;
    }
}