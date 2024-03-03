package me.jddev0.ep.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import me.jddev0.ep.EnergizedPowerMod;
import me.jddev0.ep.machine.configuration.ComparatorMode;
import me.jddev0.ep.machine.configuration.RedstoneMode;
import me.jddev0.ep.networking.ModMessages;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Environment(EnvType.CLIENT)
public class BlockPlacerScreen extends AbstractGenericEnergyStorageHandledScreen<BlockPlacerMenu> {
    private final Identifier CONFIGURATION_ICONS_TEXTURE = new Identifier(EnergizedPowerMod.MODID, "textures/gui/machine_configuration/configuration_buttons.png");

    public BlockPlacerScreen(BlockPlacerMenu menu, PlayerInventory inventory, Text component) {
        super(menu, inventory, component,
                "tooltip.energizedpower.block_placer.block_energy_left.txt",
                new Identifier(EnergizedPowerMod.MODID, "textures/gui/container/block_placer.png"),
                8, 17);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if(mouseButton == 0) {
            boolean clicked = false;
            if(isPointWithinBounds(158, 16, 11, 11, mouseX, mouseY)) {
                //Inverse rotation checkbox

                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeBlockPos(handler.getBlockEntity().getPos());
                buf.writeInt(0);
                buf.writeBoolean(!handler.isInverseRotation());
                ClientPlayNetworking.send(ModMessages.SET_CHECKBOX_ID, buf);
                clicked = true;
            }else if(isPointWithinBounds(-22, 2, 20, 20, mouseX, mouseY)) {
                //Redstone Mode

                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeBlockPos(handler.getBlockEntity().getPos());
                ClientPlayNetworking.send(ModMessages.CHANGE_REDSTONE_MODE_ID, buf);
                clicked = true;
            }else if(isPointWithinBounds(-22, 26, 20, 20, mouseX, mouseY)) {
                //Comparator Mode

                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeBlockPos(handler.getBlockEntity().getPos());
                ClientPlayNetworking.send(ModMessages.CHANGE_COMPARATOR_MODE_ID, buf);
                clicked = true;
            }

            if(clicked)
                client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.f));
        }

        return super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void drawBackground(MatrixStack poseStack, float partialTick, int mouseX, int mouseY) {
        super.drawBackground(poseStack, partialTick, mouseX, mouseY);

        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        renderCheckboxes(poseStack, x, y, mouseX, mouseY);

        renderConfiguration(poseStack, x, y, mouseX, mouseY);
    }

    private void renderCheckboxes(MatrixStack poseStack, int x, int y, int mouseX, int mouseY) {
        if(handler.isInverseRotation()) {
            //Inverse rotation checkbox

            drawTexture(poseStack, x + 158, y + 16, 176, 53, 11, 11);
        }
    }

    private void renderConfiguration(MatrixStack poseStack, int x, int y, int mouseX, int mouseY) {
        RedstoneMode redstoneMode = handler.getRedstoneMode();
        int ordinal = redstoneMode.ordinal();

        RenderSystem.setShaderTexture(0, CONFIGURATION_ICONS_TEXTURE);
        if(isPointWithinBounds(-22, 2, 20, 20, mouseX, mouseY)) {
            drawTexture(poseStack, x - 22, y + 2, 20 * ordinal, 20, 20, 20);
        }else {
            drawTexture(poseStack, x - 22, y + 2, 20 * ordinal, 0, 20, 20);
        }

        ComparatorMode comparatorMode = handler.getComparatorMode();
        ordinal = comparatorMode.ordinal();

        if(isPointWithinBounds(-22, 26, 20, 20, mouseX, mouseY)) {
            drawTexture(poseStack, x - 22, y + 26, 20 * ordinal, 60, 20, 20);
        }else {
            drawTexture(poseStack, x - 22, y + 26, 20 * ordinal, 40, 20, 20);
        }
    }

    @Override
    protected void drawMouseoverTooltip(MatrixStack poseStack, int mouseX, int mouseY) {
        super.drawMouseoverTooltip(poseStack, mouseX, mouseY);

        if(isPointWithinBounds(158, 16, 11, 11, mouseX, mouseY)) {
            //Inverse rotation checkbox

            List<Text> components = new ArrayList<>(2);
            components.add(Text.translatable("tooltip.energizedpower.block_placer.cbx.inverse_rotation"));

            renderTooltip(poseStack, components, Optional.empty(), mouseX, mouseY);
        }else if(isPointWithinBounds(-22, 2, 20, 20, mouseX, mouseY)) {
            //Redstone Mode

            RedstoneMode redstoneMode = handler.getRedstoneMode();

            List<Text> components = new ArrayList<>(2);
            components.add(Text.translatable("tooltip.energizedpower.machine_configuration.redstone_mode." + redstoneMode.asString()));

            renderTooltip(poseStack, components, Optional.empty(), mouseX, mouseY);
        }else if(isPointWithinBounds(-22, 26, 20, 20, mouseX, mouseY)) {
            //Comparator Mode

            ComparatorMode comparatorMode = handler.getComparatorMode();

            List<Text> components = new ArrayList<>(2);
            components.add(Text.translatable("tooltip.energizedpower.machine_configuration.comparator_mode." + comparatorMode.asString()));

            renderTooltip(poseStack, components, Optional.empty(), mouseX, mouseY);
        }
    }
}
