package me.jddev0.ep.block.entity;

import me.jddev0.ep.block.SolarPanelBlock;
import me.jddev0.ep.energy.EnergyStoragePacketUpdate;
import me.jddev0.ep.networking.ModMessages;
import me.jddev0.ep.screen.SolarPanelMenu;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.base.LimitingEnergyStorage;
import team.reborn.energy.api.base.SimpleEnergyStorage;

public class SolarPanelBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory, EnergyStoragePacketUpdate {
    private final SolarPanelBlock.Tier tier;
    private final long maxTransfer;

    final LimitingEnergyStorage energyStorage;
    private final SimpleEnergyStorage internalEnergyStorage;

    public static BlockEntityType<SolarPanelBlockEntity> getEntityTypeFromTier(SolarPanelBlock.Tier tier) {
        return switch(tier) {
            case TIER_1 -> ModBlockEntities.SOLAR_PANEL_ENTITY_1;
            case TIER_2 -> ModBlockEntities.SOLAR_PANEL_ENTITY_2;
            case TIER_3 -> ModBlockEntities.SOLAR_PANEL_ENTITY_3;
            case TIER_4 -> ModBlockEntities.SOLAR_PANEL_ENTITY_4;
            case TIER_5 -> ModBlockEntities.SOLAR_PANEL_ENTITY_5;
        };
    }

    public SolarPanelBlockEntity(BlockPos blockPos, BlockState blockState, SolarPanelBlock.Tier tier) {
        super(getEntityTypeFromTier(tier), blockPos, blockState);

        this.tier = tier;

        maxTransfer = tier.getMaxTransfer();
        long capacity = tier.getCapacity();
        internalEnergyStorage = new SimpleEnergyStorage(capacity, capacity, capacity) {
            @Override
            protected void onFinalCommit() {
                markDirty();

                if(world != null && !world.isClient()) {
                    PacketByteBuf buffer = PacketByteBufs.create();
                    buffer.writeLong(amount);
                    buffer.writeLong(capacity);
                    buffer.writeBlockPos(getPos());

                    ModMessages.broadcastServerPacket(world.getServer(), ModMessages.ENERGY_SYNC_ID, buffer);
                }
            }
        };
        energyStorage = new LimitingEnergyStorage(internalEnergyStorage, 0, maxTransfer);
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable("container.energizedpower." + tier.getResourceId());
    }

    @Nullable
    @Override
    public ScreenHandler createMenu(int id, PlayerInventory inventory, PlayerEntity player) {
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeLong(internalEnergyStorage.amount);
        buffer.writeLong(internalEnergyStorage.capacity);
        buffer.writeBlockPos(getPos());

        ModMessages.sendServerPacketToPlayer((ServerPlayerEntity)player, ModMessages.ENERGY_SYNC_ID, buffer);
        
        return new SolarPanelMenu(id, this, inventory);
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
        buf.writeBlockPos(pos);
    }

    public SolarPanelBlock.Tier getTier() {
        return tier;
    }

    public static void tick(World level, BlockPos blockPos, BlockState state, SolarPanelBlockEntity blockEntity) {
        if(level.isClient())
            return;

        int i = 4 * (level.getLightLevel(LightType.SKY, blockPos) - level.getAmbientDarkness()); //(0 - 15) * 4 => (0 - 60)
        float f = level.getSkyAngleRadians(1.0F);
        if(i > 0) {
            float f1 = f < (float)Math.PI ? 0.0F : ((float)Math.PI * 2F);

            f += (f1 - f) * 0.2F;

            i = Math.round((float)i * MathHelper.cos(f));
        }

        i = MathHelper.clamp(i, 0, 60);

        try(Transaction transaction = Transaction.openOuter()) {
            blockEntity.internalEnergyStorage.insert((long)(i/60.f * blockEntity.getTier().getPeakFePerTick()),
                    transaction);
            transaction.commit();
        }

        transferEnergy(level, blockPos, state, blockEntity);
    }

    private static void transferEnergy(World level, BlockPos blockPos, BlockState state, SolarPanelBlockEntity blockEntity) {
        if(level.isClient())
            return;

        BlockPos testPos = blockPos.offset(Direction.DOWN);

        BlockEntity testBlockEntity = level.getBlockEntity(testPos);
        if(testBlockEntity == null)
            return;

        EnergyStorage energyStorage = EnergyStorage.SIDED.find(level, testPos, Direction.DOWN.getOpposite());
        if(energyStorage == null)
            return;

        if(!energyStorage.supportsInsertion())
            return;

        try(Transaction transaction = Transaction.openOuter()) {
            long amount = energyStorage.insert(Math.min(blockEntity.internalEnergyStorage.amount, blockEntity.maxTransfer), transaction);
            blockEntity.energyStorage.extract(amount, transaction);
            transaction.commit();
        }
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        nbt.putLong("energy", internalEnergyStorage.amount);

        super.writeNbt(nbt);
    }

    @Override
    public void readNbt(@NotNull NbtCompound nbt) {
        super.readNbt(nbt);

        internalEnergyStorage.amount = nbt.getLong("energy");
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
        //Does nothing (capacity is final)
    }
}
