package me.jddev0.ep.networking.packet;

import me.jddev0.ep.energy.EnergyStoragePacketUpdate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class EnergySyncS2CPacket {
    private final int energy;
    private final int capacity;
    private final BlockPos pos;

    public EnergySyncS2CPacket(int energy, int capacity, BlockPos pos) {
        this.energy = energy;
        this.capacity = capacity;
        this.pos = pos;
    }

    public EnergySyncS2CPacket(FriendlyByteBuf buffer) {
        energy = buffer.readInt();
        capacity = buffer.readInt();
        pos = buffer.readBlockPos();
    }

    public void toBytes(FriendlyByteBuf buffer) {
        buffer.writeInt(energy);
        buffer.writeInt(capacity);
        buffer.writeBlockPos(pos);
    }

    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            BlockEntity blockEntity = Minecraft.getInstance().level.getBlockEntity(pos);

            //BlockEntity
            if(blockEntity instanceof EnergyStoragePacketUpdate) {
                EnergyStoragePacketUpdate energyStorage = (EnergyStoragePacketUpdate)blockEntity;
                energyStorage.setCapacity(capacity);
                energyStorage.setEnergy(energy);
            }
        });

        return true;
    }
}
