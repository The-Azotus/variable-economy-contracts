package variableeconomy.contracts.client;

import variableeconomy.contracts.data.ContractBoard;
import variableeconomy.contracts.network.ContractPayloads;
import variableeconomy.contracts.service.ContractService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static variableeconomy.contracts.client.ContractBoardScreen.*;

/**
 * The contract board screen's content: the board. The selected contract can be claimed, delivered to and abandoned
 * with the quantity controls and two buttons.
 */
final class ContractsView {
    private final ContractBoardScreen screen;
    /** Claim or deliver, and abandon. */
    private Button contractButton;
    private Button abandonButton;
    /** The selected contract's ID, if any. */
    @Nullable
    private Integer selectedContract;
    /** Abandoning loses the deposit, so it takes a second click; this is the contract the first click was for. */
    @Nullable
    private Integer abandonArmed;

    ContractsView(ContractBoardScreen screen) {
        this.screen = screen;
    }

    /** Claim or deliver, and abandon. */
    void init() {
        int[] first = screen.buttonBounds(true);
        int[] second = screen.buttonBounds(false);
        contractButton = screen.add(Button.builder(Component.empty(), b -> contractAction())
                .bounds(first[0], first[1], first[2], 18).build());
        abandonButton = screen.add(Button.builder(Component.translatable("screen.variable_economy_contracts.contracts.abandon"), b -> abandon())
                .bounds(second[0], second[1], second[2], 18).build());
    }

    int listSize() {
        return contractLines().size();
    }

    /** "All": every copy held, up to what the contract still wants and the delivery limit. */
    void fillAll() {
        fillDeliverAll();
    }

    boolean rowClicked(int index) {
        screen.minecraft().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        ContractPayloads.ContractLine line = contractLines().get(index);
        if (!Integer.valueOf(line.id()).equals(selectedContract)) {
            selectedContract = line.id();
            abandonArmed = null;
            if (deliverable(line)) {
                // Start from what can be delivered now.
                startDeliveryQuantity(line);
            }
        }
        screen.setFocused(null);
        return true;
    }

    void renderTooltip(GuiGraphics graphics, int index, int mouseX, int mouseY) {
        if (index >= 0) {
            graphics.renderTooltip(screen.font(), contractTooltip(contractLines().get(index)), Optional.empty(), mouseX, mouseY);
        }
    }

