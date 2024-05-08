package me.jddev0.ep.block.entity;

import com.mojang.datafixers.util.Pair;
import me.jddev0.ep.block.entity.handler.CachedSidedInventoryStorage;
import me.jddev0.ep.block.AutoCrafterBlock;
import me.jddev0.ep.block.entity.handler.InputOutputItemHandler;
import me.jddev0.ep.block.entity.handler.SidedInventoryWrapper;
import me.jddev0.ep.config.ModConfigs;
import me.jddev0.ep.energy.EnergyStoragePacketUpdate;
import me.jddev0.ep.machine.CheckboxUpdate;
import me.jddev0.ep.machine.configuration.ComparatorMode;
import me.jddev0.ep.machine.configuration.ComparatorModeUpdate;
import me.jddev0.ep.machine.configuration.RedstoneMode;
import me.jddev0.ep.machine.configuration.RedstoneModeUpdate;
import me.jddev0.ep.networking.ModMessages;
import me.jddev0.ep.networking.packet.EnergySyncS2CPacket;
import me.jddev0.ep.screen.AutoCrafterMenu;
import me.jddev0.ep.util.ByteUtils;
import me.jddev0.ep.util.EnergyUtils;
import me.jddev0.ep.util.ItemStackUtils;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SpecialCraftingRecipe;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import me.jddev0.ep.energy.EnergizedPowerEnergyStorage;
import me.jddev0.ep.energy.EnergizedPowerLimitingEnergyStorage;

import java.util.*;
import java.util.stream.IntStream;

