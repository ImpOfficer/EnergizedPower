package me.jddev0.ep.screen;

import me.jddev0.ep.EnergizedPowerMod;
import me.jddev0.ep.block.entity.TimeControllerBlockEntity;
import me.jddev0.ep.networking.ModMessages;
import me.jddev0.ep.networking.packet.SetWeatherFromWeatherControllerC2SPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@OnlyIn(Dist.CLIENT)
public class WeatherControllerScreen extends AbstractGenericEnergyStorageContainerScreen<WeatherControllerMenu> {
    private final ResourceLocation CONFIGURATION_ICONS_TEXTURE =
            new ResourceLocation(EnergizedPowerMod.MODID, "textures/gui/machine_configuration/configuration_buttons.png");
    private final ResourceLocation UPGRADE_VIEW_TEXTURE =
            new ResourceLocation(EnergizedPowerMod.MODID, "textures/gui/container/upgrade_view/1_energy_capacity.png");

    public WeatherControllerScreen(WeatherControllerMenu menu, Inventory inventory, Component component) {
        super(menu, inventory, component,
                new ResourceLocation(EnergizedPowerMod.MODID, "textures/gui/container/weather_controller.png"),
                8, 17);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if(mouseButton == 0) {
            boolean clicked = false;
            if(!menu.isInUpgradeModuleView()) {
                if(isHovering(52, 34, 18, 18, mouseX, mouseY)) {
                    //Weather clear button

                    ModMessages.sendToServer(new SetWeatherFromWeatherControllerC2SPacket(menu.getBlockEntity().getBlockPos(), 0));
                    clicked = true;
                }else if(isHovering(88, 34, 18, 18, mouseX, mouseY)) {
                    //Weather rain button

                    ModMessages.sendToServer(new SetWeatherFromWeatherControllerC2SPacket(menu.getBlockEntity().getBlockPos(), 1));
                    clicked = true;
                }else if(isHovering(124, 34, 18, 18, mouseX, mouseY)) {
                    //Weather thunder button

                    ModMessages.sendToServer(new SetWeatherFromWeatherControllerC2SPacket(menu.getBlockEntity().getBlockPos(), 2));
                    clicked = true;
                }
            }

            if(isHovering(-22, 2, 20, 20, mouseX, mouseY)) {
                //Upgrade view

                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0);
                clicked = true;
            }

            if(clicked)
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.f));
        }

        return super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);

        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;

        if(menu.isInUpgradeModuleView()) {
            guiGraphics.blit(UPGRADE_VIEW_TEXTURE, x, y, 0, 0, imageWidth, imageHeight);
        }else {
            renderButtons(guiGraphics, x, y, mouseX, mouseY);
            renderInfoText(guiGraphics, x, y);
        }

        renderConfiguration(guiGraphics, x, y, mouseX, mouseY);
    }

    private void renderButtons(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY) {
        if(isHovering(52, 34, 18, 18, mouseX, mouseY)) {
            //Weather clear button

            guiGraphics.blit(TEXTURE, x + 52, y + 34, 176, 52, 18, 18);
        }else if(isHovering(88, 34, 18, 18, mouseX, mouseY)) {
            //Weather rain button

            guiGraphics.blit(TEXTURE, x + 88, y + 34, 176, 70, 18, 18);
        }else if(isHovering(124, 34, 18, 18, mouseX, mouseY)) {
            //Weather thunder button

            guiGraphics.blit(TEXTURE, x + 124, y + 34, 176, 88, 18, 18);
        }
    }

    private void renderInfoText(GuiGraphics guiGraphics, int x, int y) {
        Component component = menu.getEnergy() < TimeControllerBlockEntity.CAPACITY?
                Component.translatable("tooltip.energizedpower.not_enough_energy.txt").withStyle(ChatFormatting.RED):
                Component.translatable("tooltip.energizedpower.ready.txt").withStyle(ChatFormatting.DARK_GREEN);

        int componentWidth = font.width(component);

        guiGraphics.drawString(font, component, (int)(x + 34 + (126 - componentWidth) * .5f), y + 58, 0, false);
    }

    private void renderConfiguration(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY) {
        //Upgrade view
        if(isHovering(-22, 2, 20, 20, mouseX, mouseY)) {
            guiGraphics.blit(CONFIGURATION_ICONS_TEXTURE, x - 22, y + 2, 40, 80, 20, 20);
        }else if(menu.isInUpgradeModuleView()) {
            guiGraphics.blit(CONFIGURATION_ICONS_TEXTURE, x - 22, y + 2, 20, 80, 20, 20);
        }else {
            guiGraphics.blit(CONFIGURATION_ICONS_TEXTURE, x - 22, y + 2, 0, 80, 20, 20);
        }
    }

    @Override
    protected void renderTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderTooltip(guiGraphics, mouseX, mouseY);

        if(!menu.isInUpgradeModuleView()) {
            if(isHovering(52, 34, 18, 18, mouseX, mouseY)) {
                //Weather clear button

                List<Component> components = new ArrayList<>(2);
                components.add(Component.translatable("tooltip.energizedpower.weather_controller.btn.clear"));

                guiGraphics.renderTooltip(font, components, Optional.empty(), mouseX, mouseY);
            }else if(isHovering(88, 34, 18, 18, mouseX, mouseY)) {
                //Weather rain button

                List<Component> components = new ArrayList<>(2);
                components.add(Component.translatable("tooltip.energizedpower.weather_controller.btn.rain"));

                guiGraphics.renderTooltip(font, components, Optional.empty(), mouseX, mouseY);
            }else if(isHovering(124, 34, 18, 18, mouseX, mouseY)) {
                //Weather thunder button

                List<Component> components = new ArrayList<>(2);
                components.add(Component.translatable("tooltip.energizedpower.weather_controller.btn.thunder"));

                guiGraphics.renderTooltip(font, components, Optional.empty(), mouseX, mouseY);
            }
        }

        if(isHovering(-22, 2, 20, 20, mouseX, mouseY)) {
            //Upgrade view

            List<Component> components = new ArrayList<>(2);
            components.add(Component.translatable("tooltip.energizedpower.upgrade_view.button." +
                    (menu.isInUpgradeModuleView()?"close":"open")));

            guiGraphics.renderTooltip(font, components, Optional.empty(), mouseX, mouseY);
        }
    }
}
