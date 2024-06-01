package me.jddev0.ep.screen.base;

public interface EnergyStorageConsumerIndicatorBarMenu extends EnergyStorageMenu {
    @Override
    long getEnergyIndicatorBarValue();

    @Override
    default int getScaledEnergyIndicatorBarPos(int energyMeterHeight) {
        long energyRequirement = getEnergyIndicatorBarValue();
        long capacity = getCapacity();

        return (int)((energyRequirement <= 0 || capacity == 0)?0:
                (Math.min(energyRequirement, capacity - 1) * energyMeterHeight / capacity + 1));
    }
}
