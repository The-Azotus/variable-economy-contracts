package variableeconomy.contracts.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.ModConfigSpec;
import variableeconomy.contracts.api.event.ContractEvent;
import variableeconomy.contracts.data.ContractBoard;

import java.util.List;
import java.util.function.Function;

import static variableeconomy.contracts.gametest.CoreTestSupport.listenFor;
import static variableeconomy.contracts.gametest.CoreTestSupport.recordEvents;

/** What the contract board's GameTests share: boards of their own, and the board's events. */
public final class ContractTestSupport {
    private ContractTestSupport() {
    }

    /** A board of its own holding these contracts, replaced in 5 days. */
    public static ContractBoard board(GameTestHelper helper, Function<ContractBoard, List<ContractBoard.Contract>> contracts) {
        ContractBoard board = new ContractBoard();
        board.observe(ContractBoard.worldDay(helper.getLevel().getServer()));
        board.replace(contracts.apply(board), 5);
        return board;
    }

    /** Runs {@code body} with a contracts config value set, then puts it back. */
    public static <T> void withSetting(ModConfigSpec.ConfigValue<T> setting, T value, Runnable body) {
        T before = setting.get();
        setting.set(value);
        try {
            body.run();
        } finally {
            setting.set(before);
        }
    }

    public static ContractBoard.Contract contract(ContractBoard board, int id) {
        return board.find(id).orElseThrow();
    }

    /** The board's events posted while {@code body} runs, in order (with any other module's its tests listen for). */
    public static List<Event> contractEventsDuring(Runnable body) {
        listenFor(ContractEvent.Claimed.class, ContractEvent.Delivered.class, ContractEvent.Completed.class,
                ContractEvent.Abandoned.class, ContractEvent.Expired.class);
        return recordEvents(body);
    }
}
