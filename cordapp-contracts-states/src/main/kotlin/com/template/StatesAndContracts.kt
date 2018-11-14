package com.template

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
        val command = tx.commands.requireSingleCommand<Commands>()
        val out = tx.outputs.single().data as ForwardState

        when (command.value) {
            is Commands.Create -> {
                val timestamp = Instant.now()

                requireThat {
                    "The initiator and the acceptor cannot be the same entity." using (out.initiator != out.acceptor)
                    "deliveryPrice must be non-negative" using (out.deliveryPrice.compareTo(BigDecimal.ZERO) > 0)
                    "Settlement data not in the past" using (timestamp < out.settlementTimestamp)
                    "Don't reissue existing / no inputs consumed" using tx.inputs.isEmpty()
                    "There must be two signers." using (command.signers.toSet().size == 2)
                    "The initiator and acceptor must be signers." using (command.signers.containsAll(listOf(
                            out.acceptor.owningKey, out.initiator.owningKey)))
                    "There should be one output state of type ForwardState." using (tx.outputs.size == 1)
                }
            }

            is Commands.Settle -> {
                val timestamp = Instant.now()

                requireThat {
                    "Must have matured" using (timestamp >= out.settlementTimestamp)
                    "The initiator and acceptor must be signers." using (command.signers.containsAll(listOf(
                            out.acceptor.owningKey, out.initiator.owningKey)))
                    "Must destroy the contract after" using tx.outputs.isEmpty()
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