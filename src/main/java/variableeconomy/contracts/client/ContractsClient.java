package variableeconomy.contracts.client;

import variableeconomy.contracts.ContractsModule;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import variableeconomy.contracts.menu.ContractBoardMenu;

/** The contract board's client side: its screen. Loaded only on the client. */
@Mod(value = ContractsModule.MOD_ID, dist = Dist.CLIENT)
public final class ContractsClient {
    /** The mod's client entry point, constructed on the client only. */
    public ContractsClient(IEventBus modBus, ModContainer container) {
        modBus.addListener((RegisterMenuScreensEvent event) -> event.register(ContractBoardMenu.TYPE.get(), ContractBoardScreen::new));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> ClientContractState.clear());
    }
}
