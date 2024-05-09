package me.jddev0.ep.block.entity;

import com.mojang.datafixers.util.Pair;
import me.jddev0.ep.block.CableBlock;
import me.jddev0.ep.config.ModConfigs;
import me.jddev0.ep.networking.ModMessages;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import team.reborn.energy.api.EnergyStorage;
import me.jddev0.ep.energy.EnergizedPowerEnergyStorage;
import me.jddev0.ep.energy.EnergizedPowerLimitingEnergyStorage;

import java.util.*;

public class CableBlockEntity extends BlockEntity {
    private static final CableBlock.EnergyExtractionMode ENERGY_EXTRACTION_MODE = ModConfigs.COMMON_CABLES_ENERGY_EXTRACTION_MODE.getValue();

    private final CableBlock.Tier tier;

    final EnergizedPowerLimitingEnergyStorage energyStorage;
    private final EnergizedPowerEnergyStorage internalEnergyStorage;

    private boolean loaded;

    private final Map<Pair<BlockPos, Direction>, EnergyStorage> producers = new HashMap<>();
    private final Map<Pair<BlockPos, Direction>, EnergyStorage> consumers = new HashMap<>();
    private final List<BlockPos> cableBlocks = new LinkedList<>();

    public static BlockEntityType<CableBlockEntity> getEntityTypeFromTier(CableBlock.Tier tier) {
        return switch(tier) {
            case TIER_TIN -> ModBlockEntities.TIN_CABLE_ENTITY;
            case TIER_COPPER -> ModBlockEntities.COPPER_CABLE_ENTITY;
            case TIER_GOLD -> ModBlockEntities.GOLD_CABLE_ENTITY;
            case TIER_ENERGIZED_COPPER -> ModBlockEntities.ENERGIZED_COPPER_CABLE_ENTITY;
            case TIER_ENERGIZED_GOLD -> ModBlockEntities.ENERGIZED_GOLD_CABLE_ENTITY;
            case TIER_ENERGIZED_CRYSTAL_MATRIX -> ModBlockEntities.ENERGIZED_CRYSTAL_MATRIX_CABLE_ENTITY;
        };
    }

