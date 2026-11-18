package variableeconomy.contracts.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import variableeconomy.contracts.client.ClientContractState;

/** The contract board's packets. */
public final class ContractNetwork {
    private static final String PROTOCOL_VERSION = "1";

    private ContractNetwork() {
    }

    /**
     * Handlers run on the main thread (the registrar default), which the contract service requires. The clientbound
     * handlers are lambdas rather than method references so a dedicated server never loads the client classes.
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(ContractPayloads.ContractRequest.TYPE, ContractPayloads.ContractRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                ContractSync.handleContractRequest(payload, player);
            }
        });
        registrar.playToClient(ContractPayloads.ContractState.TYPE, ContractPayloads.ContractState.CODEC,
                (payload, context) -> ClientContractState.applyState(payload));
        registrar.playToClient(ContractPayloads.Contracts.TYPE, ContractPayloads.Contracts.CODEC,
                (payload, context) -> ClientContractState.applyContracts(payload));
        registrar.playToClient(ContractPayloads.ContractOutcome.TYPE, ContractPayloads.ContractOutcome.CODEC,
                (payload, context) -> ClientContractState.applyOutcome(payload));
    }
}
