package me.jddev0.ep.block.entity.base;

import me.jddev0.ep.energy.IEnergizedPowerEnergyStorage;
import me.jddev0.ep.inventory.upgrade.UpgradeModuleInventory;
import me.jddev0.ep.machine.upgrade.UpgradeModuleModifier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerListener;
import net.minecraft.world.Containers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

public abstract class UpgradableEnergyStorageBlockEntity<E extends IEnergizedPowerEnergyStorage>
        extends MenuEnergyStorageBlockEntity<E> {
    protected final UpgradeModuleInventory upgradeModuleInventory;
    protected final ContainerListener updateUpgradeModuleListener = container -> updateUpgradeModules();

    public UpgradableEnergyStorageBlockEntity(BlockEntityType<?> type, BlockPos blockPos, BlockState blockState,
                                              String machineName,
                                              int baseEnergyCapacity, int baseEnergyTransferRate,
                                              UpgradeModuleModifier... upgradeModifierSlots) {
        super(type, blockPos, blockState, machineName, baseEnergyCapacity, baseEnergyTransferRate);

        this.upgradeModuleInventory = new UpgradeModuleInventory(upgradeModifierSlots);
        upgradeModuleInventory.addListener(updateUpgradeModuleListener);
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag nbt) {
        //Save Upgrade Module Inventory first
        nbt.put("upgrade_module_inventory", upgradeModuleInventory.saveToNBT());

        super.saveAdditional(nbt);
    }

    @Override
    public void load(@NotNull CompoundTag nbt) {
        //Load Upgrade Module Inventory first
        upgradeModuleInventory.removeListener(updateUpgradeModuleListener);
        upgradeModuleInventory.loadFromNBT(nbt.getCompound("upgrade_module_inventory"));
        upgradeModuleInventory.addListener(updateUpgradeModuleListener);

        super.load(nbt);
    }

    public void drops(Level level, BlockPos worldPosition) {
        Containers.dropContents(level, worldPosition, upgradeModuleInventory);
    }

    protected void updateUpgradeModules() {
        setChanged();
        syncEnergyToPlayers(32);
    }
}