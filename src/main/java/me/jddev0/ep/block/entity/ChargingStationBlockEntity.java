package me.jddev0.ep.block.entity;

import me.jddev0.ep.block.ChargingStationBlock;
import me.jddev0.ep.config.ModConfigs;
import me.jddev0.ep.energy.EnergyStoragePacketUpdate;
import me.jddev0.ep.networking.ModMessages;
import me.jddev0.ep.networking.packet.EnergySyncS2CPacket;
import me.jddev0.ep.screen.ChargingStationMenu;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.EnergyStorageUtil;
import team.reborn.energy.api.base.LimitingEnergyStorage;
import me.jddev0.ep.energy.EnergizedPowerEnergyStorage;

import java.util.List;

public class ChargingStationBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory<BlockPos>, EnergyStoragePacketUpdate {
    public static final long CAPACITY = ModConfigs.COMMON_CHARGING_STATION_CAPACITY.getValue();
    public static final long MAX_RECEIVE = ModConfigs.COMMON_CHARGING_STATION_TRANSFER_RATE.getValue();
    public static final int MAX_CHARGING_DISTANCE = ModConfigs.COMMON_CHARGING_STATION_MAX_CHARGING_DISTANCE.getValue();

    final LimitingEnergyStorage energyStorage;
    private final EnergizedPowerEnergyStorage internalEnergyStorage;

    public ChargingStationBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(ModBlockEntities.CHARGING_STATION_ENTITY, blockPos, blockState);

        internalEnergyStorage = new EnergizedPowerEnergyStorage(CAPACITY, CAPACITY, CAPACITY) {
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
    public Text getDisplayName() {
        return Text.translatable("container.energizedpower.charging_station");
    }

    @Nullable
    @Override
    public ScreenHandler createMenu(int id, PlayerInventory inventory, PlayerEntity player) {
        ModMessages.sendServerPacketToPlayer((ServerPlayerEntity)player,
                new EnergySyncS2CPacket(internalEnergyStorage.amount, internalEnergyStorage.capacity, getPos()));
        
        return new ChargingStationMenu(id, this, inventory);
    }

    @Override
    public BlockPos getScreenOpeningData(ServerPlayerEntity player) {
        return pos;
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


    public static void tick(World level, BlockPos blockPos, BlockState state, ChargingStationBlockEntity blockEntity) {
        if(level.isClient())
            return;

        List<PlayerEntity> players = level.getEntitiesByType(TypeFilter.instanceOf(PlayerEntity.class), Box.from(BlockBox.create(
                new Vec3i(blockPos.getX() - MAX_CHARGING_DISTANCE, blockPos.getY() - MAX_CHARGING_DISTANCE,
                        blockPos.getZ() - MAX_CHARGING_DISTANCE),
                new Vec3i(blockPos.getX() + MAX_CHARGING_DISTANCE, blockPos.getY() + MAX_CHARGING_DISTANCE,
                        blockPos.getZ() + MAX_CHARGING_DISTANCE))), EntityPredicates.EXCEPT_SPECTATOR.
                and(entity -> entity.squaredDistanceTo(blockPos.toCenterPos()) <= MAX_CHARGING_DISTANCE*MAX_CHARGING_DISTANCE));

        long energyPerTick = Math.min(MAX_RECEIVE, blockEntity.internalEnergyStorage.amount);
        long energyPerTickLeft = energyPerTick;

        outer:
        for(PlayerEntity player:players) {
            if(player.isDead())
                continue;

            PlayerInventory inventory = player.getInventory();
            for(int i = 0;i < inventory.size();i++) {
                ItemStack itemStack = inventory.getStack(i);

                if(!EnergyStorageUtil.isEnergyStorage(itemStack))
                    continue;

                EnergyStorage energyStorage = EnergyStorage.ITEM.find(itemStack, ContainerItemContext.
                        ofPlayerSlot(inventory.player, InventoryStorage.of(inventory, null).getSlots().get(i)));
                if(energyStorage == null)
                    continue;

                if(!energyStorage.supportsInsertion())
                    continue;

                try(Transaction transaction = Transaction.openOuter()) {
                    energyPerTickLeft -= energyStorage.insert(energyPerTickLeft, transaction);
                    transaction.commit();
                    if(energyPerTickLeft == 0)
                        break outer;
                }
            }
        }

        if(energyPerTickLeft == energyPerTick) {
            if(!level.getBlockState(blockPos).contains(ChargingStationBlock.CHARGING) || level.getBlockState(blockPos).get(ChargingStationBlock.CHARGING))
                level.setBlockState(blockPos, state.with(ChargingStationBlock.CHARGING, false), 3);
        }else {
            if(!level.getBlockState(blockPos).contains(ChargingStationBlock.CHARGING) || !level.getBlockState(blockPos).get(ChargingStationBlock.CHARGING))
                level.setBlockState(blockPos, state.with(ChargingStationBlock.CHARGING, Boolean.TRUE), 3);

            try(Transaction transaction = Transaction.openOuter()) {
                blockEntity.internalEnergyStorage.extract(energyPerTick - energyPerTickLeft, transaction);
                transaction.commit();
            }
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