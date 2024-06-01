package me.jddev0.ep.screen;

import me.jddev0.ep.block.ModBlocks;
import me.jddev0.ep.block.entity.AdvancedAutoCrafterBlockEntity;
import me.jddev0.ep.inventory.ConstraintInsertSlot;
import me.jddev0.ep.inventory.PatternResultSlot;
import me.jddev0.ep.inventory.PatternSlot;
import me.jddev0.ep.inventory.*;
import me.jddev0.ep.inventory.upgrade.UpgradeModuleInventory;
import me.jddev0.ep.machine.configuration.ComparatorMode;
import me.jddev0.ep.machine.configuration.RedstoneMode;
import me.jddev0.ep.machine.upgrade.UpgradeModuleModifier;
import me.jddev0.ep.screen.base.IConfigurableMenu;
import me.jddev0.ep.screen.base.IEnergyStorageConsumerIndicatorBarMenu;
import me.jddev0.ep.screen.base.UpgradableEnergyStorageMenu;
import me.jddev0.ep.util.ByteUtils;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.slot.Slot;

public class AdvancedAutoCrafterMenu extends UpgradableEnergyStorageMenu<AdvancedAutoCrafterBlockEntity>
        implements IEnergyStorageConsumerIndicatorBarMenu, IConfigurableMenu {
    private final PropertyDelegate data;

    private final Inventory[] patternSlots;

    private final Inventory[] patternResultSlots;

    public AdvancedAutoCrafterMenu(int id, PlayerInventory inv, PacketByteBuf buf) {
        this(id, inv.player.getWorld().getBlockEntity(buf.readBlockPos()), inv, new SimpleInventory(27) {
            @Override
            public boolean isValid(int slot, ItemStack stack) {
                return super.isValid(slot, stack) && slot >= 5;
            }
        }, new UpgradeModuleInventory(
                UpgradeModuleModifier.SPEED,
                UpgradeModuleModifier.ENERGY_CONSUMPTION,
                UpgradeModuleModifier.ENERGY_CAPACITY
        ), new Inventory[] {
                new SimpleInventory(9), new SimpleInventory(9), new SimpleInventory(9)
        }, new Inventory[] {
                new SimpleInventory(1), new SimpleInventory(1), new SimpleInventory(1)
        }, new ArrayPropertyDelegate(34));
    }

    public AdvancedAutoCrafterMenu(int id, BlockEntity blockEntity, PlayerInventory playerInventory, Inventory inv,
                                   UpgradeModuleInventory upgradeModuleInventory, Inventory[] patternSlots,
                                   Inventory[] patternResultSlots, PropertyDelegate data) {
        super(
                ModMenuTypes.ADVANCED_AUTO_CRAFTER_MENU, id,

                playerInventory, blockEntity,
                ModBlocks.ADVANCED_AUTO_CRAFTER,
                8, 142,

                upgradeModuleInventory, 3
        );

        this.patternSlots = patternSlots;
        this.patternResultSlots = patternResultSlots;

        checkSize(inv, 27);
        checkDataCount(data, 34);
        this.data = data;

        for(int i = 0;i < 3;i++)
            for(int j = 0;j < 9;j++)
                addSlot(new ConstraintInsertSlot(inv, 9 * i + j, 8 + 18 * j, 75 + 18 * i));

        for(int ri = 0;ri < 3;ri++) {
            final int recipeIndex = ri;

            for(int i = 0;i < 3;i++) {
                for(int j = 0;j < 3;j++) {
                    addSlot(new PatternSlot(patternSlots[recipeIndex], j + i * 3, 30 + j * 18, 17 + i * 18, () -> true) {
                        @Override
                        public boolean isEnabled() {
                            return super.isEnabled() && !isInUpgradeModuleView() && getRecipeIndex() == recipeIndex;
                        }
                    });
                }
            }

            addSlot(new PatternResultSlot(patternResultSlots[recipeIndex], 0, 124, 35, () -> true) {
                @Override
                public boolean isEnabled() {
                    return super.isEnabled() && !isInUpgradeModuleView() && getRecipeIndex() == recipeIndex;
                }
            });
        }

        for(int i = 0;i < upgradeModuleInventory.size();i++)
            addSlot(new UpgradeModuleSlot(upgradeModuleInventory, i, 62 + i * 18, 35, this::isInUpgradeModuleView));

        addProperties(this.data);
    }

    public Inventory[] getPatternSlots() {
        return patternSlots;
    }

    @Override
    public long getEnergyIndicatorBarValue() {
        long energyIndicatorBarValueSum = -1;

        for(int i = 12;i < 24;i += 4) {
            long value = ByteUtils.from2ByteChunks((short)data.get(i), (short)data.get(i + 1),
                    (short)data.get(i + 2), (short)data.get(i + 3));

            //Prevent overflow
            if(Math.max(0, energyIndicatorBarValueSum) + Math.max(0, value) < 0)
                return Long.MAX_VALUE;

            if(value > -1) {
                if(energyIndicatorBarValueSum == -1)
                    energyIndicatorBarValueSum++;

                energyIndicatorBarValueSum += value;
            }
        }

        return energyIndicatorBarValueSum;
    }

    /**
     * @return Same as isCrafting but energy requirements are ignored
     */
    public boolean isCraftingActive() {
        int index = getRecipeIndex();
        return ByteUtils.from2ByteChunks((short)data.get(4 * index), (short)data.get(1 + 4 * index)) > 0;
    }

    public boolean isCrafting() {
        int index = getRecipeIndex();
        return ByteUtils.from2ByteChunks((short)data.get(4 * index), (short)data.get(1 + 4 * index)) > 0 && data.get(18 + index) == 1;
    }

    public int getScaledProgressArrowSize() {
        int index = getRecipeIndex();
        int progress = ByteUtils.from2ByteChunks((short)data.get(4 * index), (short)data.get(1 + 4 * index));
        int maxProgress = ByteUtils.from2ByteChunks((short)data.get(2 + 4 * index), (short)data.get(3 + 4 * index));
        int progressArrowSize = 24;

        return (maxProgress == 0 || progress == 0)?0:progress * progressArrowSize / maxProgress;
    }

    public boolean isIgnoreNBT() {
        int index = getRecipeIndex();
        return data.get(27 + index) != 0;
    }

    public boolean isSecondaryExtractMode() {
        return data.get(30) != 0;
    }

    public int getRecipeIndex() {
        return data.get(31);
    }

    @Override
    public RedstoneMode getRedstoneMode() {
        return RedstoneMode.fromIndex(data.get(32));
    }

    @Override
    public ComparatorMode getComparatorMode() {
        return ComparatorMode.fromIndex(data.get(33));
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        Slot sourceSlot = slots.get(index);
        if(sourceSlot == null || !sourceSlot.hasStack())
            return ItemStack.EMPTY;

        ItemStack sourceItem = sourceSlot.getStack();
        ItemStack sourceItemCopy = sourceItem.copy();

        if(index < 4 * 9) {
            //Player inventory slot -> Merge into upgrade module inventory, Merge into tile inventory
            //"+ 27": Ignore 3 * (3x3 crafting grid and result slot)
            if(!insertMaxCount1Item(sourceItem, 4 * 9 + 27 + 3 * (3*3 + 1), 4 * 9 + 27 + 3 * (3*3 + 1) + 3, false) &&
                    !insertItem(sourceItem, 4 * 9 + 5, 4 * 9 + 27, false)) {
                //"+5" instead of nothing: Do not allow adding to first 5 output item only slot
                return ItemStack.EMPTY;
            }
        }else if(index < 4 * 9 + 27) {
            //Tile inventory slot -> Merge into player inventory
            if(!insertItem(sourceItem, 0, 4 * 9, false)) {
                return ItemStack.EMPTY;
            }
        }else if(index < 4 * 9 + 27 + 3 * (3*3 + 1)) {
            return ItemStack.EMPTY;
        }else if(index < 4 * 9 + 27 + 3 * (3*3 + 1) + 3) {
            //Tile inventory and upgrade module slot -> Merge into player inventory
            if(!insertItem(sourceItem, 0, 4 * 9, false)) {
                return ItemStack.EMPTY;
            }
        }else{
            throw new IllegalArgumentException("Invalid slot index");
        }

        if(sourceItem.getCount() == 0)
            sourceSlot.setStack(ItemStack.EMPTY);
        else
            sourceSlot.markDirty();

        sourceSlot.onTakeItem(player, sourceItem);

        return sourceItemCopy;
    }
}
