package variableeconomy.contracts.network;

import variableeconomy.contracts.ContractSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import variableeconomy.contracts.menu.ContractBoardMenu;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.CoreApi;
import variableeconomy.contracts.data.ContractBoard;
import variableeconomy.contracts.service.ContractService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server side of the contract board screen: keeps every open board up to date, and handles its requests. Requests
 * from a player without the board open are stale or forged, and ignored. Server thread only.
 */
public final class ContractSync {
    /** How often to re-check viewers' balance and held items for changes made elsewhere (pickups, the bank). */
    private static final int STATE_INTERVAL_TICKS = 20;

    private static final Map<UUID, ContractPayloads.ContractState> lastSentState = new HashMap<>();

    private ContractSync() {
    }

    public static List<ServerPlayer> viewers(MinecraftServer server) {
        return server.getPlayerList().getPlayers().stream()
                .filter(p -> p.containerMenu instanceof ContractBoardMenu)
                .toList();
    }

    /**
     * Whether the player has the board open and may act on it. A dead player may not: vanilla keeps the screen open
     * through death, until they respawn.
     */
    private static boolean viewing(ServerPlayer player) {
        return player.containerMenu instanceof ContractBoardMenu menu && menu.stillValid(player)
                && player.isAlive() && !player.isRemoved();
    }

    /** Call right after the board screen opens. */
    public static void sendFullState(ServerPlayer player) {
        // A newly opened screen starts empty, so resend even if nothing changed since the last send.
        lastSentState.remove(player.getUUID());
        sendContracts(player);
        sendState(player);
    }

    /** Sends a viewer their balance and held counts, but only if they changed since the last send. */
    public static void sendState(ServerPlayer player) {
        ContractPayloads.ContractState state = stateFor(player);
        if (!state.equals(lastSentState.get(player.getUUID()))) {
            lastSentState.put(player.getUUID(), state);
            PacketDistributor.sendToPlayer(player, state);
        }
    }

    /** The player's balance, and how many plain copies of each item on the board their main inventory holds. */
    private static ContractPayloads.ContractState stateFor(ServerPlayer player) {
        Set<ResourceLocation> wanted = new HashSet<>();
        ContractBoard.get(player.server).contracts().forEach(c -> wanted.add(c.item()));
        Map<ResourceLocation, Integer> held = new HashMap<>();
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (wanted.contains(id) && CoreApi.isPlain(stack, item)) {
                held.merge(id, stack.getCount(), Integer::sum);
            }
        }
        long balance = CoreApi.money().balance(player.server, CoreApi.accounts().active(player)).orElse(0L);
        return new ContractPayloads.ContractState(balance, held, ContractSettings.maxQuantityPerRequest(), ContractSettings.contractsEnabled(),
                ContractSettings.partialAllowed());
    }

    /** Every tick: forgets players who closed the screen, and refreshes the rest once a second. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % STATE_INTERVAL_TICKS != 0) {
            return;
        }
        List<ServerPlayer> viewers = viewers(server);
        Set<UUID> open = new HashSet<>();
        viewers.forEach(v -> open.add(v.getUUID()));
        lastSentState.keySet().retainAll(open);
        viewers.forEach(ContractSync::sendState);
    }

    /** After an account's balance changed: viewers who use that account see it at once. */
    public static void balanceChanged(MinecraftServer server, AccountId account) {
        for (ServerPlayer viewer : viewers(server)) {
            if (CoreApi.accounts().active(viewer).equals(account)) {
                sendState(viewer);
            }
        }
    }

    /** After locks changed for a player, or anyone: which contracts they see may have too. */
    public static void locksChanged(MinecraftServer server, Optional<ServerPlayer> player) {
        for (ServerPlayer viewer : viewers(server)) {
            if (player.isEmpty() || player.get() == viewer) {
                sendContracts(viewer);
            }
        }
    }

    // ---- Requests --------------------------------------------------------------------------------------------

    /** Claim, deliver or abandon from the open board. */
    public static void handleContractRequest(ContractPayloads.ContractRequest request, ServerPlayer player) {
        if (!viewing(player)) {
            return;
        }
        ContractService.Outcome outcome = switch (request.action()) {
            case CLAIM -> ContractService.claim(player, request.id());
            case DELIVER -> ContractService.deliver(player, request.id(), request.quantity());
            case ABANDON -> ContractService.abandon(player, request.id());
        };
        PacketDistributor.sendToPlayer(player, new ContractPayloads.ContractOutcome(outcome.action(), outcome.item(),
                outcome.result(), outcome.quantity(), outcome.amount()));
        if (outcome.ok()) {
            broadcastContracts(player.server);
        } else {
            sendContracts(player);
        }
        sendState(player);
    }

    // ---- Sending ---------------------------------------------------------------------------------------------

    /** After the board changed: everyone with it open gets it as they see it, with their counts for its items. */
    public static void broadcastContracts(MinecraftServer server) {
        viewers(server).forEach(player -> {
            sendContracts(player);
            sendState(player);
        });
    }

    /** {@code text} cut to the packet's limit, so an unusually long name can't make the board fail to send. */
    static String fit(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    public static void sendContracts(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, contractsFor(player));
    }

    /** The board as {@code player} sees it. */
    public static ContractPayloads.Contracts contractsFor(ServerPlayer player) {
        ContractBoard board = ContractBoard.get(player.server);
        List<ContractPayloads.ContractLine> lines = ContractService.view(player, board).stream()
                .map(view -> new ContractPayloads.ContractLine(view.contract().id(), view.contract().item(), view.contract().units(),
                        view.contract().delivered(), view.reward(), view.contract().deposit(), view.contract().status(), view.mine(),
                        fit(view.holderName(), ContractPayloads.MAX_HOLDER_LENGTH), view.daysLeft(), view.abandonRefund(),
                        view.itemLocked(), view.missingStage().map(stage -> fit(stage, ContractPayloads.MAX_STAGE_LENGTH)).orElse("")))
                .toList();
        return new ContractPayloads.Contracts(lines, board.daysLeft(), board.claimedBy(player.getUUID()), ContractSettings.maxClaims());
    }
}
