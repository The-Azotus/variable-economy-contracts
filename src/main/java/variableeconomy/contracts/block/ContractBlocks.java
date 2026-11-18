package variableeconomy.contracts.block;

import variableeconomy.contracts.ContractsModule;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The Contract Board block and its item. */
public final class ContractBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ContractsModule.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ContractsModule.MOD_ID);

    public static final DeferredBlock<ContractBoardBlock> CONTRACT_BOARD = BLOCKS.register("contract_board",
            () -> new ContractBoardBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5f)
                    .sound(SoundType.WOOD)));
    public static final DeferredItem<BlockItem> CONTRACT_BOARD_ITEM = ITEMS.registerSimpleBlockItem(CONTRACT_BOARD);

    private ContractBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                event.accept(CONTRACT_BOARD_ITEM);
            }
        });
    }
}
