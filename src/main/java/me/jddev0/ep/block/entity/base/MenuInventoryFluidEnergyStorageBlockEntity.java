package me.jddev0.ep.block.entity.base;

import me.jddev0.ep.energy.IEnergizedPowerEnergyStorage;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public abstract class MenuInventoryFluidEnergyStorageBlockEntity
        <E extends IEnergizedPowerEnergyStorage, I extends SimpleInventory, F extends Storage<FluidVariant>>
        extends InventoryFluidEnergyStorageBlockEntity<E, I, F>
        implements ExtendedScreenHandlerFactory<BlockPos> {
    protected final String machineName;

    protected final PropertyDelegate data;

    public MenuInventoryFluidEnergyStorageBlockEntity(BlockEntityType<?> type, BlockPos blockPos, BlockState blockState,
                                                 String machineName,
                                                 long baseEnergyCapacity, long baseEnergyTransferRate,
                                                 int slotCount,
                                                 FluidStorageMethods<F> fluidStorageMethods, long baseTankCapacity) {
        super(type, blockPos, blockState, baseEnergyCapacity, baseEnergyTransferRate, slotCount, fluidStorageMethods,
                baseTankCapacity);

        this.machineName = machineName;

        data = initContainerData();
    }

    protected PropertyDelegate initContainerData() {
        return new ArrayPropertyDelegate(0);
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable("container.energizedpower." + machineName);
    }

    @Override
    public BlockPos getScreenOpeningData(ServerPlayerEntity player) {
        return pos;
    }
}