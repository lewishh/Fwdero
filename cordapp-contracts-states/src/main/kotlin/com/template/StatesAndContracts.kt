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

        when (command.value) {
            is Commands.Create -> {
                val output = tx.outputs.single().data as ForwardState
                val timestamp = Instant.now()

                requireThat {
                    "The initiator and the acceptor cannot be the same entity" using (output.initiator != output.acceptor)
                    "deliveryPrice must be non-negative" using (output.deliveryPrice.compareTo(BigDecimal.ZERO) > 0)
                    "Don't reissue existing / no inputs consumed" using tx.inputs.isEmpty() // working
                    "Initiator and acceptor" using (command.signers.toSet().size == 2)
                    "The initiator and acceptor must be signers" using (command.signers.containsAll(listOf(
                            output.initiator.owningKey, output.acceptor.owningKey)))
                    "There should be one output state of type ForwardState" using (tx.outputs.size == 1)
                }
            }

            is Commands.SettlePhysical -> {
                val inputs = tx.inputsOfType<ForwardState>()
                val input = inputs.single()
                val timestamp = Instant.now()

                requireThat {
                    "Must have matured" using (timestamp >= input.settlementTimestamp)
                    "An ForwardState input is consumed" using (tx.inputsOfType<ForwardState>().size == 1)
                    "No other inputs are consumed" using (tx.inputs.size == 1)
                    "The initiator must be signer." using (command.signers.contains(input.initiator.owningKey))
//                    "Destroy contract on completion" using (tx.outputs.isEmpty())
                    "An ForwardState output is created" using (tx.outputsOfType<ForwardState>().size == 1)
                    "No other states are created" using (tx.outputs.size == 1)
                }
            }

            is Commands.SettleCash -> {
                val out = tx.outputs.single().data as ForwardState
                val timestamp = Instant.now()

                requireThat {
                    "Must have matured" using (timestamp >= out.settlementTimestamp)
                    "There should be one output state of type ForwardState." using (tx.outputs.size == 1)
                }
            }

            else -> throw IllegalArgumentException("Command doesn't exist")
        }
    }

    // Abstract methods, cannot store state
    interface Commands: CommandData {
        class Create: TypeOnlyCommandData(), Commands
        class SettlePhysical: TypeOnlyCommandData(), Commands
        class SettleCash: TypeOnlyCommandData(), Commands
    }

    class OracleCommand(val spotPrice: SpotPrice) : CommandData
}

// *********
// * State *
// *********
data class ForwardState(val initiator: Party, val acceptor: Party, val instrument: String, val instrumentQuantity: BigDecimal, val deliveryPrice: BigDecimal,
                        val settlementTimestamp: Instant, val settlementType: String, val position: String) : SchedulableState {

    override val participants get() = listOf(initiator, acceptor) // creation/consumption of this state

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
        fun calculateCash(forwardState: ForwardState, oracleResults: SpotPrice): String {
            if (forwardState.deliveryPrice == oracleResults.value) {
                return "No money owed"
            } else if (forwardState.deliveryPrice < oracleResults.value) {
                val difference = (oracleResults.value - forwardState.deliveryPrice) * forwardState.instrumentQuantity
                return "Seller owes Buyer $difference"
            } else if (forwardState.deliveryPrice > oracleResults.value) {
                val difference = (forwardState.deliveryPrice - oracleResults.value) * forwardState.instrumentQuantity
                return "Buyer owes Seller $difference"
            }

            return "Not a cash settlement"
        }
    }
}