package me.jddev0.ep.block.entity;

import com.mojang.datafixers.util.Pair;
import me.jddev0.ep.block.CableBlock;
import me.jddev0.ep.energy.EnergyStoragePacketUpdate;
import me.jddev0.ep.energy.ExtractOnlyEnergyStorage;
import me.jddev0.ep.networking.ModMessages;
import me.jddev0.ep.networking.packet.EnergySyncS2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class CableBlockEntity extends BlockEntity {
    private final CableBlock.Tier tier;

    private final IEnergyStorage energyStorage;
    private LazyOptional<IEnergyStorage> lazyEnergyStorage = LazyOptional.empty();

    private final Map<Pair<BlockPos, Direction>, IEnergyStorage> producers = new HashMap<>();
    private final Map<Pair<BlockPos, Direction>, IEnergyStorage> consumers = new HashMap<>();

    public static BlockEntityType<CableBlockEntity> getEntityTypeFromTier(CableBlock.Tier tier) {
        return switch(tier) {
            case TIER_COPPER -> ModBlockEntities.COPPER_CABLE_ENTITY.get();
        };
    }

    public CableBlockEntity(BlockPos blockPos, BlockState blockState, CableBlock.Tier tier) {
        super(getEntityTypeFromTier(tier), blockPos, blockState);

        this.tier = tier;

        //TODO update producer and consumer list (sync maps with other cable blocks)

        energyStorage = new IEnergyStorage() {
            @Override
            public int receiveEnergy(int maxReceive, boolean simulate) {
                return 0;
            }

            @Override
            public int extractEnergy(int maxExtract, boolean simulate) {
                return 0;
            }

            @Override
            public int getEnergyStored() {
                return 0;
            }

            @Override
            public int getMaxEnergyStored() {
                return 0;
            }

            @Override
            public boolean canExtract() {
                return false;
            }

            @Override
            public boolean canReceive() {
                return false;
            }
        };
    }

    public CableBlock.Tier getTier() {
        return tier;
    }

    public static void neighborChanged(BlockState selfState, Level level, BlockPos selfPos, Block fromBlock, BlockPos fromPos, boolean isMoving,
                                       CableBlockEntity blockEntity) {
        if(level.isClientSide)
            return;

        //TODO update producer and consumer list (sync maps with other cable blocks)
    }

    public static void tick(Level level, BlockPos blockPos, BlockState state, CableBlockEntity blockEntity) {
        if(level.isClientSide)
            return;

        int transferLeft = blockEntity.tier.getMaxTransfer();


        //TODO
    }

    public Map<Pair<BlockPos, Direction>, IEnergyStorage> getProducers() {
        return producers;
    }

    public Map<Pair<BlockPos, Direction>, IEnergyStorage> getConsumers() {
        return consumers;
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if(cap == ForgeCapabilities.ENERGY) {
            return lazyEnergyStorage.cast();
        }

        return super.getCapability(cap, side);
    }

    @Override
    public void onLoad() {
        super.onLoad();

        lazyEnergyStorage = LazyOptional.of(() -> energyStorage);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();

        lazyEnergyStorage.invalidate();
    }

    @Override
    protected void saveAdditional(CompoundTag nbt) {
        //TODO

        super.saveAdditional(nbt);
    }

    @Override
    public void load(@NotNull CompoundTag nbt) {
        super.load(nbt);

        //TODO
    }
}
