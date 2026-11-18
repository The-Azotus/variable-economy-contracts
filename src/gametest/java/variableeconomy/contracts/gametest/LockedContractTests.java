package variableeconomy.contracts.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import variableeconomy.contracts.ContractsModule;
import variableeconomy.contracts.data.ContractBoard;
import variableeconomy.contracts.definition.ContractDefinition;
import variableeconomy.contracts.definition.ContractDefinitions;
import variableeconomy.contracts.service.ContractResult;
import variableeconomy.contracts.service.ContractService;
import variableeconomy.core.service.ItemGateService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static variableeconomy.contracts.gametest.ContractTestSupport.*;
import static variableeconomy.contracts.gametest.CoreTestSupport.*;

/** Contracts a player can't claim yet, shown to them greyed out: the definition's stage they lack, or a locked item. */
@GameTestHolder(ContractsModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LockedContractTests {
    private LockedContractTests() {
    }

    @GameTest(template = EMPTY)
    public static void aMissingStageLocksAContractButNotOneAlreadyHeld(GameTestHelper helper) {
        CoreFixture f = new CoreFixture(helper);
        f.deposit(1000);
        ContractDefinition needsStage = new ContractDefinition(rl("needs_stage"), new ContractDefinition.ItemTarget(Items.BARRIER), 4, 4,
                ContractDefinition.Payout.fixed(BigDecimal.ONE), 1, Optional.of("iron_age"), "test");
        ContractBoard board = board(helper, b -> List.of(contract(b, needsStage.id()), contract(b, needsStage.id()),
                contract(b, rl("no_such_definition"))));
        List<Integer> ids = board.contracts().stream().map(ContractBoard.Contract::id).toList();
        Map<ResourceLocation, ContractDefinition> before = ContractDefinitions.INSTANCE.replaceForTesting(Map.of(needsStage.id(), needsStage));
        boolean[] hasStage = {false};
        ItemGateService.setTestStages((player, stage) -> player != f.player || hasStage[0]);
        try {
            List<ContractService.View> views = ContractService.view(f.player, board);
            helper.assertValueEqual(views.size(), 3, "every contract is shown");
            helper.assertValueEqual(views.get(0).missingStage(), Optional.of("iron_age"), "the missing stage is named");
            helper.assertTrue(views.get(0).locked() && !views.get(0).itemLocked(), "locked by the stage, not the item");
            helper.assertTrue(!views.get(2).locked(), "a contract whose definition is gone needs no stage");
            helper.assertValueEqual(ContractService.claim(f.player, board, ids.get(0)).result(), ContractResult.LOCKED, "can't be claimed");
            helper.assertValueEqual(f.balance(), 1000, "and nothing was taken");

            hasStage[0] = true;
            helper.assertValueEqual(ContractService.claim(f.player, board, ids.get(0)).result(), ContractResult.OK, "claimed with the stage");
            hasStage[0] = false;
            helper.assertTrue(!ContractService.view(f.player, board).get(0).locked(), "held, it isn't marked locked when the stage is lost");
            f.giveMany(Items.BARRIER, 4);
            helper.assertValueEqual(ContractService.deliver(f.player, board, ids.get(0), 4).result(), ContractResult.OK, "and can be finished");
        } finally {
            ItemGateService.setTestStages(null);
            ContractDefinitions.INSTANCE.replaceForTesting(before);
        }
        f.account().deduct(f.balance());
        helper.succeed();
    }

    private static ContractBoard.Contract contract(ContractBoard board, ResourceLocation definition) {
        return new ContractBoard.Contract(board.takeId(), definition, id(Items.BARRIER), 4, BigDecimal.ONE, 4, 0, 0, Optional.empty(),
                Optional.empty(), -1, ContractBoard.Status.OPEN);
    }
}
