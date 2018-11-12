package com.template

import com.template.ForwardState.Companion.calculateCash
import net.corda.core.contracts.*
import net.corda.core.flows.FlowLogicRefFactory
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import java.math.BigDecimal
import java.time.Instant
import com.template.base.SpotPrice

// *****************
// * Contract Code *
// *****************
// This is used to identify our contract when building a transaction
val FORWARD_CONTRACT_ID = "com.template.ForwardContract"

class ForwardContract : Contract {

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<CommandData>()

        when (command.value) {
            is Commands.Create -> {
                val out = tx.outputs.single().data as ForwardState

                requireThat {
                    "No inputs should be consumed when issuing the Forward." using (tx.inputs.isEmpty())
                    "There should be one output state of type ForwardState." using (tx.outputs.size == 1)
                    "The price must be non-negative." using (out.deliveryPrice.compareTo(BigDecimal.ZERO) > 0)
                    "The initiator and the acceptor cannot be the same entity." using (out.initiator != out.acceptor)

                    "There must be two signers." using (command.signers.toSet().size == 2)
                    "The initiator and acceptor must be signers." using (command.signers.containsAll(listOf(
                            out.acceptor.owningKey, out.initiator.owningKey)))
                }
            }

            is Commands.Settle -> {
                requireThat {
                    "One input state consumed" using (tx.inputs.size == 1)
                    "One output state" using (tx.outputs.size == 1)
//                    "Destroy after settlement" using (tx.outputs.isEmpty()) // Iffy

                    "A ForwardState is consumed" using (tx.inputsOfType<ForwardState>().size == 1)
                    "No other inputs are consumed" using (tx.inputs.size == 1)
                    "A new ForwardState is created" using (tx.outputsOfType<ForwardState>().size == 1)
                    "No other states are created" using (tx.outputs.size == 1)
                }
            }

            else -> throw IllegalArgumentException("Command doesn't exist")
        }
    }

    // Abstract methods, cannot store state
    interface Commands: CommandData {
        class Create: TypeOnlyCommandData(), Commands
        class Settle: TypeOnlyCommandData(), Commands
    }

    class OracleCommand(val spotPrice: SpotPrice) : CommandData
}

// *********
// * State *
// *********
data class ForwardState(val initiator: Party, val acceptor: Party, val instrument: String, val instrumentQuantity: BigDecimal, val deliveryPrice: BigDecimal,
                        val settlementTimestamp: Instant, val settlementType: String, val position: String) : SchedulableState {

    override val participants get() = listOf(initiator, acceptor)

    // Defines the scheduled activity to be conducted by the SchedulableState.
    override fun nextScheduledActivity(thisStateRef: StateRef, flowLogicRefFactory: FlowLogicRefFactory): ScheduledActivity? {
        if (settlementType == "cash") {
            return ScheduledActivity(flowLogicRefFactory.create("com.template.flows.SettleCashFlow", initiator, acceptor,
                    instrument, instrumentQuantity, deliveryPrice, settlementTimestamp, settlementType, position, thisStateRef, this), settlementTimestamp)
        } else if (settlementType == "physical") {
            return ScheduledActivity(flowLogicRefFactory.create("com.template.flows.SettlePhysicalFlow", initiator, acceptor,
                    instrument, instrumentQuantity, deliveryPrice, settlementTimestamp, settlementType, position, thisStateRef), settlementTimestamp)
        }

        return null
    }

    companion object {
        fun calculateCash(forwardState: ForwardState, oracle: SpotPrice): String {
            if (forwardState.deliveryPrice == oracle.value) {
                return "No money owed"
            } else if (forwardState.deliveryPrice < oracle.value) {
                val difference = (oracle.value - forwardState.deliveryPrice) * forwardState.instrumentQuantity
                return "Seller owes Buyer $difference"
            } else if (forwardState.deliveryPrice > oracle.value) {
                val difference = (forwardState.deliveryPrice - oracle.value) * forwardState.instrumentQuantity
                return "Buyer owes Seller $difference"
            }

            return "Not a cash settlement"
        }
    }
}