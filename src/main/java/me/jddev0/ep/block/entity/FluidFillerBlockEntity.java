package me.jddev0.ep.block.entity;

import me.jddev0.ep.block.FluidFillerBlock;
import me.jddev0.ep.block.entity.base.UpgradableInventoryEnergyStorageBlockEntity;
import me.jddev0.ep.inventory.InputOutputItemHandler;
import me.jddev0.ep.config.ModConfigs;
import me.jddev0.ep.energy.ReceiveOnlyEnergyStorage;
import me.jddev0.ep.fluid.FluidStoragePacketUpdate;
import me.jddev0.ep.machine.configuration.ComparatorMode;
import me.jddev0.ep.machine.configuration.ComparatorModeUpdate;
import me.jddev0.ep.machine.configuration.RedstoneMode;
import me.jddev0.ep.machine.configuration.RedstoneModeUpdate;
import me.jddev0.ep.machine.upgrade.UpgradeModuleModifier;
import me.jddev0.ep.networking.ModMessages;
import me.jddev0.ep.networking.packet.EnergySyncS2CPacket;
import me.jddev0.ep.networking.packet.FluidSyncS2CPacket;
import me.jddev0.ep.screen.FluidFillerMenu;
import me.jddev0.ep.util.ByteUtils;
import me.jddev0.ep.util.EnergyUtils;
import me.jddev0.ep.util.FluidUtils;
import me.jddev0.ep.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class FluidFillerBlockEntity
        extends UpgradableInventoryEnergyStorageBlockEntity<ReceiveOnlyEnergyStorage, ItemStackHandler>
        implements MenuProvider, FluidStoragePacketUpdate, RedstoneModeUpdate, ComparatorModeUpdate {
    public static final int MAX_FLUID_FILLING_PER_TICK = ModConfigs.COMMON_FLUID_FILLER_FLUID_ITEM_TRANSFER_RATE.getValue();
    public static final int ENERGY_USAGE_PER_TICK = ModConfigs.COMMON_FLUID_FILLER_ENERGY_CONSUMPTION_PER_TICK.getValue();

    private final IItemHandler itemHandlerSided = new InputOutputItemHandler(itemHandler, (i, stack) -> true, i -> {
        if(i != 0)
            return false;

        ItemStack stack = itemHandler.getStackInSlot(i);

        IFluidHandlerItem fluidStorage = stack.getCapability(Capabilities.FluidHandler.ITEM);
        if(fluidStorage == null)
            return true;

        for(int j = 0;j < fluidStorage.getTanks();j++) {
            FluidStack fluidStack = fluidStorage.getFluidInTank(j);
            if(fluidStorage.getTankCapacity(j) > fluidStack.getAmount() && (FluidFillerBlockEntity.this.fluidStorage.isEmpty() ||
                    (fluidStack.isEmpty() && fluidStorage.isFluidValid(j, FluidFillerBlockEntity.this.fluidStorage.getFluid())) ||
                    FluidStack.isSameFluidSameComponents(fluidStack, FluidFillerBlockEntity.this.fluidStorage.getFluid())))
                return false;
        }

        return true;
    });

    private final FluidTank fluidStorage;

    protected final ContainerData data;
    private int fluidFillingLeft = -1;
    private int fluidFillingSumPending = 0;

    private @NotNull RedstoneMode redstoneMode = RedstoneMode.IGNORE;
    private @NotNull ComparatorMode comparatorMode = ComparatorMode.ITEM;

    public FluidFillerBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(
                ModBlockEntities.FLUID_FILLER_ENTITY.get(), blockPos, blockState,

                ModConfigs.COMMON_FLUID_FILLER_CAPACITY.getValue(),
                ModConfigs.COMMON_FLUID_FILLER_TRANSFER_RATE.getValue(),

                1,

                UpgradeModuleModifier.ENERGY_CONSUMPTION,
                UpgradeModuleModifier.ENERGY_CAPACITY
        );

        fluidStorage = new FluidTank(ModConfigs.COMMON_FLUID_FILLER_FLUID_TANK_CAPACITY.getValue() * 1000) {
            @Override
            protected void onContentsChanged() {
                setChanged();

                if(level != null && !level.isClientSide())
                    ModMessages.sendToPlayersWithinXBlocks(
                            new FluidSyncS2CPacket(0, fluid, capacity, getBlockPos()),
                            getBlockPos(), (ServerLevel)level, 32
                    );
            }
        };
        data = new ContainerData() {
            @Override
            public int get(int index) {
                return switch(index) {
                    case 0, 1 -> ByteUtils.get2Bytes(FluidFillerBlockEntity.this.fluidFillingLeft, index);
                    case 2, 3 -> ByteUtils.get2Bytes(FluidFillerBlockEntity.this.fluidFillingSumPending, index - 2);
                    case 4 -> redstoneMode.ordinal();
                    case 5 -> comparatorMode.ordinal();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch(index) {
                    case 0, 1, 2, 3 -> {}
                    case 4 -> FluidFillerBlockEntity.this.redstoneMode = RedstoneMode.fromIndex(value);
                    case 5 -> FluidFillerBlockEntity.this.comparatorMode = ComparatorMode.fromIndex(value);
                }
            }

            @Override
            public int getCount() {
                return 6;
            }
        };
    }

    @Override
    protected ReceiveOnlyEnergyStorage initEnergyStorage() {
        return new ReceiveOnlyEnergyStorage(0, baseEnergyCapacity, baseEnergyTransferRate) {
            @Override
            public int getCapacity() {
                return Math.max(1, (int)Math.ceil(capacity * upgradeModuleInventory.getModifierEffectProduct(
                        UpgradeModuleModifier.ENERGY_CAPACITY)));
            }

            @Override
            public int getMaxReceive() {
                return Math.max(1, (int)Math.ceil(maxReceive * upgradeModuleInventory.getModifierEffectProduct(
                        UpgradeModuleModifier.ENERGY_TRANSFER_RATE)));
            }

            @Override
            protected void onChange() {
                setChanged();

                if(level != null && !level.isClientSide())
                    ModMessages.sendToPlayersWithinXBlocks(
                            new EnergySyncS2CPacket(getEnergy(), getCapacity(), getBlockPos()),
                            getBlockPos(), (ServerLevel)level, 32
                    );
            }
        };
    }

    @Override
    protected ItemStackHandler initInventoryStorage() {
        return new ItemStackHandler(slotCount) {
            @Override
            protected void onContentsChanged(int slot) {
                setChanged();
            }

            @Override
            public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                if(slot == 0)
                    return stack.getCapability(Capabilities.FluidHandler.ITEM) != null;

                return super.isItemValid(slot, stack);
            }

            @Override
            public void setStackInSlot(int slot, @NotNull ItemStack stack) {
                if(slot == 0) {
                    ItemStack itemStack = getStackInSlot(slot);
                    if(level != null && !stack.isEmpty() && !itemStack.isEmpty() && (!ItemStack.isSameItem(stack, itemStack) ||
                            (!ItemStack.isSameItemSameComponents(stack, itemStack) &&
                                    //Only check if NBT data is equal if one of stack or itemStack is no fluid item
                                    !(stack.getCapability(Capabilities.FluidHandler.ITEM) != null &&
                                            itemStack.getCapability(Capabilities.FluidHandler.ITEM) != null))))
                        resetProgress();
                }

                super.setStackInSlot(slot, stack);
            }

            @Override
            public int getSlotLimit(int slot) {
                return 1;
            }
        };
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.energizedpower.fluid_filler");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        ModMessages.sendToPlayer(new EnergySyncS2CPacket(energyStorage.getEnergy(), energyStorage.getCapacity(), getBlockPos()), (ServerPlayer)player);
        ModMessages.sendToPlayer(new FluidSyncS2CPacket(0, fluidStorage.getFluid(), fluidStorage.getCapacity(), worldPosition), (ServerPlayer)player);

        return new FluidFillerMenu(id, inventory, this, upgradeModuleInventory, this.data);
    }

    public int getRedstoneOutput() {
        return switch(comparatorMode) {
            case ITEM -> InventoryUtils.getRedstoneSignalFromItemStackHandler(itemHandler);
            case FLUID -> FluidUtils.getRedstoneSignalFromFluidHandler(fluidStorage);
            case ENERGY -> EnergyUtils.getRedstoneSignalFromEnergyStorage(energyStorage);
        };
    }

    public @Nullable IItemHandler getItemHandlerCapability(@Nullable Direction side) {
        if(side == null)
            return itemHandler;

        return itemHandlerSided;
    }

    public @Nullable IFluidHandler getFluidHandlerCapability(@Nullable Direction side) {
        return fluidStorage;
    }

    public @Nullable IEnergyStorage getEnergyStorageCapability(@Nullable Direction side) {
        return energyStorage;
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(nbt, registries);

        nbt.put("fluid", fluidStorage.writeToNBT(registries, new CompoundTag()));

        nbt.put("recipe.fluid_filling_left", IntTag.valueOf(fluidFillingLeft));
        nbt.put("recipe.fluid_filling_sum_pending", IntTag.valueOf(fluidFillingSumPending));

        nbt.putInt("configuration.redstone_mode", redstoneMode.ordinal());
        nbt.putInt("configuration.comparator_mode", comparatorMode.ordinal());
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(nbt, registries);

        fluidStorage.readFromNBT(registries, nbt.getCompound("fluid"));

        fluidFillingLeft = nbt.getInt("recipe.fluid_filling_left");
        fluidFillingSumPending = nbt.getInt("recipe.fluid_filling_sum_pending");

        redstoneMode = RedstoneMode.fromIndex(nbt.getInt("configuration.redstone_mode"));
        comparatorMode = ComparatorMode.fromIndex(nbt.getInt("configuration.comparator_mode"));
    }

    public static void tick(Level level, BlockPos blockPos, BlockState state, FluidFillerBlockEntity blockEntity) {
        if(level.isClientSide)
            return;

        if(!blockEntity.redstoneMode.isActive(state.getValue(FluidFillerBlock.POWERED)))
            return;

        if(blockEntity.hasRecipe()) {
            ItemStack stack = blockEntity.itemHandler.getStackInSlot(0);

            int fluidFillingSum = 0;
            int fluidFillingLeftSum = 0;

            if(blockEntity.fluidStorage.getFluidAmount() - blockEntity.fluidFillingSumPending <= 0)
                return;

            int energyConsumptionPerTick = Math.max(1, (int)Math.ceil(ENERGY_USAGE_PER_TICK *
                    blockEntity.upgradeModuleInventory.getModifierEffectProduct(UpgradeModuleModifier.ENERGY_CONSUMPTION)));

            if(blockEntity.energyStorage.getEnergy() < energyConsumptionPerTick)
                return;

            IFluidHandlerItem fluidStorage = stack.getCapability(Capabilities.FluidHandler.ITEM);
            if(fluidStorage == null)
                return;

            for(int i = 0;i < fluidStorage.getTanks();i++) {
                FluidStack fluidStack = fluidStorage.getFluidInTank(i);
                if(fluidStorage.getTankCapacity(i) > fluidStack.getAmount() && (blockEntity.fluidStorage.isEmpty() ||
                        (fluidStack.isEmpty() && fluidStorage.isFluidValid(i, blockEntity.fluidStorage.getFluid())) ||
                        FluidStack.isSameFluidSameComponents(fluidStack, blockEntity.fluidStorage.getFluid()))) {
                    fluidFillingSum += Math.min(blockEntity.fluidStorage.getFluidAmount() -
                                    blockEntity.fluidFillingSumPending - fluidFillingSum,
                            Math.min(fluidStorage.getTankCapacity(i) - fluidStack.getAmount(),
                                    MAX_FLUID_FILLING_PER_TICK - fluidFillingSum));

                    fluidFillingLeftSum += fluidStorage.getTankCapacity(i) - fluidStack.getAmount();
                }
            }

            if(fluidFillingSum == 0)
                return;

            blockEntity.fluidFillingLeft = fluidFillingLeftSum;
            blockEntity.fluidFillingSumPending += fluidFillingSum;

            blockEntity.energyStorage.setEnergy(blockEntity.energyStorage.getEnergy() - energyConsumptionPerTick);

            int fluidSumFillable = Math.min(blockEntity.fluidStorage.getFluidAmount(),
                    blockEntity.fluidFillingSumPending);

            FluidStack fluidStackToFill = blockEntity.getFluid(0);

            int fluidSumFilled = fluidStorage.fill(new FluidStack(fluidStackToFill.getFluidHolder(), fluidSumFillable,
                    fluidStackToFill.getComponentsPatch()), IFluidHandler.FluidAction.EXECUTE);

            if(fluidSumFilled <= 0) {
                setChanged(level, blockPos, state);

                return;
            }

            blockEntity.itemHandler.setStackInSlot(0, fluidStorage.getContainer());

            blockEntity.fluidStorage.drain(fluidSumFilled, IFluidHandler.FluidAction.EXECUTE);
            blockEntity.fluidFillingSumPending -= fluidSumFilled;
            blockEntity.fluidFillingLeft = fluidFillingLeftSum - fluidSumFilled;

            if(blockEntity.fluidFillingLeft <= 0)
                blockEntity.resetProgress();

            setChanged(level, blockPos, state);
        }else {
            blockEntity.resetProgress();
            setChanged(level, blockPos, state);
        }
    }

    private void resetProgress() {
        fluidFillingLeft = -1;
        fluidFillingSumPending = 0;
    }

    private boolean hasRecipe() {
        ItemStack stack = itemHandler.getStackInSlot(0);
        if(stack.getCapability(Capabilities.FluidHandler.ITEM) != null) {
            IFluidHandlerItem fluidStorage = stack.getCapability(Capabilities.FluidHandler.ITEM);
            if(fluidStorage == null)
                return false;

            for(int i = 0;i < fluidStorage.getTanks();i++) {
                FluidStack fluidStack = fluidStorage.getFluidInTank(i);
                if(fluidStorage.getTankCapacity(i) > fluidStack.getAmount() && (FluidFillerBlockEntity.this.fluidStorage.isEmpty() ||
                        (fluidStack.isEmpty() && fluidStorage.isFluidValid(i, FluidFillerBlockEntity.this.fluidStorage.getFluid())) ||
                        FluidStack.isSameFluidSameComponents(fluidStack, FluidFillerBlockEntity.this.fluidStorage.getFluid())))
                    return true;
            }
        }

        return false;
    }

    @Override
    protected void updateUpgradeModules() {
        resetProgress();

        super.updateUpgradeModules();
    }

    public FluidStack getFluid(int tank) {
        return fluidStorage.getFluid();
    }

    public int getTankCapacity(int tank) {
        return fluidStorage.getCapacity();
    }

    @Override
    public void setFluid(int tank, FluidStack fluidStack) {
        fluidStorage.setFluid(fluidStack);
    }

    @Override
    public void setTankCapacity(int tank, int capacity) {
        fluidStorage.setCapacity(capacity);
    }

    @Override
    public void setNextRedstoneMode() {
        redstoneMode = RedstoneMode.fromIndex(redstoneMode.ordinal() + 1);
        setChanged();
    }

    @Override
    public void setNextComparatorMode() {
        comparatorMode = ComparatorMode.fromIndex(comparatorMode.ordinal() + 1);
        setChanged();
    }
}