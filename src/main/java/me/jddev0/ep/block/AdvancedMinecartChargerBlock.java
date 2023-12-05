package me.jddev0.ep.block;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import me.jddev0.ep.block.entity.AdvancedMinecartChargerBlockEntity;
import me.jddev0.ep.block.entity.ModBlockEntities;
import me.jddev0.ep.codec.CodecFix;
import me.jddev0.ep.util.EnergyUtils;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class AdvancedMinecartChargerBlock extends BlockWithEntity {
    public static final MapCodec<AdvancedMinecartChargerBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> {
        return instance.group(CodecFix.FABRIC_BLOCK_SETTINGS_CODEC.fieldOf("properties").forGetter(block -> {
            return (FabricBlockSettings)block.getSettings();
        })).apply(instance, AdvancedMinecartChargerBlock::new);
    });

    public static final DirectionProperty FACING = Properties.FACING;

    protected AdvancedMinecartChargerBlock(FabricBlockSettings props) {
        super(props);

        this.setDefaultState(this.getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos blockPos, BlockState state) {
        return new AdvancedMinecartChargerBlockEntity(blockPos, state);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public boolean hasComparatorOutput(BlockState state) {
        return true;
    }

    @Override
    public int getComparatorOutput(BlockState state, World level, BlockPos blockPos) {
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        if(!(blockEntity instanceof AdvancedMinecartChargerBlockEntity advancedMinecartChargerBlockEntity))
            return super.getComparatorOutput(state, level, blockPos);

        return advancedMinecartChargerBlockEntity.getRedstoneOutput();
    }

    @Override
    public ActionResult onUse(BlockState state, World level, BlockPos blockPos, PlayerEntity player, Hand handItem, BlockHitResult hit) {
        if(level.isClient())
            return ActionResult.SUCCESS;

        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        if(!(blockEntity instanceof AdvancedMinecartChargerBlockEntity))
            throw new IllegalStateException("Container is invalid");

        player.openHandledScreen((AdvancedMinecartChargerBlockEntity)blockEntity);

        return ActionResult.SUCCESS;
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return this.getDefaultState().with(FACING, context.getPlayerLookDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.rotate(mirror.getRotation(state.get(FACING)));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> stateBuilder) {
        stateBuilder.add(FACING);
    }
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World level, BlockState state, BlockEntityType<T> type) {
        return validateTicker(type, ModBlockEntities.ADVANCED_MINECART_CHARGER_ENTITY, AdvancedMinecartChargerBlockEntity::tick);
    }

    public static class Item extends BlockItem {
        public Item(Block block, FabricItemSettings props) {
            super(block, props);
        }

        @Override
        public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
            if(Screen.hasShiftDown()) {
                tooltip.add(Text.translatable("tooltip.energizedpower.transfer_rate.txt",
                                EnergyUtils.getEnergyWithPrefix(AdvancedMinecartChargerBlockEntity.MAX_TRANSFER)).
                        formatted(Formatting.GRAY));
            }else {
                tooltip.add(Text.translatable("tooltip.energizedpower.shift_details.txt").formatted(Formatting.YELLOW));
            }
        }
    }
}
