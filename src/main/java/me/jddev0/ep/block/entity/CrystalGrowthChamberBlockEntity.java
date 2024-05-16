package me.jddev0.ep.block.entity;

import me.jddev0.ep.block.CrystalGrowthChamberBlock;
import me.jddev0.ep.block.entity.handler.InputOutputItemHandler;
import me.jddev0.ep.config.ModConfigs;
import me.jddev0.ep.energy.EnergyStoragePacketUpdate;
import me.jddev0.ep.energy.ReceiveOnlyEnergyStorage;
import me.jddev0.ep.machine.configuration.ComparatorMode;
import me.jddev0.ep.machine.configuration.ComparatorModeUpdate;
import me.jddev0.ep.machine.configuration.RedstoneMode;
import me.jddev0.ep.machine.configuration.RedstoneModeUpdate;
import me.jddev0.ep.networking.ModMessages;
import me.jddev0.ep.networking.packet.EnergySyncS2CPacket;
import me.jddev0.ep.recipe.CrystalGrowthChamberRecipe;
import me.jddev0.ep.screen.CrystalGrowthChamberMenu;
import me.jddev0.ep.util.ByteUtils;
import me.jddev0.ep.util.EnergyUtils;
import me.jddev0.ep.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class CrystalGrowthChamberBlockEntity extends BlockEntity implements MenuProvider, EnergyStoragePacketUpdate, RedstoneModeUpdate,
        ComparatorModeUpdate {
    private static final int ENERGY_USAGE_PER_TICK = ModConfigs.COMMON_CRYSTAL_GROWTH_CHAMBER_ENERGY_CONSUMPTION_PER_TICK.getValue();

    public static final float RECIPE_DURATION_MULTIPLIER = ModConfigs.COMMON_CRYSTAL_GROWTH_CHAMBER_RECIPE_DURATION_MULTIPLIER.getValue();
    private final ItemStackHandler itemHandler = new ItemStackHandler(2) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return switch(slot) {
                case 0 -> level == null || level.getRecipeManager().
                        getAllRecipesFor(CrystalGrowthChamberRecipe.Type.INSTANCE).stream().
                        map(RecipeHolder::value).map(CrystalGrowthChamberRecipe::getInput).
                        anyMatch(ingredient -> ingredient.test(stack));
                case 1 -> false;
                default -> super.isItemValid(slot, stack);
            };
        }

        @Override
        public void setStackInSlot(int slot, @NotNull ItemStack stack) {
            if(slot == 0) {
                ItemStack itemStack = getStackInSlot(slot);
                if(level != null && !stack.isEmpty() && !itemStack.isEmpty() && !ItemStack.isSameItemSameTags(stack, itemStack))
                    resetProgress(worldPosition, level.getBlockState(worldPosition));
            }

            super.setStackInSlot(slot, stack);
        }
    };
    private final IItemHandler itemHandlerSided = new InputOutputItemHandler(itemHandler, (i, stack) -> i == 0, i -> i == 1);

    private final ReceiveOnlyEnergyStorage energyStorage;

    protected final ContainerData data;
    private int progress;
    private int maxProgress;
    private int energyConsumptionLeft = -1;
    private boolean hasEnoughEnergy;

    private @NotNull RedstoneMode redstoneMode = RedstoneMode.IGNORE;
    private @NotNull ComparatorMode comparatorMode = ComparatorMode.ITEM;

    public CrystalGrowthChamberBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(ModBlockEntities.CRYSTAL_GROWTH_CHAMBER_ENTITY.get(), blockPos, blockState);

        energyStorage = new ReceiveOnlyEnergyStorage(0, ModConfigs.COMMON_CRYSTAL_GROWTH_CHAMBER_CAPACITY.getValue(),
                ModConfigs.COMMON_CRYSTAL_GROWTH_CHAMBER_TRANSFER_RATE.getValue()) {
            @Override
            protected void onChange() {
                setChanged();

                if(level != null && !level.isClientSide())
                    ModMessages.sendToPlayersWithinXBlocks(
                            new EnergySyncS2CPacket(energy, capacity, getBlockPos()),
                            getBlockPos(), level.dimension(), 32
                    );
            }
        };
        data = new ContainerData() {
            @Override
            public int get(int index) {
                return switch(index) {
                    case 0, 1 -> ByteUtils.get2Bytes(CrystalGrowthChamberBlockEntity.this.progress, index);
                    case 2, 3 -> ByteUtils.get2Bytes(CrystalGrowthChamberBlockEntity.this.maxProgress, index - 2);
                    case 4, 5 -> ByteUtils.get2Bytes(CrystalGrowthChamberBlockEntity.this.energyConsumptionLeft, index - 4);
                    case 6 -> hasEnoughEnergy?1:0;
                    case 7 -> redstoneMode.ordinal();
                    case 8 -> comparatorMode.ordinal();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch(index) {
                    case 0, 1 -> CrystalGrowthChamberBlockEntity.this.progress = ByteUtils.with2Bytes(
                            CrystalGrowthChamberBlockEntity.this.progress, (short)value, index
                    );
                    case 2, 3 -> CrystalGrowthChamberBlockEntity.this.maxProgress = ByteUtils.with2Bytes(
                            CrystalGrowthChamberBlockEntity.this.maxProgress, (short)value, index - 2
                    );
                    case 4, 5, 6 -> {}
                    case 7 -> CrystalGrowthChamberBlockEntity.this.redstoneMode = RedstoneMode.fromIndex(value);
                    case 8 -> CrystalGrowthChamberBlockEntity.this.comparatorMode = ComparatorMode.fromIndex(value);
                }
            }

            @Override
            public int getCount() {
                return 9;
            }
        };
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.energizedpower.crystal_growth_chamber");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        ModMessages.sendToPlayer(new EnergySyncS2CPacket(energyStorage.getEnergy(), energyStorage.getCapacity(),
                getBlockPos()), (ServerPlayer)player);

        return new CrystalGrowthChamberMenu(id, inventory, this, this.data);
    }

    public int getRedstoneOutput() {
        return switch(comparatorMode) {
            case ITEM -> InventoryUtils.getRedstoneSignalFromItemStackHandler(itemHandler);
            case FLUID -> 0;
            case ENERGY -> EnergyUtils.getRedstoneSignalFromEnergyStorage(energyStorage);
        };
    }

    public @Nullable IItemHandler getItemHandlerCapability(@Nullable Direction side) {
        if(side == null)
            return itemHandler;

        return itemHandlerSided;
    }

    public @Nullable IEnergyStorage getEnergyStorageCapability(@Nullable Direction side) {
        return energyStorage;
    }

    @Override
    protected void saveAdditional(CompoundTag nbt) {
        nbt.put("inventory", itemHandler.serializeNBT());
        nbt.put("energy", energyStorage.saveNBT());

        nbt.put("recipe.progress", IntTag.valueOf(progress));
        nbt.put("recipe.max_progress", IntTag.valueOf(maxProgress));
        nbt.put("recipe.energy_consumption_left", IntTag.valueOf(energyConsumptionLeft));

        nbt.putInt("configuration.redstone_mode", redstoneMode.ordinal());
        nbt.putInt("configuration.comparator_mode", comparatorMode.ordinal());

        super.saveAdditional(nbt);
    }

    @Override
    public void load(@NotNull CompoundTag nbt) {
        super.load(nbt);

        itemHandler.deserializeNBT(nbt.getCompound("inventory"));
        energyStorage.loadNBT(nbt.get("energy"));

        progress = nbt.getInt("recipe.progress");
        maxProgress = nbt.getInt("recipe.max_progress");
        energyConsumptionLeft = nbt.getInt("recipe.energy_consumption_left");

        redstoneMode = RedstoneMode.fromIndex(nbt.getInt("configuration.redstone_mode"));
        comparatorMode = ComparatorMode.fromIndex(nbt.getInt("configuration.comparator_mode"));
    }

    public void drops(Level level, BlockPos worldPosition) {
        SimpleContainer inventory = new SimpleContainer(itemHandler.getSlots());
        for(int i = 0;i < itemHandler.getSlots();i++)
            inventory.setItem(i, itemHandler.getStackInSlot(i));

        Containers.dropContents(level, worldPosition, inventory);
    }

    public static void tick(Level level, BlockPos blockPos, BlockState state, CrystalGrowthChamberBlockEntity blockEntity) {
        if(level.isClientSide)
            return;

        if(!blockEntity.redstoneMode.isActive(state.getValue(CrystalGrowthChamberBlock.POWERED)))
            return;

        if(hasRecipe(blockEntity)) {
            SimpleContainer inventory = new SimpleContainer(blockEntity.itemHandler.getSlots());
            for(int i = 0;i < blockEntity.itemHandler.getSlots();i++)
                inventory.setItem(i, blockEntity.itemHandler.getStackInSlot(i));

            Optional<RecipeHolder<CrystalGrowthChamberRecipe>> recipe = level.getRecipeManager().getRecipeFor(CrystalGrowthChamberRecipe.Type.INSTANCE, inventory, level);
            if(recipe.isEmpty())
                return;

            if(blockEntity.maxProgress == 0)
                blockEntity.maxProgress = (int)(recipe.get().value().getTicks() * RECIPE_DURATION_MULTIPLIER);

            if(blockEntity.energyConsumptionLeft < 0)
                blockEntity.energyConsumptionLeft = ENERGY_USAGE_PER_TICK * blockEntity.maxProgress;

            if(ENERGY_USAGE_PER_TICK <= blockEntity.energyStorage.getEnergy()) {
                blockEntity.hasEnoughEnergy = true;

                if(blockEntity.progress < 0 || blockEntity.maxProgress < 0 || blockEntity.energyConsumptionLeft < 0) {
                    //Reset progress for invalid values

                    blockEntity.resetProgress(blockPos, state);
                    setChanged(level, blockPos, state);

                    return;
                }

                blockEntity.energyStorage.setEnergy(blockEntity.energyStorage.getEnergy() - ENERGY_USAGE_PER_TICK);
                blockEntity.energyConsumptionLeft -= ENERGY_USAGE_PER_TICK;

                blockEntity.progress++;
                if(blockEntity.progress >= blockEntity.maxProgress)
                    craftItem(blockPos, state, blockEntity);

                setChanged(level, blockPos, state);
            }else {
                blockEntity.hasEnoughEnergy = false;
                setChanged(level, blockPos, state);
            }
        }else {
            blockEntity.resetProgress(blockPos, state);
            setChanged(level, blockPos, state);
        }
    }

    private void resetProgress(BlockPos blockPos, BlockState state) {
        progress = 0;
        maxProgress = 0;
        energyConsumptionLeft = -1;
        hasEnoughEnergy = true;
    }

    private static void craftItem(BlockPos blockPos, BlockState state, CrystalGrowthChamberBlockEntity blockEntity) {
        Level level = blockEntity.level;

        SimpleContainer inventory = new SimpleContainer(blockEntity.itemHandler.getSlots());
        for(int i = 0;i < blockEntity.itemHandler.getSlots();i++)
            inventory.setItem(i, blockEntity.itemHandler.getStackInSlot(i));

        Optional<RecipeHolder<CrystalGrowthChamberRecipe>> recipe = level.getRecipeManager().getRecipeFor(CrystalGrowthChamberRecipe.Type.INSTANCE, inventory, level);

        if(!hasRecipe(blockEntity) || recipe.isEmpty())
            return;

        blockEntity.itemHandler.extractItem(0, recipe.get().value().getInputCount(), false);

        ItemStack output = recipe.get().value().generateOutput(level.random);

        if(!output.isEmpty())
            blockEntity.itemHandler.setStackInSlot(1, output.copyWithCount(
                    blockEntity.itemHandler.getStackInSlot(1).getCount() + output.getCount()));

        blockEntity.resetProgress(blockPos, state);
    }

    private static boolean hasRecipe(CrystalGrowthChamberBlockEntity blockEntity) {
        Level level = blockEntity.level;

        SimpleContainer inventory = new SimpleContainer(blockEntity.itemHandler.getSlots());
        for(int i = 0;i < blockEntity.itemHandler.getSlots();i++)
            inventory.setItem(i, blockEntity.itemHandler.getStackInSlot(i));

        Optional<RecipeHolder<CrystalGrowthChamberRecipe>> recipe = level.getRecipeManager().getRecipeFor(CrystalGrowthChamberRecipe.Type.INSTANCE, inventory, level);

        return recipe.isPresent() && canInsertItemIntoOutputSlot(inventory, recipe.get().value().getMaxOutputCount());
    }

    private static boolean canInsertItemIntoOutputSlot(SimpleContainer inventory, ItemStack itemStack) {
        ItemStack inventoryItemStack = inventory.getItem(1);

        return (inventoryItemStack.isEmpty() || ItemStack.isSameItemSameTags(inventoryItemStack, itemStack)) &&
                inventoryItemStack.getMaxStackSize() >= inventoryItemStack.getCount() + itemStack.getCount();
    }

    public int getEnergy() {
        return energyStorage.getEnergy();
    }

    public int getCapacity() {
        return energyStorage.getCapacity();
    }

    @Override
    public void setEnergy(int energy) {
        energyStorage.setEnergyWithoutUpdate(energy);
    }

    @Override
    public void setCapacity(int capacity) {
        energyStorage.setCapacityWithoutUpdate(capacity);
    }

    @Override
    public void setNextRedstoneMode() {
        redstoneMode = RedstoneMode.fromIndex(redstoneMode.ordinal() + 1);
        setChanged();
    }

    @Override
    public void setNextComparatorMode() {
        do {
            comparatorMode = ComparatorMode.fromIndex(comparatorMode.ordinal() + 1);
        }while(comparatorMode == ComparatorMode.FLUID); //Prevent the FLUID comparator mode from being selected
        setChanged();
    }
}