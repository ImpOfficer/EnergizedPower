package me.jddev0.ep.block.entity;

import me.jddev0.ep.block.BlockPlacerBlock;
import me.jddev0.ep.block.entity.handler.CachedSidedInventoryStorage;
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
import me.jddev0.ep.screen.BlockPlacerMenu;
import me.jddev0.ep.util.ByteUtils;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.AutomaticItemPlacementContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtLong;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;
import me.jddev0.ep.util.EnergyUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import me.jddev0.ep.energy.EnergizedPowerEnergyStorage;
import me.jddev0.ep.energy.EnergizedPowerLimitingEnergyStorage;

import java.util.stream.IntStream;
import java.util.List;

public class BlockPlacerBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory, EnergyStoragePacketUpdate, RedstoneModeUpdate,
        ComparatorModeUpdate, CheckboxUpdate {
    private static final List<@NotNull Identifier> PLACEMENT_BLACKLIST = ModConfigs.COMMON_BLOCK_PLACER_PLACEMENT_BLACKLIST.getValue();

    public static final long CAPACITY = ModConfigs.COMMON_BLOCK_PLACER_CAPACITY.getValue();
    public static final long MAX_RECEIVE = ModConfigs.COMMON_BLOCK_PLACER_TRANSFER_RATE.getValue();
    private static final long ENERGY_USAGE_PER_TICK = ModConfigs.COMMON_BLOCK_PLACER_ENERGY_CONSUMPTION_PER_TICK.getValue();

    final CachedSidedInventoryStorage<BlockPlacerBlockEntity> cachedSidedInventoryStorage;
    final InputOutputItemHandler inventory;
    private final SimpleInventory internalInventory;

    final EnergizedPowerLimitingEnergyStorage energyStorage;
    private final EnergizedPowerEnergyStorage internalEnergyStorage;

    protected final PropertyDelegate data;
    private int progress;
    private int maxProgress = ModConfigs.COMMON_BLOCK_PLACER_PLACEMENT_DURATION.getValue();
    private long energyConsumptionLeft = -1;
    private boolean hasEnoughEnergy;
    private boolean inverseRotation;

    private @NotNull RedstoneMode redstoneMode = RedstoneMode.IGNORE;
    private @NotNull ComparatorMode comparatorMode = ComparatorMode.ITEM;

    public BlockPlacerBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(ModBlockEntities.BLOCK_PLACER_ENTITY, blockPos, blockState);

        internalInventory = new SimpleInventory(1) {
            @Override
            public int getMaxCountPerStack() {
                return 1;
            }

            @Override
            public boolean isValid(int slot, ItemStack stack) {
                if(slot == 0) {
                    return stack.getItem() instanceof BlockItem;
                }

                return super.isValid(slot, stack);
            }

            @Override
            public void setStack(int slot, ItemStack stack) {
                if(slot == 0) {
                    ItemStack itemStack = getStack(slot);
                    if(world != null && !stack.isEmpty() && !itemStack.isEmpty() && (!ItemStack.areItemsEqual(stack, itemStack) ||
                            !ItemStack.areNbtEqual(stack, itemStack)))
                        resetProgress(pos, world.getBlockState(pos));
                }

                super.setStack(slot, stack);
            }

            @Override
            public void markDirty() {
                super.markDirty();

                BlockPlacerBlockEntity.this.markDirty();
            }
        };
        inventory = new InputOutputItemHandler(new SidedInventoryWrapper(internalInventory) {
            @Override
            public int[] getAvailableSlots(Direction side) {
                return IntStream.range(0, 1).toArray();
            }

            @Override
            public boolean canInsert(int slot, ItemStack stack, @Nullable Direction dir) {
                return isValid(slot, stack);
            }

            @Override
            public boolean canExtract(int slot, ItemStack stack, Direction dir) {
                return true;
            }
        }, (i, stack) -> true, i -> false);
        cachedSidedInventoryStorage = new CachedSidedInventoryStorage<>(inventory);

        internalEnergyStorage = new EnergizedPowerEnergyStorage(CAPACITY, CAPACITY, CAPACITY) {
            @Override
            protected void onFinalCommit() {
                markDirty();

                if(world != null && !world.isClient()) {
                    PacketByteBuf buffer = PacketByteBufs.create();
                    buffer.writeLong(getAmount());
                    buffer.writeLong(getCapacity());
                    buffer.writeBlockPos(getPos());

                    ModMessages.sendServerPacketToPlayersWithinXBlocks(
                            getPos(), (ServerWorld)world, 32,
                            ModMessages.ENERGY_SYNC_ID, buffer
                    );
                }
            }
        };
        energyStorage = new EnergizedPowerLimitingEnergyStorage(internalEnergyStorage, MAX_RECEIVE, 0);

        data = new PropertyDelegate() {
            @Override
            public int get(int index) {
                return switch(index) {
                    case 0, 1 -> ByteUtils.get2Bytes(BlockPlacerBlockEntity.this.progress, index);
                    case 2, 3 -> ByteUtils.get2Bytes(BlockPlacerBlockEntity.this.maxProgress, index - 2);
                    case 4, 5, 6, 7 -> ByteUtils.get2Bytes(BlockPlacerBlockEntity.this.energyConsumptionLeft, index - 4);
                    case 8 -> hasEnoughEnergy?1:0;
                    case 9 -> inverseRotation?1:0;
                    case 10 -> redstoneMode.ordinal();
                    case 11 -> comparatorMode.ordinal();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch(index) {
                    case 0, 1 -> BlockPlacerBlockEntity.this.progress = ByteUtils.with2Bytes(
                            BlockPlacerBlockEntity.this.progress, (short)value, index
                    );
                    case 2, 3 -> BlockPlacerBlockEntity.this.maxProgress = ByteUtils.with2Bytes(
                            BlockPlacerBlockEntity.this.maxProgress, (short)value, index - 2
                    );
                    case 4, 5, 6, 7, 8 -> {}
                    case 9 -> BlockPlacerBlockEntity.this.inverseRotation = value != 0;
                    case 10 -> BlockPlacerBlockEntity.this.redstoneMode = RedstoneMode.fromIndex(value);
                    case 11 -> BlockPlacerBlockEntity.this.comparatorMode = ComparatorMode.fromIndex(value);
                }
            }

            @Override
            public int size() {
                return 12;
            }
        };
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable("container.energizedpower.block_placer");
    }

    public int getRedstoneOutput() {
        return switch(comparatorMode) {
            case ITEM -> ScreenHandler.calculateComparatorOutput(internalInventory);
            case FLUID -> 0;
            case ENERGY -> EnergyUtils.getRedstoneSignalFromEnergyStorage(energyStorage);
        };
    }

    @Nullable
    @Override
    public ScreenHandler createMenu(int id, PlayerInventory inventory, PlayerEntity player) {
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeLong(internalEnergyStorage.getAmount());
        buffer.writeLong(internalEnergyStorage.getCapacity());
        buffer.writeBlockPos(getPos());

        ModMessages.sendServerPacketToPlayer((ServerPlayerEntity)player, ModMessages.ENERGY_SYNC_ID, buffer);

        return new BlockPlacerMenu(id, this, inventory, internalInventory, this.data);
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
        buf.writeBlockPos(pos);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        nbt.put("inventory", Inventories.writeNbt(new NbtCompound(), internalInventory.stacks));
        nbt.putLong("energy", internalEnergyStorage.getAmount());

        nbt.put("recipe.progress", NbtInt.of(progress));
        nbt.put("recipe.energy_consumption_left", NbtLong.of(energyConsumptionLeft));

        nbt.putBoolean("inverse_rotation", inverseRotation);

        nbt.putInt("configuration.redstone_mode", redstoneMode.ordinal());
        nbt.putInt("configuration.comparator_mode", comparatorMode.ordinal());

        super.writeNbt(nbt);
    }

    @Override
    public void readNbt(@NotNull NbtCompound nbt) {
        super.readNbt(nbt);

        Inventories.readNbt(nbt.getCompound("inventory"), internalInventory.stacks);
        internalEnergyStorage.setAmountWithoutUpdate(nbt.getLong("energy"));

        progress = nbt.getInt("recipe.progress");
        energyConsumptionLeft = nbt.getLong("recipe.energy_consumption_left");

        inverseRotation = nbt.getBoolean("inverse_rotation");

        redstoneMode = RedstoneMode.fromIndex(nbt.getInt("configuration.redstone_mode"));
        comparatorMode = ComparatorMode.fromIndex(nbt.getInt("configuration.comparator_mode"));
    }

    public void drops(World level, BlockPos worldPosition) {
        ItemScatterer.spawn(level, worldPosition, internalInventory.stacks);
    }

    public static void tick(World level, BlockPos blockPos, BlockState state, BlockPlacerBlockEntity blockEntity) {
        if(level.isClient())
            return;

        if(!blockEntity.redstoneMode.isActive(state.get(BlockPlacerBlock.POWERED)))
            return;

        if(hasRecipe(blockEntity)) {
            if(blockEntity.energyConsumptionLeft < 0)
                blockEntity.energyConsumptionLeft = ENERGY_USAGE_PER_TICK * blockEntity.maxProgress;

            if(ENERGY_USAGE_PER_TICK <= blockEntity.internalEnergyStorage.getAmount()) {
                blockEntity.hasEnoughEnergy = true;

                if(blockEntity.progress < 0 || blockEntity.maxProgress < 0 || blockEntity.energyConsumptionLeft < 0) {
                    //Reset progress for invalid values

                    blockEntity.resetProgress(blockPos, state);
                    markDirty(level, blockPos, state);

                    return;
                }

                try(Transaction transaction = Transaction.openOuter()) {
                    blockEntity.internalEnergyStorage.extract(ENERGY_USAGE_PER_TICK, transaction);
                    transaction.commit();
                }

                blockEntity.energyConsumptionLeft -= ENERGY_USAGE_PER_TICK;

                if(blockEntity.progress < blockEntity.maxProgress)
                    blockEntity.progress++;

                if(blockEntity.progress >= blockEntity.maxProgress) {
                    ItemStack itemStack = blockEntity.internalInventory.getStack(0);
                    if(itemStack.isEmpty()) {
                        blockEntity.energyConsumptionLeft = ENERGY_USAGE_PER_TICK;
                        markDirty(level, blockPos, state);

                        return;
                    }

                    BlockPos blockPosPlacement = blockEntity.getPos().offset(blockEntity.getCachedState().get(BlockPlacerBlock.FACING));

                    BlockItem blockItem = (BlockItem)itemStack.getItem();
                    final Direction direction;

                    if(blockEntity.inverseRotation) {
                        direction = switch(state.get(BlockPlacerBlock.FACING)) {
                            case DOWN -> Direction.UP;
                            case UP -> Direction.DOWN;
                            case NORTH -> Direction.SOUTH;
                            case SOUTH -> Direction.NORTH;
                            case WEST -> Direction.EAST;
                            case EAST -> Direction.WEST;
                        };
                    }else {
                        direction = state.get(BlockPlacerBlock.FACING);
                    }

                    ActionResult result = blockItem.place(new AutomaticItemPlacementContext(level, blockPosPlacement, direction, itemStack, direction) {
                        @Override
                        public @NotNull Direction getPlayerLookDirection() {
                            return direction;
                        }

                        @Override
                        public @NotNull Direction @NotNull [] getPlacementDirections() {
                            return switch (direction) {
                                case DOWN ->
                                        new Direction[] { Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP };
                                case UP ->
                                        new Direction[] { Direction.UP, Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
                                case NORTH ->
                                        new Direction[] { Direction.NORTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN, Direction.SOUTH };
                                case SOUTH ->
                                        new Direction[] { Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN, Direction.NORTH };
                                case WEST ->
                                        new Direction[] { Direction.WEST, Direction.SOUTH, Direction.UP, Direction.DOWN, Direction.NORTH, Direction.EAST };
                                case EAST ->
                                        new Direction[] { Direction.EAST, Direction.SOUTH, Direction.UP, Direction.DOWN, Direction.NORTH, Direction.WEST };
                            };
                        }

                        @Override
                        public boolean canReplaceExisting() {
                            return false;
                        }
                    });

                    if(result == ActionResult.FAIL) {
                        blockEntity.energyConsumptionLeft = ENERGY_USAGE_PER_TICK;
                        markDirty(level, blockPos, state);

                        return;
                    }

                    blockEntity.internalInventory.setStack(0, itemStack);
                    blockEntity.resetProgress(blockPos, state);
                }

                markDirty(level, blockPos, state);
            }else {
                blockEntity.hasEnoughEnergy = false;
                markDirty(level, blockPos, state);
            }
        }else {
            blockEntity.resetProgress(blockPos, state);
            markDirty(level, blockPos, state);
        }
    }

    private void resetProgress(BlockPos blockPos, BlockState state) {
        progress = 0;
        energyConsumptionLeft = -1;
        hasEnoughEnergy = true;
    }

    private static boolean hasRecipe(BlockPlacerBlockEntity blockEntity) {
        ItemStack itemStack = blockEntity.internalInventory.getStack(0);
        if(itemStack.isEmpty())
            return false;

        if(!(itemStack.getItem() instanceof BlockItem blockItemStack))
            return false;

        return !PLACEMENT_BLACKLIST.contains(Registry.BLOCK.getId(blockItemStack.getBlock()));
    }

    public void setInverseRotation(boolean inverseRotation) {
        this.inverseRotation = inverseRotation;
        markDirty(world, getPos(), getCachedState());
    }

    @Override
    public void setCheckbox(int checkboxId, boolean checked) {
        switch(checkboxId) {
            //Inverse rotation
            case 0 -> setInverseRotation(checked);
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