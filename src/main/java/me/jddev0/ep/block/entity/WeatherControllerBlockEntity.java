package me.jddev0.ep.block.entity;

import me.jddev0.ep.block.entity.base.UpgradableEnergyStorageBlockEntity;
import me.jddev0.ep.config.ModConfigs;
import me.jddev0.ep.energy.ReceiveOnlyEnergyStorage;
import me.jddev0.ep.machine.upgrade.UpgradeModuleModifier;
import me.jddev0.ep.screen.WeatherControllerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.capabilities.Capabilities;
import net.neoforged.neoforge.common.capabilities.Capability;
import net.neoforged.neoforge.common.util.LazyOptional;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class WeatherControllerBlockEntity extends UpgradableEnergyStorageBlockEntity<ReceiveOnlyEnergyStorage> {
    private static final int WEATHER_CHANGED_TICKS = ModConfigs.COMMON_WEATHER_CONTROLLER_CONTROL_DURATION.getValue();

    private final LazyOptional<IEnergyStorage> lazyEnergyStorage;

    private int selectedWeatherType = -1;

    public WeatherControllerBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(
                ModBlockEntities.WEATHER_CONTROLLER_ENTITY.get(), blockPos, blockState,

                "weather_controller",

                ModConfigs.COMMON_WEATHER_CONTROLLER_CAPACITY.getValue(),
                ModConfigs.COMMON_WEATHER_CONTROLLER_TRANSFER_RATE.getValue(),

                UpgradeModuleModifier.DURATION
        );

        lazyEnergyStorage = LazyOptional.of(() -> energyStorage);
    }

    protected ContainerData initContainerData() {
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch(index) {
                    case 0 -> selectedWeatherType;
                    case 1 -> hasEnoughEnergy()?1:0;
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch(index) {
                    case 0 -> selectedWeatherType = value;
                    case 1 -> {}
                }
            }

            @Override
            public int getCount() {
                return 2;
            }
        };
    }

    @Override
    protected ReceiveOnlyEnergyStorage initEnergyStorage() {
        return new ReceiveOnlyEnergyStorage(0, baseEnergyCapacity, baseEnergyTransferRate) {
            @Override
            public int getMaxReceive() {
                return Math.max(1, (int)Math.ceil(maxReceive * upgradeModuleInventory.getModifierEffectProduct(
                        UpgradeModuleModifier.ENERGY_TRANSFER_RATE)));
            }

            @Override
            protected void onChange() {
                setChanged();
                syncEnergyToPlayers(32);
            }
        };
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        syncEnergyToPlayer(player);

        return new WeatherControllerMenu(id, inventory, this, upgradeModuleInventory, data);
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if(cap == Capabilities.ENERGY) {
            return lazyEnergyStorage.cast();
        }

        return super.getCapability(cap, side);
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag nbt) {
        super.saveAdditional(nbt);

        nbt.put("selected_weather_type", IntTag.valueOf(selectedWeatherType));
    }

    @Override
    public void load(@NotNull CompoundTag nbt) {
        super.load(nbt);

        selectedWeatherType = nbt.contains("selected_weather_type")?nbt.getInt("selected_weather_type"):-1;
    }


    public static void tick(Level level, BlockPos blockPos, BlockState state, WeatherControllerBlockEntity blockEntity) {
        if(level.isClientSide || !(level instanceof ServerLevel serverLevel))
            return;

        if(!blockEntity.hasInfiniteWeatherChangedDuration() || blockEntity.selectedWeatherType == -1)
            return;

        double energyConsumptionPerTick = blockEntity.upgradeModuleInventory.getModifierEffectSum(
                UpgradeModuleModifier.ENERGY_CONSUMPTION);

        if(blockEntity.energyStorage.getEnergy() >= energyConsumptionPerTick) {
            blockEntity.energyStorage.setEnergy((int)(blockEntity.energyStorage.getEnergy() - energyConsumptionPerTick));

            //Set weather every 5 seconds instead of for every tick
            if(level.getGameTime() % 100 == 0) {
                int duration = blockEntity.getWeatherChangedDuration();

                switch(blockEntity.selectedWeatherType) {
                    //Clear
                    case 0 -> serverLevel.setWeatherParameters(duration, 0, false, false);
                    //Rain
                    case 1 -> serverLevel.setWeatherParameters(0, duration, true, false);
                    //Thunder
                    case 2 -> serverLevel.setWeatherParameters(0, duration, true, true);
                }
            }
        }else {
            blockEntity.setSelectedWeatherType(-1);
        }
    }

    public void clearEnergy() {
        energyStorage.setEnergy(0);
    }

    public boolean hasEnoughEnergy() {
        if(hasInfiniteWeatherChangedDuration()) {
            double energyConsumptionPerTick = upgradeModuleInventory.getModifierEffectSum(
                    UpgradeModuleModifier.ENERGY_CONSUMPTION);

            return energyStorage.getEnergy() >= energyConsumptionPerTick;
        }

        return energyStorage.getEnergy() >= energyStorage.getCapacity();
    }

    public boolean hasInfiniteWeatherChangedDuration() {
        return upgradeModuleInventory.getModifierEffectProduct(UpgradeModuleModifier.DURATION) == Double.POSITIVE_INFINITY;
    }

    public int getWeatherChangedDuration() {
        //15 seconds for infinite duration upgrade
        if(hasInfiniteWeatherChangedDuration())
            return 300;

        return (int)Math.max(1, WEATHER_CHANGED_TICKS *
                upgradeModuleInventory.getModifierEffectProduct(UpgradeModuleModifier.DURATION));
    }

    public int getSelectedWeatherType() {
        return selectedWeatherType;
    }

    public void setSelectedWeatherType(int selectedWeatherType) {
        this.selectedWeatherType = selectedWeatherType;
        setChanged();
    }

    @Override
    protected void updateUpgradeModules() {
        //Reset to no selected weather type if infinite duration upgrade is not present
        if(!hasInfiniteWeatherChangedDuration())
            selectedWeatherType = -1;

        super.updateUpgradeModules();
    }
}