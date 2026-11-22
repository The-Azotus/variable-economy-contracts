package variableeconomy.contracts.network;

import variableeconomy.contracts.ContractsModule;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import variableeconomy.contracts.data.ContractBoard;
import variableeconomy.contracts.service.ContractService;
import variableeconomy.contracts.service.ContractResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Every packet the contract board sends. Rewards, deposits and balances are in bits. */
public final class ContractPayloads {
    /** The longest holder name a board line carries: a player name's limit. */
    public static final int MAX_HOLDER_LENGTH = 16;
    /** The longest missing-stage name a board line carries. */
    public static final int MAX_STAGE_LENGTH = 256;

    private ContractPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ContractsModule.MOD_ID, path));
    }

    private static final StreamCodec<ByteBuf, ContractResult> RESULT_CODEC =
            ByteBufCodecs.VAR_INT.map(i -> ContractResult.values()[i], ContractResult::ordinal);

    /**
     * Server to client: the balance of the account the player trades with, how many plain copies they hold of each
     * item on the board, the largest delivery one request may make, whether contracts are on, and whether deliveries
     * may come in batches. The client can't work the counts out itself: vanilla doesn't sync the inventory while a
     * slotless menu is open.
     */
    public record ContractState(long balance, Map<ResourceLocation, Integer> held, int maxQuantity, boolean enabled,
                                boolean partialAllowed) implements CustomPacketPayload {
        public static final Type<ContractState> TYPE = payloadType("contract_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, ContractState> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, ContractState::balance,
                ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.VAR_INT), ContractState::held,
                ByteBufCodecs.VAR_INT, ContractState::maxQuantity,
                ByteBufCodecs.BOOL, ContractState::enabled,
                ByteBufCodecs.BOOL, ContractState::partialAllowed,
                ContractState::new);

        @Override
        public Type<ContractState> type() {
            return TYPE;
        }
    }

    private static final StreamCodec<ByteBuf, ContractService.Action> ACTION_CODEC =
            ByteBufCodecs.idMapper(i -> ContractService.Action.values()[i], ContractService.Action::ordinal);

    /** Client to server: claim, deliver {@code quantity} units to, or abandon a contract. */
    public record ContractRequest(ContractService.Action action, int id, int quantity) implements CustomPacketPayload {
        public static final Type<ContractRequest> TYPE = payloadType("contract_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ContractRequest> CODEC = StreamCodec.composite(
                ACTION_CODEC, ContractRequest::action,
                ByteBufCodecs.VAR_INT, ContractRequest::id,
                ByteBufCodecs.VAR_INT, ContractRequest::quantity,
                ContractRequest::new);

        @Override
        public Type<ContractRequest> type() {
            return TYPE;
        }
    }

    /**
     * One contract as the receiving player sees it. {@code reward} is what completing it pays now; {@code holder} is
     * the name of whoever claimed it ("" if nobody), and {@code mine} whether that's the receiving player. For a
     * contract they hold, {@code daysLeft} is the days until its time runs out (otherwise -1), and {@code
     * abandonRefund} what abandoning it would pay back now. A contract they can't claim has {@code itemLocked} set
     * (the item is locked for them) or names the stage they lack in {@code missingStage} ("" if none).
     */
    public record ContractLine(int id, ResourceLocation item, int units, int delivered, long reward, long deposit,
                               ContractBoard.Status status, boolean mine, String holder, long daysLeft, long abandonRefund,
                               boolean itemLocked, String missingStage) {
        private static final StreamCodec<ByteBuf, ContractBoard.Status> STATUS_CODEC =
                ByteBufCodecs.idMapper(i -> ContractBoard.Status.values()[i], ContractBoard.Status::ordinal);
        static final StreamCodec<ByteBuf, ContractLine> CODEC = StreamCodec.of(
                (buf, line) -> {
                    ByteBufCodecs.VAR_INT.encode(buf, line.id());
                    ResourceLocation.STREAM_CODEC.encode(buf, line.item());
                    ByteBufCodecs.VAR_INT.encode(buf, line.units());
                    ByteBufCodecs.VAR_INT.encode(buf, line.delivered());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.reward());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.deposit());
                    STATUS_CODEC.encode(buf, line.status());
                    buf.writeBoolean(line.mine());
                    ByteBufCodecs.stringUtf8(MAX_HOLDER_LENGTH).encode(buf, line.holder());
                    buf.writeLong(line.daysLeft());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.abandonRefund());
                    buf.writeBoolean(line.itemLocked());
                    ByteBufCodecs.stringUtf8(MAX_STAGE_LENGTH).encode(buf, line.missingStage());
                },
                buf -> new ContractLine(ByteBufCodecs.VAR_INT.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf),
                        ByteBufCodecs.VAR_LONG.decode(buf), STATUS_CODEC.decode(buf), buf.readBoolean(),
                        ByteBufCodecs.stringUtf8(MAX_HOLDER_LENGTH).decode(buf), buf.readLong(), ByteBufCodecs.VAR_LONG.decode(buf), buf.readBoolean(),
                        ByteBufCodecs.stringUtf8(MAX_STAGE_LENGTH).decode(buf)));

        public int remaining() {
            return units - delivered;
        }

        /** Whether the receiving player can't claim it: shown greyed out. */
        public boolean locked() {
            return itemLocked || !missingStage.isEmpty();
        }
    }

    /**
     * Server to client: the contract board as this player sees it, how many in-game days until it's replaced, and
     * how many contracts the player holds out of the most they may.
     */
    public record Contracts(List<ContractLine> lines, long daysLeft, int held, int maxHeld) implements CustomPacketPayload {
        public static final Type<Contracts> TYPE = payloadType("contracts");
        public static final StreamCodec<RegistryFriendlyByteBuf, Contracts> CODEC = StreamCodec.composite(
                // Held contracts stay through a refresh, so a board can hold far more than board.size lines.
                ContractLine.CODEC.apply(ByteBufCodecs.list(65_536)), Contracts::lines,
                ByteBufCodecs.VAR_LONG, Contracts::daysLeft,
                ByteBufCodecs.VAR_INT, Contracts::held,
                ByteBufCodecs.VAR_INT, Contracts::maxHeld,
                Contracts::new);

        @Override
        public Type<Contracts> type() {
            return TYPE;
        }
    }

    /** Server to client: how this player's last contract request went. */
    public record ContractOutcome(ContractService.Action action, ResourceLocation item, ContractResult result, int quantity,
                                  long amount) implements CustomPacketPayload {
        public static final Type<ContractOutcome> TYPE = payloadType("contract_outcome");
        public static final StreamCodec<RegistryFriendlyByteBuf, ContractOutcome> CODEC = StreamCodec.composite(
                ACTION_CODEC, ContractOutcome::action,
                ResourceLocation.STREAM_CODEC, ContractOutcome::item,
                RESULT_CODEC, ContractOutcome::result,
                ByteBufCodecs.VAR_INT, ContractOutcome::quantity,
                ByteBufCodecs.VAR_LONG, ContractOutcome::amount,
                ContractOutcome::new);

        @Override
        public Type<ContractOutcome> type() {
            return TYPE;
        }
    }

}