    public CableBlockEntity(BlockPos blockPos, BlockState blockState, CableBlock.Tier tier) {
        super(getEntityTypeFromTier(tier), blockPos, blockState);

        this.tier = tier;

        long capacity = ENERGY_EXTRACTION_MODE.isPush()?tier.getMaxTransfer():0;

        internalEnergyStorage = new EnergizedPowerEnergyStorage(capacity, capacity, capacity) {
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
        energyStorage = new EnergizedPowerLimitingEnergyStorage(internalEnergyStorage, capacity, 0);
    }

    public CableBlock.Tier getTier() {
        return tier;
    }

    public Map<Pair<BlockPos, Direction>, EnergyStorage> getProducers() {
        return producers;
    }

    public Map<Pair<BlockPos, Direction>, EnergyStorage> getConsumers() {
        return consumers;
    }

    public List<BlockPos> getCableBlocks() {
        return cableBlocks;
    }

    public static void updateConnections(World level, BlockPos blockPos, BlockState state, CableBlockEntity blockEntity) {
        if(level.isClient())
            return;

        blockEntity.producers.clear();
        blockEntity.consumers.clear();
        blockEntity.cableBlocks.clear();

        for(Direction direction:Direction.values()) {
            BlockPos testPos = blockPos.offset(direction);

            BlockEntity testBlockEntity = level.getBlockEntity(testPos);
            if(testBlockEntity == null)
                continue;

            if(testBlockEntity instanceof CableBlockEntity cableBlockEntity) {
                if(cableBlockEntity.getTier() != blockEntity.getTier()) //Do not connect to different cable tiers
                    continue;

                blockEntity.cableBlocks.add(testPos);

                continue;
            }

            EnergyStorage energyStorage = EnergyStorage.SIDED.find(level, testPos, direction.getOpposite());
            if(energyStorage == null)
                continue;

            if(ENERGY_EXTRACTION_MODE.isPull() && energyStorage.supportsExtraction())
                blockEntity.producers.put(Pair.of(testPos, direction.getOpposite()), energyStorage);

            if(energyStorage.supportsInsertion())
                blockEntity.consumers.put(Pair.of(testPos, direction.getOpposite()), energyStorage);
        }
    }

    public static List<EnergyStorage> getConnectedConsumers(World level, BlockPos blockPos, List<BlockPos> checkedCables) {
        List<EnergyStorage> consumers = new LinkedList<>();

        LinkedList<BlockPos> cableBlocksLeft = new LinkedList<>();
        cableBlocksLeft.add(blockPos);

        checkedCables.add(blockPos);

        while(cableBlocksLeft.size() > 0) {
            BlockPos checkPos = cableBlocksLeft.pop();

            BlockEntity blockEntity = level.getBlockEntity(checkPos);
            if(!(blockEntity instanceof CableBlockEntity))
                continue;

            CableBlockEntity cableBlockEntity = (CableBlockEntity)blockEntity;
            cableBlockEntity.getCableBlocks().forEach(pos -> {
                if(!checkedCables.contains(pos)) {
                    checkedCables.add(pos);

                    cableBlocksLeft.add(pos);
                }
            });

            consumers.addAll(cableBlockEntity.getConsumers().values());
        }

        return consumers;
    }

    public static void tick(World level, BlockPos blockPos, BlockState state, CableBlockEntity blockEntity) {
        if(level.isClient())
            return;

        if(!blockEntity.loaded) {
            updateConnections(level, blockPos, state, blockEntity);

            blockEntity.loaded = true;
        }

        final long MAX_TRANSFER = blockEntity.tier.getMaxTransfer();
        List<EnergyStorage> energyProduction = new LinkedList<>();
        List<Long> energyProductionValues = new LinkedList<>();

        //Prioritize stored energy for PUSH mode
        long productionSum = blockEntity.internalEnergyStorage.getAmount(); //Will always be 0 if in PULL only mode
        for(EnergyStorage energyStorage:blockEntity.producers.values()) {
            try(Transaction transaction = Transaction.openOuter()) {
                long extracted = energyStorage.extract(MAX_TRANSFER, transaction);

                if(extracted <= 0)
                    continue;

                energyProduction.add(energyStorage);
                energyProductionValues.add(extracted);
                productionSum += extracted;
            }
        }

        if(productionSum <= 0)
            return;

        List<EnergyStorage> energyConsumption = new LinkedList<>();
        List<Long> energyConsumptionValues = new LinkedList<>();

        List<EnergyStorage> consumers = getConnectedConsumers(level, blockPos, new LinkedList<>());

        long consumptionSum = 0;
        for(EnergyStorage energyStorage:consumers) {
            try(Transaction transaction = Transaction.openOuter()) {
                long received = energyStorage.insert(MAX_TRANSFER, transaction);

                if(received <= 0)
                    continue;

                energyConsumption.add(energyStorage);
                energyConsumptionValues.add(received);
                consumptionSum += received;
            }
        }

        if(consumptionSum <= 0)
            return;

        long transferLeft = Math.min(Math.min(MAX_TRANSFER, productionSum), consumptionSum);

        long extractInternally = 0;
        if(ENERGY_EXTRACTION_MODE.isPush()) {
            //Prioritize stored energy for PUSH mode
            extractInternally = Math.min(blockEntity.internalEnergyStorage.getAmount(), transferLeft);
            try(Transaction transaction = Transaction.openOuter()) {
                blockEntity.internalEnergyStorage.extract(extractInternally, transaction);

                transaction.commit();
            }
        }

        List<Long> energyProductionDistributed = new LinkedList<>();
        for(int i = 0;i < energyProduction.size();i++)
            energyProductionDistributed.add(0L);

        //Set to 0 for PUSH only mode
        long productionLeft = ENERGY_EXTRACTION_MODE.isPull()?transferLeft - extractInternally:0;
        int divisor = energyProduction.size();
        outer:
        while(productionLeft > 0) {
            long productionPerProducer = productionLeft / divisor;
            if(productionPerProducer == 0) {
                divisor = Math.max(1, divisor - 1);
                productionPerProducer = productionLeft / divisor;
            }

            for(int i = 0;i < energyProductionValues.size();i++) {
                long productionDistributed = energyProductionDistributed.get(i);
                long productionOfProducerLeft = energyProductionValues.get(i) - productionDistributed;

                long productionDistributedNew = Math.min(productionPerProducer, Math.min(productionOfProducerLeft, productionLeft));
                energyProductionDistributed.set(i, productionDistributed + productionDistributedNew);
                productionLeft -= productionDistributedNew;
                if(productionLeft == 0)
                    break outer;
            }
        }

        for(int i = 0;i < energyProduction.size();i++) {
            long energy = energyProductionDistributed.get(i);
            if(energy > 0)
                try(Transaction transaction = Transaction.openOuter()) {
                    energyProduction.get(i).extract(energy, transaction);
                    transaction.commit();
                }
        }

        List<Long> energyConsumptionDistributed = new LinkedList<>();
        for(int i = 0;i < energyConsumption.size();i++)
            energyConsumptionDistributed.add(0L);

        long consumptionLeft = transferLeft;
        divisor = energyConsumption.size();
        outer:
        while(consumptionLeft > 0) {
            long consumptionPerConsumer = consumptionLeft / divisor;
            if(consumptionPerConsumer == 0) {
                divisor = Math.max(1, divisor - 1);
                consumptionPerConsumer = consumptionLeft / divisor;
            }

            for(int i = 0;i < energyConsumptionValues.size();i++) {
                long consumptionDistributed = energyConsumptionDistributed.get(i);
                long consumptionOfConsumerLeft = energyConsumptionValues.get(i) - consumptionDistributed;

                long consumptionDistributedNew = Math.min(consumptionOfConsumerLeft, Math.min(consumptionPerConsumer, consumptionLeft));
                energyConsumptionDistributed.set(i, consumptionDistributed + consumptionDistributedNew);
                consumptionLeft -= consumptionDistributedNew;
                if(consumptionLeft == 0)
                    break outer;
            }
        }

        for(int i = 0;i < energyConsumption.size();i++) {
            long energy = energyConsumptionDistributed.get(i);
            if(energy > 0) {
                try(Transaction transaction = Transaction.openOuter()) {
                    energyConsumption.get(i).insert(energy, transaction);
                    transaction.commit();
                }
            }
        }
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        if(ENERGY_EXTRACTION_MODE.isPush())
            nbt.putLong("energy", internalEnergyStorage.getAmount());


        super.writeNbt(nbt);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);

        if(ENERGY_EXTRACTION_MODE.isPush())
            internalEnergyStorage.setAmountWithoutUpdate(nbt.getLong("energy"));
    }
}
