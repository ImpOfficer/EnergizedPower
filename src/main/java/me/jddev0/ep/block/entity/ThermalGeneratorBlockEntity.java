package me.jddev0.ep.block.entity;

import me.jddev0.ep.config.ModConfigs;
import me.jddev0.ep.energy.EnergyStoragePacketUpdate;
import me.jddev0.ep.fluid.FluidStack;
import me.jddev0.ep.fluid.FluidStoragePacketUpdate;
import me.jddev0.ep.fluid.SimpleFluidStorage;
import me.jddev0.ep.networking.ModMessages;
import me.jddev0.ep.recipe.ThermalGeneratorRecipe;
import me.jddev0.ep.screen.ThermalGeneratorMenu;
import me.jddev0.ep.util.ByteUtils;
import me.jddev0.ep.util.FluidUtils;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.Fluid;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.base.LimitingEnergyStorage;
import me.jddev0.ep.energy.EnergizedPowerEnergyStorage;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class ThermalGeneratorBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory, EnergyStoragePacketUpdate,
        FluidStoragePacketUpdate {
    private static final long CAPACITY = ModConfigs.COMMON_THERMAL_GENERATOR_CAPACITY.getValue();
    private static final long MAX_EXTRACT = ModConfigs.COMMON_THERMAL_GENERATOR_TRANSFER_RATE.getValue();

    public static final double ENERGY_PRODUCTION_MULTIPLIER = ModConfigs.COMMON_THERMAL_GENERATOR_ENERGY_PRODUCTION_MULTIPLIER.getValue();

    final LimitingEnergyStorage energyStorage;
    private final EnergizedPowerEnergyStorage internalEnergyStorage;

    final SimpleFluidStorage fluidStorage;

    protected final PropertyDelegate data;

    public ThermalGeneratorBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(ModBlockEntities.THERMAL_GENERATOR_ENTITY, blockPos, blockState);

        internalEnergyStorage = new EnergizedPowerEnergyStorage(CAPACITY, CAPACITY, CAPACITY) {
            @Override
            protected void onFinalCommit() {
                markDirty();

                if(world != null && !world.isClient()) {
                    PacketByteBuf buffer = PacketByteBufs.create();
                    buffer.writeLong(amount);
                    buffer.writeLong(capacity);
                    buffer.writeBlockPos(getPos());

                    ModMessages.sendServerPacketToPlayersWithinXBlocks(
                            getPos(), (ServerWorld)world, 32,
                            ModMessages.ENERGY_SYNC_ID, buffer
                    );
                }
            }
        };
        energyStorage = new LimitingEnergyStorage(internalEnergyStorage, 0, MAX_EXTRACT);

        fluidStorage = new SimpleFluidStorage(FluidUtils.convertMilliBucketsToDroplets(
                ModConfigs.COMMON_THERMAL_GENERATOR_FLUID_TANK_CAPACITY.getValue() * 1000)) {
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
                if(world == null)
                    return false;

                List<RecipeEntry<ThermalGeneratorRecipe>> recipes = world.getRecipeManager().listAllOfType(ThermalGeneratorRecipe.Type.INSTANCE);

                return recipes.stream().map(RecipeEntry::value).map(ThermalGeneratorRecipe::getInput).
                        anyMatch(inputs -> Arrays.stream(inputs).anyMatch(input -> variant.getFluid() == input));
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
                if(world == null || index > 3)
                    return 0;

                List<RecipeEntry<ThermalGeneratorRecipe>> recipes = world.getRecipeManager().listAllOfType(ThermalGeneratorRecipe.Type.INSTANCE);

                long rawProduction = 0;
                outer:
                for(RecipeEntry<ThermalGeneratorRecipe> recipe:recipes) {
                    for(Fluid fluid:recipe.value().getInput()) {
                        if(fluidStorage.getFluid().getFluid() == fluid) {
                            rawProduction = recipe.value().getEnergyProduction();
                            rawProduction = (long)(rawProduction * ENERGY_PRODUCTION_MULTIPLIER);

                            break outer;
                        }
                    }
                }
                //Calculate real production (raw production is in x E per 1000 mB, use fluid amount without cap)
                long productionLeft = (long)(rawProduction * fluidStorage.getFluid().getMilliBucketsAmount() / 1000.);

                return switch(index) {
                    case 0, 1, 2, 3 -> ByteUtils.get2Bytes(productionLeft, index);
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch(index) {
                    case 0, 1, 2, 3 -> {}
                }
            }

            @Override
            public int size() {
                return 4;
            }
        };
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable("container.energizedpower.thermal_generator");
    }

    @Nullable
    @Override
    public ScreenHandler createMenu(int id, PlayerInventory inventory, PlayerEntity player) {
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeLong(internalEnergyStorage.amount);
        buffer.writeLong(internalEnergyStorage.capacity);
        buffer.writeBlockPos(getPos());

        ModMessages.sendServerPacketToPlayer((ServerPlayerEntity)player, ModMessages.ENERGY_SYNC_ID, buffer);

        buffer = PacketByteBufs.create();
        buffer.writeInt(0);
        fluidStorage.getFluid().toPacket(buffer);
        buffer.writeLong(fluidStorage.getCapacity());
        buffer.writeBlockPos(getPos());

        ModMessages.sendServerPacketToPlayer((ServerPlayerEntity)player, ModMessages.FLUID_SYNC_ID, buffer);

        return new ThermalGeneratorMenu(id, this, inventory, this.data);
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
        buf.writeBlockPos(pos);
    }

    public int getRedstoneOutput() {
        float fullnessPercent = 0;
        boolean isEmptyFlag = true;

        FluidStack fluid = fluidStorage.getFluid();
        if(!fluidStorage.isEmpty()) {
            fullnessPercent = (float)fluid.getDropletsAmount() / fluidStorage.getCapacity();
            isEmptyFlag = false;
        }

        return Math.min(MathHelper.floor(fullnessPercent * 14.f) + (isEmptyFlag?0:1), 15);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        nbt.putLong("energy", internalEnergyStorage.amount);
        nbt.put("fluid", fluidStorage.toNBT(new NbtCompound()));

        super.writeNbt(nbt);
    }

    @Override
    public void readNbt(@NotNull NbtCompound nbt) {
        super.readNbt(nbt);

        internalEnergyStorage.amount = nbt.getLong("energy");
        fluidStorage.fromNBT(nbt.getCompound("fluid"));
    }

    public static void tick(World level, BlockPos blockPos, BlockState state, ThermalGeneratorBlockEntity blockEntity) {
        if(level.isClient())
            return;

        List<RecipeEntry<ThermalGeneratorRecipe>> recipes = level.getRecipeManager().listAllOfType(ThermalGeneratorRecipe.Type.INSTANCE);

        long rawProduction = 0;
        outer:
        for(RecipeEntry<ThermalGeneratorRecipe> recipe:recipes) {
            for(Fluid fluid:recipe.value().getInput()) {
                if(blockEntity.fluidStorage.getFluid().getFluid() == fluid) {
                    rawProduction = recipe.value().getEnergyProduction();
                    rawProduction = (long)(rawProduction * ENERGY_PRODUCTION_MULTIPLIER);

                    break outer;
                }
            }
        }

        if(rawProduction > 0 && blockEntity.internalEnergyStorage.amount < blockEntity.internalEnergyStorage.capacity) {
            //Calculate real production (raw production is in x E per 1000 mB, 50 mB of fluid can be consumed per tick)
            long production = (long)(rawProduction * (Math.min(
                    blockEntity.fluidStorage.getFluid().getMilliBucketsAmount(), 50) / 1000.));

            //Cap production
            production = Math.min(production, blockEntity.internalEnergyStorage.capacity - blockEntity.internalEnergyStorage.amount);

            long fluidAmount = (long)((double)production/rawProduction * 1000);

            //Re-calculate energy production (Prevents draining of not enough fluid)
            production = (long)(rawProduction * fluidAmount / 1000.);

            try(Transaction transaction = Transaction.openOuter()) {
                blockEntity.fluidStorage.extract(blockEntity.fluidStorage.variant,
                        FluidUtils.convertMilliBucketsToDroplets(fluidAmount), transaction);

                blockEntity.internalEnergyStorage.insert(production, transaction);
                transaction.commit();
            }
        }

        transferEnergy(level, blockPos, state, blockEntity);
    }

    private static void transferEnergy(World level, BlockPos blockPos, BlockState state, ThermalGeneratorBlockEntity blockEntity) {
        if(level.isClient())
            return;

        List<EnergyStorage> consumerItems = new LinkedList<>();
        List<Long> consumerEnergyValues = new LinkedList<>();
        int consumptionSum = 0;
        for(Direction direction:Direction.values()) {
            BlockPos testPos = blockPos.offset(direction);

            BlockEntity testBlockEntity = level.getBlockEntity(testPos);
            if(testBlockEntity == null)
                continue;

            EnergyStorage energyStorage = EnergyStorage.SIDED.find(level, testPos, direction.getOpposite());
            if(energyStorage == null)
                continue;

            if(!energyStorage.supportsInsertion())
                continue;

            try(Transaction transaction = Transaction.openOuter()) {
                long received = energyStorage.insert(Math.min(MAX_EXTRACT, blockEntity.internalEnergyStorage.amount), transaction);

                if(received <= 0)
                    continue;

                consumptionSum += received;
                consumerItems.add(energyStorage);
                consumerEnergyValues.add(received);
            }
        }

        List<Long> consumerEnergyDistributed = new LinkedList<>();
        for(int i = 0;i < consumerItems.size();i++)
            consumerEnergyDistributed.add(0L);

        long consumptionLeft = Math.min(MAX_EXTRACT, Math.min(blockEntity.internalEnergyStorage.amount, consumptionSum));
        try(Transaction transaction = Transaction.openOuter()) {
            blockEntity.internalEnergyStorage.extract(consumptionLeft, transaction);
            transaction.commit();
        }

        int divisor = consumerItems.size();
        outer:
        while(consumptionLeft > 0) {
            long consumptionPerConsumer = consumptionLeft / divisor;
            if(consumptionPerConsumer == 0) {
                divisor = Math.max(1, divisor - 1);
                consumptionPerConsumer = consumptionLeft / divisor;
            }

            for(int i = 0;i < consumerEnergyValues.size();i++) {
                long consumptionDistributed = consumerEnergyDistributed.get(i);
                long consumptionOfConsumerLeft = consumerEnergyValues.get(i) - consumptionDistributed;

                long consumptionDistributedNew = Math.min(consumptionOfConsumerLeft, Math.min(consumptionPerConsumer, consumptionLeft));
                consumerEnergyDistributed.set(i, consumptionDistributed + consumptionDistributedNew);
                consumptionLeft -= consumptionDistributedNew;
                if(consumptionLeft == 0)
                    break outer;
            }
        }

        for(int i = 0;i < consumerItems.size();i++) {
            long energy = consumerEnergyDistributed.get(i);
            if(energy > 0) {
                try(Transaction transaction = Transaction.openOuter()) {
                    consumerItems.get(i).insert(energy, transaction);
                    transaction.commit();
                }
            }
        }
    }

    public FluidStack getFluid(int tank) {
        return fluidStorage.getFluid();
    }

    public long getTankCapacity(int tank) {
        return fluidStorage.getCapacity();
    }

    @Override
    public void setEnergy(long energy) {
        internalEnergyStorage.amount = energy;
    }

    @Override
    public void setCapacity(long capacity) {
        internalEnergyStorage.capacity = capacity;
    }

    public long getEnergy() {
        return internalEnergyStorage.amount;
    }

    public long getCapacity() {
        return internalEnergyStorage.capacity;
    }

    @Override
    public void setFluid(int tank, FluidStack fluidStack) {
        fluidStorage.setFluid(fluidStack);
    }

    @Override
    public void setTankCapacity(int tank, long capacity) {
        //Does nothing (capacity is final)
    }
}