package me.jddev0.ep.block.entity;

import me.jddev0.ep.block.FluidTankBlock;
import me.jddev0.ep.fluid.FluidStack;
import me.jddev0.ep.fluid.FluidStoragePacketUpdate;
import me.jddev0.ep.fluid.SimpleFluidStorage;
import me.jddev0.ep.machine.CheckboxUpdate;
import me.jddev0.ep.networking.ModMessages;
import me.jddev0.ep.screen.FluidTankMenu;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import me.jddev0.ep.util.FluidUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class FluidTankBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory, FluidStoragePacketUpdate,
        CheckboxUpdate {
    private final FluidTankBlock.Tier tier;
    final SimpleFluidStorage fluidStorage;

    protected final PropertyDelegate data;

    private boolean ignoreNBT;
    private FluidStack fluidFilter = new FluidStack(FluidVariant.blank(), 1);

    public static BlockEntityType<FluidTankBlockEntity> getEntityTypeFromTier(FluidTankBlock.Tier tier) {
        return switch(tier) {
            case SMALL -> ModBlockEntities.FLUID_TANK_SMALL_ENTITY;
            case MEDIUM -> ModBlockEntities.FLUID_TANK_MEDIUM_ENTITY;
            case LARGE -> ModBlockEntities.FLUID_TANK_LARGE_ENTITY;
        };
    }

    public FluidTankBlockEntity(BlockPos blockPos, BlockState blockState, FluidTankBlock.Tier tier) {
        super(getEntityTypeFromTier(tier), blockPos, blockState);

        this.tier = tier;

        fluidStorage = new SimpleFluidStorage(tier.getTankCapacity()) {
            @Override
            protected void onFinalCommit() {
                markDirty();

                if(world != null && !world.isClient()) {
                    PacketByteBuf buffer = PacketByteBufs.create();
                    buffer.writeInt(0);
                    getFluid().toPacket(buffer);
                    buffer.writeLong(capacity);
                    buffer.writeBlockPos(getPos());

                    ModMessages.sendServerPacketToPlayersWithinXBlocks(
                            getPos(), (ServerWorld)world, 32,
                            ModMessages.FLUID_SYNC_ID, buffer
                    );
                }
            }

            private boolean isFluidValid(FluidVariant variant) {
                return fluidFilter.isEmpty() || (ignoreNBT?(
                        fluidFilter.getFluidVariant().isOf(variant.getFluid()) &&
                                fluidFilter.getFluidVariant().nbtMatches(variant.getNbt())):
                        fluidFilter.getFluidVariant().isOf(variant.getFluid()));
            }

            @Override
            protected boolean canInsert(FluidVariant variant) {
                return isFluidValid(variant);
            }

            @Override
            protected boolean canExtract(FluidVariant variant) {
                return isFluidValid(variant);
            }
        };

        data = new PropertyDelegate() {
            @Override
            public int get(int index) {
                return switch(index) {
                    case 0 -> ignoreNBT?1:0;
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch(index) {
                    case 0 -> FluidTankBlockEntity.this.ignoreNBT = value != 0;
                }
            }

            @Override
            public int size() {
                return 1;
            }
        };
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable("container.energizedpower." + tier.getResourceId());
    }

    @Nullable
    @Override
    public ScreenHandler createMenu(int id, PlayerInventory inventory, PlayerEntity player) {
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeInt(0);
        fluidStorage.getFluid().toPacket(buffer);
        buffer.writeLong(fluidStorage.getCapacity());
        buffer.writeBlockPos(getPos());

        ModMessages.sendServerPacketToPlayer((ServerPlayerEntity)player, ModMessages.FLUID_SYNC_ID, buffer);

        buffer = PacketByteBufs.create();
        buffer.writeInt(1);
        fluidFilter.toPacket(buffer);
        buffer.writeLong(0);
        buffer.writeBlockPos(getPos());

        ModMessages.sendServerPacketToPlayer((ServerPlayerEntity)player, ModMessages.FLUID_SYNC_ID, buffer);

        return new FluidTankMenu(id, inventory, this, this.data);
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
        buf.writeBlockPos(pos);
    }

    public FluidTankBlock.Tier getTier() {
        return tier;
    }

    public static void tick(World level, BlockPos blockPos, BlockState state, FluidTankBlockEntity blockEntity) {
        if(level.isClient())
            return;

        //Sync item stacks to client every 5 seconds
        if(level.getTime() % 100 == 0) { //TODO improve
            PacketByteBuf buffer = PacketByteBufs.create();
            buffer.writeInt(0);
            blockEntity.fluidStorage.getFluid().toPacket(buffer);
            buffer.writeLong(blockEntity.fluidStorage.getCapacity());
            buffer.writeBlockPos(blockPos);

            ModMessages.sendServerPacketToPlayersWithinXBlocks(
                    blockPos, (ServerWorld)level, 32,
                    ModMessages.FLUID_SYNC_ID, buffer
            );
        }
    }

    public int getRedstoneOutput() {
        return FluidUtils.getRedstoneSignalFromFluidHandler(fluidStorage);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        nbt.put("fluid", fluidStorage.toNBT(new NbtCompound()));

        nbt.putBoolean("ignore_nbt", ignoreNBT);

        nbt.put("fluid_filter", fluidFilter.toNBT(new NbtCompound()));

        super.writeNbt(nbt);
    }

    @Override
    public void readNbt(@NotNull NbtCompound nbt) {
        super.readNbt(nbt);

        fluidStorage.fromNBT(nbt.getCompound("fluid"));

        ignoreNBT = nbt.getBoolean("ignore_nbt");

        fluidFilter = FluidStack.fromNbt(nbt.getCompound("fluid_filter"));
    }

    public void setIgnoreNBT(boolean ignoreNBT) {
        this.ignoreNBT = ignoreNBT;
        markDirty(world, getPos(), getCachedState());
    }

    @Override
    public void setCheckbox(int checkboxId, boolean checked) {
        switch(checkboxId) {
            //Ignore NBT
            case 0 -> setIgnoreNBT(checked);
        }
    }

    public void setFluidFilter(FluidStack fluidFilter) {
        this.fluidFilter = new FluidStack(fluidFilter.getFluid(), fluidFilter.getFluidVariant().copyNbt(),
                fluidFilter.getDropletsAmount());
        markDirty(world, getPos(), getCachedState());

        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeInt(1);
        fluidFilter.toPacket(buffer);
        buffer.writeLong(0);
        buffer.writeBlockPos(getPos());

        ModMessages.sendServerPacketToPlayersWithinXBlocks(
                getPos(), (ServerWorld)world, 32,
                ModMessages.FLUID_SYNC_ID, buffer
        );
    }

    public FluidStack getFluid(int tank) {
        return switch(tank) {
            case 0 -> fluidStorage.getFluid();
            case 1 -> fluidFilter;
            default -> null;
        };
    }

    public long getTankCapacity(int tank) {
        if(tank != 0)
            return 0;

        return fluidStorage.getCapacity();
    }

    @Override
    public void setFluid(int tank, FluidStack fluidStack) {
        switch(tank) {
            case 0 -> fluidStorage.setFluid(fluidStack);
            case 1 -> fluidFilter = new FluidStack(fluidStack.getFluid(), fluidStack.getFluidVariant().copyNbt(),
                    fluidStack.getDropletsAmount());
        }
    }

    @Override
    public void setTankCapacity(int tank, long capacity) {
        //Does nothing (capacity is final)
    }
}