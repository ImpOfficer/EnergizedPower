package me.jddev0.ep.block.entity.base;

import me.jddev0.ep.energy.IEnergizedPowerEnergyStorage;
import me.jddev0.ep.inventory.upgrade.UpgradeModuleInventory;
import me.jddev0.ep.machine.upgrade.UpgradeModuleModifier;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.inventory.InventoryChangedListener;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

public abstract class UpgradableEnergyStorageBlockEntity<E extends IEnergizedPowerEnergyStorage>
        extends MenuEnergyStorageBlockEntity<E> {
    protected final UpgradeModuleInventory upgradeModuleInventory;
    protected final InventoryChangedListener updateUpgradeModuleListener = container -> updateUpgradeModules();

    public UpgradableEnergyStorageBlockEntity(BlockEntityType<?> type, BlockPos blockPos, BlockState blockState,
                                              String machineName,
                                              long baseEnergyCapacity, long baseEnergyTransferRate,
                                              UpgradeModuleModifier... upgradeModifierSlots) {
        super(type, blockPos, blockState, machineName, baseEnergyCapacity, baseEnergyTransferRate);

        this.upgradeModuleInventory = new UpgradeModuleInventory(upgradeModifierSlots);
        upgradeModuleInventory.addListener(updateUpgradeModuleListener);
    }

    @Override
    protected void writeNbt(@NotNull NbtCompound nbt) {
        //Save Upgrade Module Inventory first
        nbt.put("upgrade_module_inventory", upgradeModuleInventory.saveToNBT());

        super.writeNbt(nbt);
    }

    @Override
    public void readNbt(@NotNull NbtCompound nbt) {
        //Load Upgrade Module Inventory first
        upgradeModuleInventory.removeListener(updateUpgradeModuleListener);
        upgradeModuleInventory.loadFromNBT(nbt.getCompound("upgrade_module_inventory"));
        upgradeModuleInventory.addListener(updateUpgradeModuleListener);

        super.readNbt(nbt);
    }

    public void drops(World level, BlockPos worldPosition) {
        ItemScatterer.spawn(level, worldPosition, upgradeModuleInventory);
    }

    protected void updateUpgradeModules() {
        markDirty();
        syncEnergyToPlayers(32);
    }
}