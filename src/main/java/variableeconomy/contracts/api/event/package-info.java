/**
 * Events the contracts add-on posts on {@link net.neoforged.neoforge.common.NeoForge#EVENT_BUS}, for the contract board: a contract claimed, delivered, completed, abandoned or expired.
 * <ul>
 *   <li>Every event is posted <em>after</em> its change is complete: money has moved, items have moved and the saved
 *       data is updated. When one action changes several things, its events are posted together after the last
 *       change, never in the middle.</li>
 *   <li>No event is cancellable. To stop an item being traded, lock it with core's
 *       {@link variableeconomy.core.api.ItemGate}.</li>
 *   <li>Events are posted only for actions that succeeded, and always on the server thread.</li>
 *   <li>A listener that throws is logged and ignored, so it can't undo or interrupt the add-on's work. Listeners
 *       after it on the same event may then not run.</li>
 * </ul>
 * Listen as with any NeoForge event, for example {@code NeoForge.EVENT_BUS.addListener((ContractEvent event) -> ...)}. The
 * add-on is optional, so check {@code ModList.get().isLoaded("variable_economy_contracts")} first and keep the listener in a class that
 * loads only then.
 * <p>
 * <b>Stability.</b> Experimental until 1.0. Changed in 0.6.0 besides the package: every event names its contract
 * definition ({@code getDefinition()}), and {@code Abandoned} and {@code Expired} report what was paid and how much of
 * the deposit came back and was lost (see the changelog in {@code docs/api.md}).
 */
@ApiStatus.Experimental
package variableeconomy.contracts.api.event;

import org.jetbrains.annotations.ApiStatus;