public class AutoCrafterBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory<BlockPos>, EnergyStoragePacketUpdate, RedstoneModeUpdate,
        ComparatorModeUpdate, CheckboxUpdate {
    private static final List<@NotNull Identifier> RECIPE_BLACKLIST = ModConfigs.COMMON_AUTO_CRAFTER_RECIPE_BLACKLIST.getValue();

    public static final long CAPACITY = ModConfigs.COMMON_AUTO_CRAFTER_CAPACITY.getValue();
    public static final long MAX_RECEIVE = ModConfigs.COMMON_AUTO_CRAFTER_TRANSFER_RATE.getValue();
    public final static long ENERGY_CONSUMPTION_PER_TICK_PER_INGREDIENT = ModConfigs.COMMON_AUTO_CRAFTER_ENERGY_CONSUMPTION_PER_TICK_PER_INGREDIENT.getValue();

    final CachedSidedInventoryStorage<AutoCrafterBlockEntity> cachedSidedInventoryStorage;
    final InputOutputItemHandler inventory;
    private final SimpleInventory internalInventory;

    final EnergizedPowerLimitingEnergyStorage energyStorage;
    private final EnergizedPowerEnergyStorage internalEnergyStorage;

    private final SimpleInventory patternSlots = new SimpleInventory(3 * 3) {
        @Override
        public int getMaxCountPerStack() {
            return 1;
        }
    };
    private final SimpleInventory patternResultSlots = new SimpleInventory(1);
    private final InventoryChangedListener updatePatternListener = container -> updateRecipe();
    private boolean hasRecipeLoaded = false;
    private Identifier recipeIdForSetRecipe;
    private RecipeEntry<CraftingRecipe> craftingRecipe;
    private CraftingInventory oldCopyOfRecipe;
    private final ScreenHandler dummyContainerMenu = new ScreenHandler(null, -1) {
        @Override
        public ItemStack quickMove(PlayerEntity player, int index) {
            return null;
        }
        @Override
        public boolean canUse(PlayerEntity player) {
            return false;
        }
        @Override
        public void onContentChanged(Inventory container) {}
    };

    protected final PropertyDelegate data;
    private int progress;
    private int maxProgress = ModConfigs.COMMON_AUTO_CRAFTER_RECIPE_DURATION.getValue();
    private long energyConsumptionLeft = -1;
    private boolean hasEnoughEnergy;
    private boolean ignoreNBT;
    private boolean secondaryExtractMode;

    private @NotNull RedstoneMode redstoneMode = RedstoneMode.IGNORE;
    private @NotNull ComparatorMode comparatorMode = ComparatorMode.ITEM;

    public AutoCrafterBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(ModBlockEntities.AUTO_CRAFTER_ENTITY, blockPos, blockState);

        patternSlots.addListener(updatePatternListener);

        internalInventory = new SimpleInventory(18) {
            @Override
            public boolean isValid(int slot, ItemStack stack) {
                if(slot < 0 || slot >= 18)
                    return super.isValid(slot, stack);

                //Slot 0, 1, and 2 are for output items only
                return slot >= 3;
            }

            @Override
            public void markDirty() {
                super.markDirty();

                AutoCrafterBlockEntity.this.markDirty();
            }
        };
        inventory = new InputOutputItemHandler(new SidedInventoryWrapper(internalInventory) {
            @Override
            public int[] getAvailableSlots(Direction side) {
                return IntStream.range(0, 18).toArray();
            }

            @Override
            public boolean canInsert(int slot, ItemStack stack, @Nullable Direction dir) {
                return isValid(slot, stack);
            }

            @Override
            public boolean canExtract(int slot, ItemStack stack, Direction dir) {
                return true;
            }
        }, (i, stack) -> i >= 3, i -> secondaryExtractMode?!isInput(internalInventory.getStack(i)):
                isOutputOrCraftingRemainderOfInput(internalInventory.getStack(i)));
        cachedSidedInventoryStorage = new CachedSidedInventoryStorage<>(inventory);

        internalEnergyStorage = new EnergizedPowerEnergyStorage(CAPACITY, CAPACITY, CAPACITY) {
            @Override
            protected void onFinalCommit() {
                markDirty();

                if(world != null && !world.isClient()) {
                    ModMessages.sendServerPacketToPlayersWithinXBlocks(
                            getPos(), (ServerWorld)world, 32,
                            new EnergySyncS2CPacket(getAmount(), getCapacity(), getPos())
                    );
                }
            }
        };
        energyStorage = new EnergizedPowerLimitingEnergyStorage(internalEnergyStorage, MAX_RECEIVE, 0);

        data = new PropertyDelegate() {
            @Override
            public int get(int index) {
                return switch(index) {
                    case 0, 1 -> ByteUtils.get2Bytes(AutoCrafterBlockEntity.this.progress, index);
                    case 2, 3 -> ByteUtils.get2Bytes(AutoCrafterBlockEntity.this.maxProgress, index - 2);
                    case 4, 5, 6, 7 -> ByteUtils.get2Bytes(AutoCrafterBlockEntity.this.energyConsumptionLeft, index - 4);
                    case 8 -> hasEnoughEnergy?1:0;
                    case 9 -> ignoreNBT?1:0;
                    case 10 -> secondaryExtractMode?1:0;
                    case 11 -> redstoneMode.ordinal();
                    case 12 -> comparatorMode.ordinal();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch(index) {
                    case 0, 1 -> AutoCrafterBlockEntity.this.progress = ByteUtils.with2Bytes(
                            AutoCrafterBlockEntity.this.progress, (short)value, index
                    );
                    case 2, 3 -> AutoCrafterBlockEntity.this.maxProgress = ByteUtils.with2Bytes(
                            AutoCrafterBlockEntity.this.maxProgress, (short)value, index - 2
                    );
                    case 4, 5, 6, 7, 8 -> {}
                    case 9 -> AutoCrafterBlockEntity.this.ignoreNBT = value != 0;
                    case 10 -> AutoCrafterBlockEntity.this.secondaryExtractMode = value != 0;
                    case 11 -> AutoCrafterBlockEntity.this.redstoneMode = RedstoneMode.fromIndex(value);
                    case 12 -> AutoCrafterBlockEntity.this.comparatorMode = ComparatorMode.fromIndex(value);
                }
            }

            @Override
            public int size() {
                return 13;
            }
        };
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable("container.energizedpower.auto_crafter");
    }

    @Nullable
    @Override
    public ScreenHandler createMenu(int id, PlayerInventory inventory, PlayerEntity player) {
        ModMessages.sendServerPacketToPlayer((ServerPlayerEntity)player,
                new EnergySyncS2CPacket(internalEnergyStorage.getAmount(), internalEnergyStorage.getCapacity(), getPos()));

        return new AutoCrafterMenu(id, this, inventory, internalInventory, patternSlots, patternResultSlots, data);
    }

    @Override
    public BlockPos getScreenOpeningData(ServerPlayerEntity player) {
        return pos;
    }

    public int getRedstoneOutput() {
        return switch(comparatorMode) {
            case ITEM -> ScreenHandler.calculateComparatorOutput(internalInventory);
            case FLUID -> 0;
            case ENERGY -> EnergyUtils.getRedstoneSignalFromEnergyStorage(energyStorage);
        };
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        nbt.put("inventory", Inventories.writeNbt(new NbtCompound(), internalInventory.heldStacks, registries));
        nbt.put("pattern", Inventories.writeNbt(new NbtCompound(), patternSlots.heldStacks, registries));
        nbt.putLong("energy", internalEnergyStorage.getAmount());

        if(craftingRecipe != null)
            nbt.put("recipe.id", NbtString.of(craftingRecipe.id().toString()));

        nbt.put("recipe.progress", NbtInt.of(progress));
        nbt.put("recipe.energy_consumption_left", NbtLong.of(energyConsumptionLeft));

        nbt.putBoolean("ignore_nbt", ignoreNBT);
        nbt.putBoolean("secondary_extract_mode", secondaryExtractMode);

        nbt.putInt("configuration.redstone_mode", redstoneMode.ordinal());
        nbt.putInt("configuration.comparator_mode", comparatorMode.ordinal());

        super.writeNbt(nbt, registries);
    }

    @Override
    protected void readNbt(@NotNull NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.readNbt(nbt, registries);

        Inventories.readNbt(nbt.getCompound("inventory"), internalInventory.heldStacks, registries);
        Inventories.readNbt(nbt.getCompound("pattern"), patternSlots.heldStacks, registries);
        internalEnergyStorage.setAmountWithoutUpdate(nbt.getLong("energy"));

        if(nbt.contains("recipe.id")) {
            NbtElement tag = nbt.get("recipe.id");

            if(!(tag instanceof NbtString stringTag))
                throw new IllegalArgumentException("Tag must be of type StringTag!");

            recipeIdForSetRecipe = Identifier.tryParse(stringTag.asString());
        }

        progress = nbt.getInt("recipe.progress");
        energyConsumptionLeft = nbt.getLong("recipe.energy_consumption_left");

        ignoreNBT = nbt.getBoolean("ignore_nbt");
        secondaryExtractMode = nbt.getBoolean("secondary_extract_mode");

        redstoneMode = RedstoneMode.fromIndex(nbt.getInt("configuration.redstone_mode"));
        comparatorMode = ComparatorMode.fromIndex(nbt.getInt("configuration.comparator_mode"));
    }

    public void drops(World level, BlockPos worldPosition) {
        ItemScatterer.spawn(level, worldPosition, internalInventory.heldStacks);
    }

    public static void tick(World level, BlockPos blockPos, BlockState state, AutoCrafterBlockEntity blockEntity) {
        if(level.isClient())
            return;

        if(!blockEntity.hasRecipeLoaded) {
            blockEntity.updateRecipe();

            if(blockEntity.craftingRecipe == null)
                blockEntity.resetProgress();
        }

        if(!blockEntity.redstoneMode.isActive(state.get(AutoCrafterBlock.POWERED)))
            return;

        int itemCount = 0;
        for(int i = 0;i < blockEntity.patternSlots.size();i++)
            if(!blockEntity.patternSlots.getStack(i).isEmpty())
                itemCount++;

        //Ignore empty recipes
        if(itemCount == 0)
            return;

        if(blockEntity.craftingRecipe != null && (blockEntity.progress > 0 || (blockEntity.canInsertIntoOutputSlots() && blockEntity.canExtractItemsFromInput()))) {
            if(!blockEntity.canInsertIntoOutputSlots() || !blockEntity.canExtractItemsFromInput())
                return;

            long energyConsumptionPerTick = itemCount * ENERGY_CONSUMPTION_PER_TICK_PER_INGREDIENT;

            if(blockEntity.progress == 0) {
                if(!blockEntity.canExtractItemsFromInput())
                    return;

                blockEntity.energyConsumptionLeft = energyConsumptionPerTick * blockEntity.maxProgress;
            }

            if(blockEntity.progress < 0 || blockEntity.maxProgress < 0 || blockEntity.energyConsumptionLeft < 0 ||
                    energyConsumptionPerTick < 0) {
                //Reset progress for invalid values

                blockEntity.resetProgress();
                markDirty(level, blockPos, state);

                return;
            }

            if(energyConsumptionPerTick <= blockEntity.internalEnergyStorage.getAmount()) {
                try(Transaction transaction = Transaction.openOuter()) {
                    blockEntity.internalEnergyStorage.extract(energyConsumptionPerTick, transaction);
                    transaction.commit();
                }

                blockEntity.energyConsumptionLeft -= energyConsumptionPerTick;

                blockEntity.progress++;

                if(blockEntity.progress >= blockEntity.maxProgress) {
                    SimpleInventory patternSlotsForRecipe = blockEntity.ignoreNBT?
                            blockEntity.replaceCraftingPatternWithCurrentNBTItems(blockEntity.patternSlots):blockEntity.patternSlots;
                    CraftingInventory copyOfPatternSlots = new CraftingInventory(blockEntity.dummyContainerMenu, 3, 3);
                    for(int i = 0;i < patternSlotsForRecipe.size();i++)
                        copyOfPatternSlots.setStack(i, patternSlotsForRecipe.getStack(i));

                    blockEntity.extractItems();
                    blockEntity.craftItem(copyOfPatternSlots);
                }

                markDirty(level, blockPos, state);
            }else {
                blockEntity.hasEnoughEnergy = false;
                markDirty(level, blockPos, state);
            }
        }else {
            blockEntity.resetProgress();
            markDirty(level, blockPos, state);
        }
    }

    private void resetProgress() {
        progress = 0;
        energyConsumptionLeft = -1;
        hasEnoughEnergy = true;
    }

    public void resetProgressAndMarkAsChanged() {
        resetProgress();
        markDirty(world, getPos(), getCachedState());
    }

    public void cycleRecipe() {
        SimpleInventory patternSlotsForRecipe = ignoreNBT?replaceCraftingPatternWithCurrentNBTItems(patternSlots):patternSlots;
        CraftingInventory copyOfPatternSlots = new CraftingInventory(dummyContainerMenu, 3, 3);
        for(int i = 0;i < patternSlotsForRecipe.size();i++)
            copyOfPatternSlots.setStack(i, patternSlotsForRecipe.getStack(i));

        List<RecipeEntry<CraftingRecipe>> recipes = getRecipesFor(copyOfPatternSlots, world);

        //No recipe found
        if(recipes.isEmpty()) {
            updateRecipe();

            return;
        }

        if(recipeIdForSetRecipe == null)
            recipeIdForSetRecipe = (craftingRecipe == null || craftingRecipe.id() == null)?recipes.get(0).id():
                    craftingRecipe.id();

        for(int i = 0;i < recipes.size();i++) {
            if(Objects.equals(recipes.get(i).id(), recipeIdForSetRecipe)) {
                recipeIdForSetRecipe = recipes.get((i + 1) % recipes.size()).id();

                break;
            }
        }

        updateRecipe();
    }

    public void setRecipeIdForSetRecipe(Identifier recipeIdForSetRecipe) {
        this.recipeIdForSetRecipe = recipeIdForSetRecipe;

        updateRecipe();
    }

    private void updateRecipe() {
        if(world == null)
            return;

        RecipeEntry<CraftingRecipe> oldRecipe = null;
        ItemStack oldResult = null;
        if(hasRecipeLoaded && craftingRecipe != null && oldCopyOfRecipe != null) {
            oldRecipe = craftingRecipe;

            oldResult = craftingRecipe.value() instanceof SpecialCraftingRecipe?craftingRecipe.value().craft(oldCopyOfRecipe, world.getRegistryManager()):
                    craftingRecipe.value().getResult(world.getRegistryManager());
        }

        hasRecipeLoaded = true;

        SimpleInventory patternSlotsForRecipe = ignoreNBT?replaceCraftingPatternWithCurrentNBTItems(patternSlots):patternSlots;
        CraftingInventory copyOfPatternSlots = new CraftingInventory(dummyContainerMenu, 3, 3);
        for(int i = 0;i < patternSlotsForRecipe.size();i++)
            copyOfPatternSlots.setStack(i, patternSlotsForRecipe.getStack(i));

        Optional<Pair<Identifier, RecipeEntry<CraftingRecipe>>> recipe = getRecipeFor(copyOfPatternSlots, world, recipeIdForSetRecipe);
        if(recipe.isPresent()) {
            craftingRecipe = recipe.get().getSecond();

            //Recipe with saved recipe id does not exist or pattern items are not compatible with recipe
            if(recipeIdForSetRecipe != null && !Objects.equals(craftingRecipe.id(), recipeIdForSetRecipe)) {
                recipeIdForSetRecipe = craftingRecipe.id();
                resetProgress();
            }

            ItemStack resultItemStack = craftingRecipe.value() instanceof SpecialCraftingRecipe?craftingRecipe.value().craft(copyOfPatternSlots, world.getRegistryManager()):
                    craftingRecipe.value().getResult(world.getRegistryManager());

            patternResultSlots.setStack(0, resultItemStack);

            if(oldRecipe != null && oldResult != null && oldCopyOfRecipe != null && (craftingRecipe != oldRecipe || ItemStack.areItemsAndComponentsEqual(resultItemStack, oldResult)))
                resetProgress();

            oldCopyOfRecipe = new CraftingInventory(dummyContainerMenu, 3, 3);
            for(int i = 0;i < patternSlotsForRecipe.size();i++)
                oldCopyOfRecipe.setStack(i, copyOfPatternSlots.getStack(i).copy());
        }else {
            recipeIdForSetRecipe = null;

            craftingRecipe = null;

            patternResultSlots.setStack(0, ItemStack.EMPTY);

            oldCopyOfRecipe = null;

            resetProgress();
        }
    }

    private void extractItems() {
        SimpleInventory patternSlotsForRecipe = ignoreNBT?replaceCraftingPatternWithCurrentNBTItems(patternSlots):patternSlots;
        List<ItemStack> patternItemStacks = new ArrayList<>(9);
        for(int i = 0;i < patternSlotsForRecipe.size();i++)
            if(!patternSlotsForRecipe.getStack(i).isEmpty())
                patternItemStacks.add(patternSlotsForRecipe.getStack(i));

        List<ItemStack> itemStacksExtract = ItemStackUtils.combineItemStacks(patternItemStacks);

        for(ItemStack itemStack:itemStacksExtract) {
            for(int i = 0;i < internalInventory.size();i++) {
                ItemStack testItemStack = internalInventory.getStack(i);
                if(ItemStack.areItemsAndComponentsEqual(itemStack, testItemStack)) {
                    ItemStack ret = internalInventory.removeStack(i, itemStack.getCount());
                    if(!ret.isEmpty()) {
                        int amount = ret.getCount();
                        if(amount == itemStack.getCount())
                            break;

                        itemStack.decrement(amount);
                    }
                }
            }
        }
    }

    private void craftItem(CraftingInventory copyOfPatternSlots) {
        if(craftingRecipe == null) {
            resetProgress();

            return;
        }

        List<ItemStack> outputItemStacks = new ArrayList<>(10);

        ItemStack resultItemStack = craftingRecipe.value() instanceof SpecialCraftingRecipe?craftingRecipe.value().craft(copyOfPatternSlots, world.getRegistryManager()):
                craftingRecipe.value().getResult(world.getRegistryManager());

        outputItemStacks.add(resultItemStack);

        for(ItemStack remainingItem:craftingRecipe.value().getRemainder(copyOfPatternSlots))
            if(!remainingItem.isEmpty())
                outputItemStacks.add(remainingItem);

        List<ItemStack> itemStacksInsert = ItemStackUtils.combineItemStacks(outputItemStacks);

        List<Integer> emptyIndices = new ArrayList<>(18);
        outer:
        for(ItemStack itemStack:itemStacksInsert) {
            for(int i = 0;i < internalInventory.size();i++) {
                ItemStack testItemStack = internalInventory.getStack(i);
                if(emptyIndices.contains(i))
                    continue;

                if(testItemStack.isEmpty()) {
                    emptyIndices.add(i);

                    continue;
                }

                if(ItemStack.areItemsAndComponentsEqual(itemStack, testItemStack)) {
                    int amount = Math.min(itemStack.getCount(), testItemStack.getMaxCount() - testItemStack.getCount());
                    if(amount > 0) {
                        internalInventory.setStack(i, internalInventory.getStack(i).copyWithCount(testItemStack.getCount() + amount));

                        itemStack.setCount(itemStack.getCount() - amount);

                        if(itemStack.isEmpty())
                            continue outer;
                    }
                }
            }

            //Leftover -> put in empty slot
            if(emptyIndices.isEmpty())
                continue; //Should not happen

            internalInventory.setStack(emptyIndices.remove(0), itemStack);
        }

        if(ignoreNBT)
            updateRecipe();

        resetProgress();
    }

    private boolean canExtractItemsFromInput() {
        if(craftingRecipe == null)
            return false;

        SimpleInventory patternSlotsForRecipe = ignoreNBT?replaceCraftingPatternWithCurrentNBTItems(patternSlots):patternSlots;
        List<ItemStack> patternItemStacks = new ArrayList<>(9);
        for(int i = 0;i < patternSlotsForRecipe.size();i++)
            if(!patternSlotsForRecipe.getStack(i).isEmpty())
                patternItemStacks.add(patternSlotsForRecipe.getStack(i));

        List<ItemStack> itemStacks = ItemStackUtils.combineItemStacks(patternItemStacks);

        List<Integer> checkedIndices = new ArrayList<>(18);
        outer:
        for(int i = itemStacks.size() - 1;i >= 0;i--) {
            ItemStack itemStack = itemStacks.get(i);

            for(int j = 0;j < internalInventory.size();j++) {
                if(checkedIndices.contains(j))
                    continue;

                ItemStack testItemStack = internalInventory.getStack(j);
                if(testItemStack.isEmpty()) {
                    checkedIndices.add(j);

                    continue;
                }

                if(ItemStack.areItemsAndComponentsEqual(itemStack, testItemStack)) {
                    int amount = Math.min(itemStack.getCount(), testItemStack.getCount());
                    checkedIndices.add(j);

                    if(amount == itemStack.getCount()) {
                        itemStacks.remove(i);

                        continue outer;
                    }else {
                        itemStack.decrement(amount);
                    }
                }
            }

            return false;
        }

        return itemStacks.isEmpty();
    }

    private boolean canInsertIntoOutputSlots() {
        if(craftingRecipe == null)
            return false;

        SimpleInventory patternSlotsForRecipe = ignoreNBT?replaceCraftingPatternWithCurrentNBTItems(patternSlots):patternSlots;
        CraftingInventory copyOfPatternSlots = new CraftingInventory(dummyContainerMenu, 3, 3);
        for(int i = 0;i < patternSlotsForRecipe.size();i++)
            copyOfPatternSlots.setStack(i, patternSlotsForRecipe.getStack(i));


        List<ItemStack> outputItemStacks = new ArrayList<>(10);
        ItemStack resultItemStack = craftingRecipe.value() instanceof SpecialCraftingRecipe?craftingRecipe.value().craft(copyOfPatternSlots, world.getRegistryManager()):
                craftingRecipe.value().getResult(world.getRegistryManager());

        if(!resultItemStack.isEmpty())
            outputItemStacks.add(resultItemStack);

        for(ItemStack remainingItem:craftingRecipe.value().getRemainder(copyOfPatternSlots))
            if(!remainingItem.isEmpty())
                outputItemStacks.add(remainingItem);

        List<ItemStack> itemStacks = ItemStackUtils.combineItemStacks(outputItemStacks);

        List<Integer> checkedIndices = new ArrayList<>(18);
        List<Integer> emptyIndices = new ArrayList<>(18);
        outer:
        for(int i = itemStacks.size() - 1;i >= 0;i--) {
            ItemStack itemStack = itemStacks.get(i);
            for(int j = 0;j < internalInventory.size();j++) {
                if(checkedIndices.contains(j) || emptyIndices.contains(j))
                    continue;

                ItemStack testItemStack = internalInventory.getStack(j);
                if(testItemStack.isEmpty()) {
                    emptyIndices.add(j);

                    continue;
                }

                if(ItemStack.areItemsAndComponentsEqual(itemStack, testItemStack)) {
                    int amount = Math.min(itemStack.getCount(), testItemStack.getMaxCount() - testItemStack.getCount());

                    if(amount + testItemStack.getCount() == testItemStack.getMaxCount())
                        checkedIndices.add(j);

                    if(amount == itemStack.getCount()) {
                        itemStacks.remove(i);

                        continue outer;
                    }else {
                        itemStack.decrement(amount);
                    }
                }
            }

            //Leftover -> put in empty slot
            if(emptyIndices.isEmpty())
                return false;

            int index = emptyIndices.remove(0);
            if(itemStack.getCount() == itemStack.getMaxCount())
                checkedIndices.add(index);

            itemStacks.remove(i);
        }

        return itemStacks.isEmpty();
    }

    private boolean isOutputOrCraftingRemainderOfInput(ItemStack itemStack) {
        if(craftingRecipe == null)
            return false;

        SimpleInventory patternSlotsForRecipe = ignoreNBT?replaceCraftingPatternWithCurrentNBTItems(patternSlots):patternSlots;
        CraftingInventory copyOfPatternSlots = new CraftingInventory(dummyContainerMenu, 3, 3);
        for(int i = 0;i < patternSlotsForRecipe.size();i++)
            copyOfPatternSlots.setStack(i, patternSlotsForRecipe.getStack(i));

        ItemStack resultItemStack = craftingRecipe.value() instanceof SpecialCraftingRecipe?craftingRecipe.value().craft(copyOfPatternSlots, world.getRegistryManager()):
                craftingRecipe.value().getResult(world.getRegistryManager());

        if(ItemStack.areItemsEqual(itemStack, resultItemStack) && ItemStack.areItemsAndComponentsEqual(itemStack, resultItemStack))
            return true;

        for(ItemStack remainingItem:craftingRecipe.value().getRemainder(copyOfPatternSlots))
            if(ItemStack.areItemsEqual(itemStack, remainingItem) && ItemStack.areItemsAndComponentsEqual(itemStack, remainingItem))
                return true;

        return false;
    }

    private boolean isInput(ItemStack itemStack) {
        if(craftingRecipe == null)
            return false;

        for(int i = 0;i < patternSlots.size();i++)
            if(ignoreNBT?ItemStack.areItemsEqual(itemStack, patternSlots.getStack(i)):
                    (ItemStack.areItemsEqual(itemStack, patternSlots.getStack(i)) &&
                            ItemStack.areItemsAndComponentsEqual(itemStack, patternSlots.getStack(i))))
                return true;

        return false;
    }

    private SimpleInventory replaceCraftingPatternWithCurrentNBTItems(SimpleInventory container) {
        SimpleInventory copyOfContainer = new SimpleInventory(container.size());
        for(int i = 0;i < container.size();i++)
            copyOfContainer.setStack(i, container.getStack(i).copy());

        Map<Integer, Integer> usedItemCounts = new HashMap<>(); //slotIndex: usedCount
        outer:
        for(int i = 0;i < copyOfContainer.size();i++) {
            ItemStack itemStack = copyOfContainer.getStack(i);
            if(itemStack.isEmpty())
                continue;

            for(int j = 0;j < internalInventory.size();j++) {
                ItemStack testItemStack = internalInventory.getStack(j).copy();
                int usedCount = usedItemCounts.getOrDefault(j, 0);
                testItemStack.setCount(testItemStack.getCount() - usedCount);
                if(testItemStack.getCount() <= 0)
                    continue;

                if(ItemStack.areItemsAndComponentsEqual(itemStack, testItemStack)) {
                    usedItemCounts.put(j, usedCount + 1);
                    continue outer;
                }
            }

            //Same item with same tag was not found -> check for same item without same tag and change if found
            for(int j = 0;j < internalInventory.size();j++) {
                ItemStack testItemStack = internalInventory.getStack(j).copy();
                int usedCount = usedItemCounts.getOrDefault(j, 0);
                testItemStack.setCount(testItemStack.getCount() - usedCount);
                if(testItemStack.getCount() <= 0)
                    continue;

                if(ItemStack.areItemsEqual(itemStack, testItemStack)) {
                    usedItemCounts.put(j, usedCount + 1);

                    copyOfContainer.setStack(i, testItemStack.copyWithCount(1));

                    continue outer;
                }
            }

            //Not found at all -> Mot enough input items are present
            return copyOfContainer;
        }

        return copyOfContainer;
    }

    private List<RecipeEntry<CraftingRecipe>> getRecipesFor(CraftingInventory patternSlots, World level) {
        return level.getRecipeManager().listAllOfType(RecipeType.CRAFTING).
                stream().filter(recipe -> !RECIPE_BLACKLIST.contains(recipe.id())).
                filter(recipe -> recipe.value().matches(patternSlots, level)).
                sorted(Comparator.comparing(recipe -> recipe.value().getResult(level.getRegistryManager()).getTranslationKey())).
                toList();
    }

    private Optional<Pair<Identifier, RecipeEntry<CraftingRecipe>>> getRecipeFor(CraftingInventory patternSlots, World level, Identifier recipeId) {
        List<RecipeEntry<CraftingRecipe>> recipes = getRecipesFor(patternSlots, level);
        Optional<RecipeEntry<CraftingRecipe>> recipe = recipes.stream().filter(r -> r.id().equals(recipeId)).findFirst();

        return recipe.or(() -> recipes.stream().findFirst()).map(r -> Pair.of(r.id(), r));
    }

    public void setIgnoreNBT(boolean ignoreNBT) {
        this.ignoreNBT = ignoreNBT;
        updateRecipe();
        markDirty(world, getPos(), getCachedState());
    }

    public void setSecondaryExtractMode(boolean secondaryExtractMode) {
        this.secondaryExtractMode = secondaryExtractMode;
        markDirty(world, getPos(), getCachedState());
    }

    @Override
    public void setCheckbox(int checkboxId, boolean checked) {
        switch(checkboxId) {
            //Ignore NBT
            case 0 -> setIgnoreNBT(checked);

            //Secondary extract mode
            case 1 -> setSecondaryExtractMode(checked);
        }
    }

    public long getEnergy() {
        return internalEnergyStorage.getAmount();
    }

    public long getCapacity() {
        return internalEnergyStorage.getCapacity();
    }

    @Override
    public void setEnergy(long energy) {
        internalEnergyStorage.setAmountWithoutUpdate(energy);
    }

    @Override
    public void setCapacity(long capacity) {
        internalEnergyStorage.setCapacityWithoutUpdate(capacity);
    }

    @Override
    public void setNextRedstoneMode() {
        redstoneMode = RedstoneMode.fromIndex(redstoneMode.ordinal() + 1);
        markDirty();
    }

    @Override
    public void setNextComparatorMode() {
        do {
            comparatorMode = ComparatorMode.fromIndex(comparatorMode.ordinal() + 1);
        }while(comparatorMode == ComparatorMode.FLUID); //Prevent the FLUID comparator mode from being selected
        markDirty();
    }
}