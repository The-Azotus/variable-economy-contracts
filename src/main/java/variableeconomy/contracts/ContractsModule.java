package variableeconomy.contracts;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import variableeconomy.contracts.block.ContractBlocks;
import variableeconomy.contracts.command.ContractCommands;
import variableeconomy.contracts.compat.MarketBridge;
import variableeconomy.contracts.service.ContractDiagnostics;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import variableeconomy.contracts.definition.ContractDefinitions;
import variableeconomy.contracts.menu.ContractBoardMenu;
import variableeconomy.contracts.network.ContractNetwork;
import variableeconomy.contracts.network.ContractSync;
import variableeconomy.contracts.service.ContractService;
import variableeconomy.core.api.event.BalanceChangedEvent;
import variableeconomy.core.api.event.ItemGateChangedEvent;

/**
 * Contracts: a shared board of delivery contracts with deposits. Registers its block, menu, packets, commands and
 * server events.
 */
@Mod(ContractsModule.MOD_ID)
public final class ContractsModule {
    /** The contract board's namespace for its block, menu, packets and saved data. */
    public static final String MOD_ID = "variable_economy_contracts";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** The mod's entry point: registers its server config, then its blocks, menus, packets, commands and server events. */
    public ContractsModule(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, ContractsConfig.SPEC, ContractsConfig.FILE);
        ContractsConfig.install();
        // Deposits held by claimed contracts count toward their owners' worth, and keep their accounts from being deleted.
        ContractService.registerNetWorth();
        ContractBoardMenu.MENUS.register(modBus);
        ContractBlocks.register(modBus);
        modBus.addListener(ContractNetwork::register);

        // Contract definitions load with the datapacks, at server start and on /reload.
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(ContractDefinitions.INSTANCE));
        // The definitions' warnings, once the market's catalog has loaded (with the market) or the datapacks have (without).
        if (MarketBridge.available()) {
            MarketBridge.onCatalogReloaded(ContractDiagnostics::log);
        } else {
            NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> ContractDiagnostics.log(event.getServer()));
            NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> {
                if (event.getPlayer() == null) {
                    ContractDiagnostics.log(event.getPlayerList().getServer());
                }
            });
        }
        // What a player has unlocked decides which contracts they're shown.
        NeoForge.EVENT_BUS.addListener((ItemGateChangedEvent event) -> ContractSync.locksChanged(event.getServer(), event.getPlayer()));
        // An open board shows a new balance straight away, whatever changed it.
        NeoForge.EVENT_BUS.addListener((BalanceChangedEvent event) -> ContractSync.balanceChanged(event.getServer(), event.getAccount()));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> ContractCommands.register(event.getDispatcher(), event.getBuildContext()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            if (ContractService.tick(event.getServer())) {
                ContractSync.broadcastContracts(event.getServer());
            }
            ContractSync.tick(event.getServer());
        });
        // How a held contract ended while its holder was away.
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ContractService.loggedIn(player);
            }
        });
    }
}
