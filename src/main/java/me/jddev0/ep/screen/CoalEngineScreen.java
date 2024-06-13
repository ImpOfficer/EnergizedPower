package me.jddev0.ep.screen;

import me.jddev0.ep.EnergizedPowerMod;
import me.jddev0.ep.screen.base.ConfigurableUpgradableEnergyStorageContainerScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class CoalEngineScreen
        extends ConfigurableUpgradableEnergyStorageContainerScreen<CoalEngineMenu> {
    public CoalEngineScreen(CoalEngineMenu menu, Inventory inventory, Component component) {
        super(menu, inventory, component,
                "tooltip.energizedpower.coal_engine.txt",
                ResourceLocation.fromNamespaceAndPath(EnergizedPowerMod.MODID, "textures/gui/container/coal_engine.png"),
                ResourceLocation.fromNamespaceAndPath(EnergizedPowerMod.MODID, "textures/gui/container/upgrade_view/1_energy_capacity.png"));
    }

    @Override
    protected void renderTooltipNormalView(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        super.renderTooltipNormalView(guiGraphics, mouseX, mouseY);

        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;

        renderProgressFlame(guiGraphics, x, y);
    }

    private void renderProgressFlame(GuiGraphics guiGraphics, int x, int y) {
        if(menu.isProducingActive()) {
            int pos = menu.getScaledProgressFlameSize();
            guiGraphics.blit(TEXTURE, x + 81, y + 28 + pos, 176, 53 + pos, 14, 14 - pos);
        }
    }
}