    void renderList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        renderContractList(graphics, x, y, mouseX, mouseY);
    }

    void renderPanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        renderContractPanel(graphics, x, y);
        if (selectedContractLine() != null) {
            screen.renderQuantityNote(graphics, x + screen.noteX, y + screen.noteY);
        }
    }

    /**
     * The most one delivery to the selected contract may hold: the server's per-request limit, except that with
     * batches off the one delivery that finishes a contract may be larger (as the server allows), so it is what the
     * contract still wants.
     */
    int deliveryLimit() {
        ContractPayloads.ContractLine line = selectedContractLine();
        return line != null ? deliveryLimit(line) : ClientContractState.maxQuantity();
    }

    private static int deliveryLimit(ContractPayloads.ContractLine line) {
        return ClientContractState.partialAllowed() ? ClientContractState.maxQuantity() : Math.max(1, line.remaining());
    }

    private static List<ContractPayloads.ContractLine> contractLines() {
        ContractPayloads.Contracts board = ClientContractState.contracts();
        return board == null ? List.of() : board.lines();
    }

    @Nullable
    private ContractPayloads.ContractLine selectedContractLine() {
        if (selectedContract == null) {
            return null;
        }
        for (ContractPayloads.ContractLine line : contractLines()) {
            if (line.id() == selectedContract) {
                return line;
            }
        }
        return null;
    }

    private static boolean deliverable(ContractPayloads.ContractLine line) {
        return line.mine() && line.status() == ContractBoard.Status.CLAIMED;
    }

    /** Every copy held, up to what the contract still wants and the delivery limit. */
    private void fillDeliverAll() {
        ContractPayloads.ContractLine line = selectedContractLine();
        if (line == null || !deliverable(line)) {
            return;
        }
        int held = ClientContractState.held(line.item());
        if (held == 0) {
            screen.quantityNote = Component.translatable("screen.variable_economy_contracts.note.none_held");
            screen.quantityNoteFor = screen.quantityValue();
            return;
        }
        screen.setQuantity(Math.min(Math.min(held, line.remaining()), deliveryLimit(line)), Component.empty());
    }

    /** Client-side hints only; the server re-checks everything and replies with the real outcome. */
    void refreshButtons() {
        ContractPayloads.ContractLine line = selectedContractLine();
        ContractPayloads.Contracts board = ClientContractState.contracts();
        if (screen.quantityValue() != screen.quantityNoteFor) {
            screen.quantityNote = Component.empty();
        }
        boolean selected = line != null && board != null;
        screen.showQuantityControls(selected);
        contractButton.visible = selected;
        abandonButton.visible = selected;
        if (line == null || board == null) {
            contractButton.setMessage(Component.translatable("screen.variable_economy_contracts.contracts.claim"));
            contractButton.active = false;
            contractButton.setTooltip(null);
            abandonButton.active = false;
            abandonButton.setTooltip(null);
            abandonArmed = null;
            return;
        }
        if (deliverable(line)) {
            int qty = screen.quantityValue();
            contractButton.setMessage(Component.translatable("screen.variable_economy_contracts.contracts.deliver", PriceFormat.amount(qty)));
            Component problem = qty <= 0 ? Component.translatable("screen.variable_economy_contracts.result.invalid_quantity")
                    : qty > deliveryLimit(line) ? Component.translatable("screen.variable_economy_contracts.result.too_large")
                    : qty > line.remaining() ? Component.translatable("screen.variable_economy_contracts.result.contract_full")
                    : qty < line.remaining() && !ClientContractState.partialAllowed()
                    ? Component.translatable("screen.variable_economy_contracts.result.whole_delivery")
                    : ClientContractState.held(line.item()) < qty ? Component.translatable("screen.variable_economy_contracts.result.not_enough_items")
                    : null;
            contractButton.active = problem == null;
            contractButton.setTooltip(Tooltip.create(problem != null ? problem
                    : Component.translatable(qty == line.remaining() ? "screen.variable_economy_contracts.contracts.deliver.finishes"
                            : "screen.variable_economy_contracts.contracts.deliver.tooltip", PriceFormat.money(PriceFormat.amount(line.reward() + line.deposit())))));
            abandonButton.active = true;
            abandonButton.setMessage(Component.translatable(Integer.valueOf(line.id()).equals(abandonArmed)
                    ? "screen.variable_economy_contracts.contracts.abandon_confirm" : "screen.variable_economy_contracts.contracts.abandon"));
            abandonButton.setTooltip(Tooltip.create(Component.translatable("screen.variable_economy_contracts.contracts.abandon.tooltip",
                    PriceFormat.money(PriceFormat.amount(line.abandonRefund())), PriceFormat.money(PriceFormat.amount(line.deposit())))));
            return;
        }
        abandonArmed = null;
        abandonButton.active = false;
        abandonButton.setMessage(Component.translatable("screen.variable_economy_contracts.contracts.abandon"));
        abandonButton.setTooltip(null);
        contractButton.setMessage(line.status() == ContractBoard.Status.OPEN
                ? Component.translatable("screen.variable_economy_contracts.contracts.claim_for", PriceFormat.money(PriceFormat.amount(line.deposit())))
                : Component.translatable("screen.variable_economy_contracts.contracts.claim"));
        Component problem = !ClientContractState.enabled() ? Component.translatable("screen.variable_economy_contracts.contracts.turned_off")
                : line.locked() && line.status() == ContractBoard.Status.OPEN ? lockReason(line)
                : line.status() != ContractBoard.Status.OPEN ? Component.translatable("screen.variable_economy_contracts.result.contract_taken")
                : board.held() >= board.maxHeld() ? Component.translatable("screen.variable_economy_contracts.result.contract_limit")
                : !ClientContractState.looksAffordable(line.deposit()) ? Component.translatable("screen.variable_economy_contracts.result.no_funds")
                : null;
        contractButton.active = problem == null;
        contractButton.setTooltip(Tooltip.create(problem != null ? problem
                : Component.translatable("screen.variable_economy_contracts.contracts.claim.tooltip", PriceFormat.money(PriceFormat.amount(line.deposit())))));
    }

    private void contractAction() {
        ContractPayloads.ContractLine line = selectedContractLine();
        if (line == null) {
            return;
        }
        if (deliverable(line)) {
            int qty = screen.quantityValue();
            if (qty > 0) {
                PacketDistributor.sendToServer(new ContractPayloads.ContractRequest(ContractService.Action.DELIVER, line.id(), qty));
            }
        } else if (line.status() == ContractBoard.Status.OPEN) {
            PacketDistributor.sendToServer(new ContractPayloads.ContractRequest(ContractService.Action.CLAIM, line.id(), 0));
        }
    }

    private void abandon() {
        ContractPayloads.ContractLine line = selectedContractLine();
        if (line == null || !deliverable(line)) {
            return;
        }
        if (!Integer.valueOf(line.id()).equals(abandonArmed)) {
            abandonArmed = line.id();
            return;
        }
        abandonArmed = null;
        PacketDistributor.sendToServer(new ContractPayloads.ContractRequest(ContractService.Action.ABANDON, line.id(), 0));
    }

    private void startDeliveryQuantity(ContractPayloads.ContractLine line) {
        int held = ClientContractState.held(line.item());
        screen.setQuantity(Math.max(1, Math.min(Math.min(held, line.remaining()), deliveryLimit(line))), Component.empty());
    }

    /** The board: what each contract wants, what it pays, and who holds it; the days left and the player's count above. */
    private void renderContractList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        ContractPayloads.Contracts board = ClientContractState.contracts();
        List<ContractPayloads.ContractLine> lines = contractLines();
        int listRight = LIST_X + screen.listWidth;
        int statusRight = listRight - 2;
        int rewardRight = listRight - 66;
        if (board != null) {
            graphics.drawString(screen.font(), Component.translatable(board.daysLeft() == 1 ? "screen.variable_economy_contracts.contracts.title_one"
                    : "screen.variable_economy_contracts.contracts.title", board.daysLeft()), x + LIST_X + 4, y + HEADER_Y, TEXT_DIM, false);
            screen.drawRight(graphics, Component.translatable("screen.variable_economy_contracts.contracts.held", board.held(), board.maxHeld()),
                    x + statusRight, y + HEADER_Y, board.held() >= board.maxHeld() ? TEXT_MONEY : TEXT_DIM);
        }
        int hovered = screen.rowAt(mouseX, mouseY);
        for (int i = 0; i < screen.rows && screen.scroll + i < lines.size(); i++) {
            ContractPayloads.ContractLine line = lines.get(screen.scroll + i);
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            boolean selectedRow = Integer.valueOf(line.id()).equals(selectedContract);
            int background = selectedRow ? ROW_SELECTED : screen.scroll + i == hovered ? ROW_HOVER : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + listRight, rowY + ROW_HEIGHT, background);
            }
            boolean done = line.status() == ContractBoard.Status.COMPLETED || line.status() == ContractBoard.Status.ABANDONED
                    || line.locked() && line.status() == ContractBoard.Status.OPEN;
            graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(line.item())), x + LIST_X + 2, rowY + 1);
            int textY = rowY + 5;
            Component what = Component.translatable("screen.variable_economy_contracts.contracts.wants", PriceFormat.amount(line.units()),
                    BuiltInRegistries.ITEM.get(line.item()).getDescription());
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(what.getString(), rewardRight - 60 - LIST_X - 22),
                    x + LIST_X + 22, textY, done ? TEXT_DIM : TEXT, false);
            screen.drawRight(graphics, PriceFormat.money(PriceFormat.amount(line.reward())), x + rewardRight, textY, done ? TEXT_DIM : TEXT_MONEY);
            Component state;
            int color;
            switch (line.status()) {
                case OPEN -> {
                    state = Component.translatable(line.locked() ? "screen.variable_economy_contracts.contracts.locked"
                            : "screen.variable_economy_contracts.contracts.open");
                    color = line.locked() ? TEXT_OFF : TEXT_GOOD;
                }
                case CLAIMED -> {
                    state = line.mine()
                            ? Component.translatable("screen.variable_economy_contracts.contracts.progress_days", PriceFormat.count(line.delivered()),
                            PriceFormat.count(line.units()), line.daysLeft())
                            : Component.literal(line.holder());
                    color = line.mine() ? TEXT_MONEY : TEXT_DIM;
                }
                case COMPLETED -> {
                    state = Component.translatable("screen.variable_economy_contracts.contracts.done");
                    color = TEXT_OFF;
                }
                default -> {
                    state = Component.translatable("screen.variable_economy_contracts.contracts.given_up");
                    color = TEXT_OFF;
                }
            }
            screen.drawRight(graphics, Component.literal(screen.font().plainSubstrByWidth(state.getString(), 60)), x + statusRight, textY, color);
        }
        if (lines.isEmpty()) {
            graphics.drawCenteredString(screen.font(), Component.translatable(board == null ? "screen.variable_economy_contracts.trades.loading"
                            : !ClientContractState.enabled() ? "screen.variable_economy_contracts.contracts.turned_off" : "screen.variable_economy_contracts.contracts.none"),
                    x + LIST_X + screen.listWidth / 2, y + LIST_Y + 20, TEXT_DIM);
        }
    }

    /** The selected contract: what it pays, the deposit, progress, and how many the player holds; or how contracts work. */
    private void renderContractPanel(GuiGraphics graphics, int x, int y) {
        int left = x + screen.panelX;
        ContractPayloads.ContractLine line = selectedContractLine();
        if (line == null) {
            List<net.minecraft.util.FormattedCharSequence> help = screen.font().split(
                    Component.translatable(screen.wide ? "screen.variable_economy_contracts.contracts.help" : "screen.variable_economy_contracts.contracts.select_hint"),
                    screen.panelWidth);
            // The quantity controls and buttons are hidden until a contract is selected, so the help can use their room.
            int helpBottom = screen.wide ? screen.tradeY + 38 : screen.controlsY + 18;
            for (int i = 0; i < help.size() && screen.panelY + 4 + i * 10 <= helpBottom - 9; i++) {
                graphics.drawString(screen.font(), help.get(i), left, y + screen.panelY + 4 + i * 10, TEXT_DIM, false);
            }
            return;
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(line.item()));
        graphics.renderItem(stack, left, y + screen.panelY);
        int held = ClientContractState.held(line.item());
        if (!screen.wide) {
            Component summary = Component.translatable("screen.variable_economy_contracts.contracts.summary", stack.getHoverName(),
                    PriceFormat.money(PriceFormat.amount(line.reward())), PriceFormat.money(PriceFormat.amount(line.deposit())),
                    PriceFormat.count(line.delivered()), PriceFormat.count(line.units()));
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(summary.getString(), screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
            return;
        }
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(stack.getHoverName().getString(), screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
        int right = left + screen.panelWidth;
        int top = y + screen.infoY;
        screen.infoLine(graphics, "contracts.reward", PriceFormat.amount(line.reward()), TEXT_MONEY, left, right, top);
        screen.infoLine(graphics, "contracts.per_unit", PriceFormat.decimal(java.math.BigDecimal.valueOf(line.reward())
                .divide(java.math.BigDecimal.valueOf(line.units()), 2, RoundingMode.FLOOR)), TEXT_MONEY, left, right, top + INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "contracts.deposit", PriceFormat.amount(line.deposit()), TEXT, left, right, top + 2 * INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "contracts.progress", PriceFormat.count(line.delivered()) + "/" + PriceFormat.count(line.units()),
                line.mine() ? TEXT_MONEY : TEXT, left, right, top + 3 * INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "col.have", PriceFormat.amount(held), held > 0 ? TEXT : TEXT_DIM, left, right, top + 4 * INFO_LINE_HEIGHT);
        Component note = switch (line.status()) {
            case OPEN -> line.locked() ? lockReason(line) : Component.translatable("screen.variable_economy_contracts.contracts.note.open");
            case CLAIMED -> line.mine() ? Component.translatable(line.daysLeft() == 1 ? "screen.variable_economy_contracts.contracts.note.mine_one"
                    : "screen.variable_economy_contracts.contracts.note.mine", line.daysLeft())
                    : Component.translatable("screen.variable_economy_contracts.contracts.note.taken", line.holder());
            case COMPLETED -> Component.translatable("screen.variable_economy_contracts.contracts.note.done");
            case ABANDONED -> Component.translatable("screen.variable_economy_contracts.contracts.note.given_up");
        };
        List<net.minecraft.util.FormattedCharSequence> lines = screen.font().split(note, screen.panelWidth);
        int noteTop = screen.infoY + INFO_LINES * INFO_LINE_HEIGHT + 4;
        for (int i = 0; i < lines.size() && noteTop + i * 10 < screen.controlsY - 9; i++) {
            graphics.drawString(screen.font(), lines.get(i), left, y + noteTop + i * 10, TEXT_DIM, false);
        }
    }

    private List<Component> contractTooltip(ContractPayloads.ContractLine line) {
        List<Component> lines = new ArrayList<>(net.minecraft.client.gui.screens.Screen.getTooltipFromItem(screen.minecraft(), new ItemStack(BuiltInRegistries.ITEM.get(line.item()))));
        lines.add(Component.translatable("screen.variable_economy_contracts.contracts.tooltip.reward",
                PriceFormat.money(PriceFormat.amount(line.reward())), PriceFormat.money(PriceFormat.amount(line.deposit()))).withColor(TEXT_MONEY));
        if (line.status() == ContractBoard.Status.CLAIMED && !line.mine()) {
            lines.add(Component.translatable("screen.variable_economy_contracts.contracts.note.taken", line.holder()).withColor(TEXT_DIM));
        }
        if (line.locked() && line.status() == ContractBoard.Status.OPEN) {
            lines.add(lockReason(line).copy().withColor(TEXT_OFF));
        }
        return lines;
    }

    /** Why the player can't claim a contract: the item is locked for them, or they lack its stage. */
    private static Component lockReason(ContractPayloads.ContractLine line) {
        return line.itemLocked() ? Component.translatable("screen.variable_economy_contracts.contracts.locked.item")
                : Component.translatable("screen.variable_economy_contracts.contracts.locked.stage", line.missingStage());
    }
}
