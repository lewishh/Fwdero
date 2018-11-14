package com.template

import net.corda.core.contracts.*
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import java.lang.IllegalArgumentException
import java.math.BigDecimal
import java.time.Instant

// *****************
// * Contract Code *
// *****************
// This is used to identify our contract when building a transaction
val FORWARD_CONTRACT_ID = "com.template.ForwardContract"

class ForwardContract : Contract {
    // Our Create command.
    interface Commands : CommandData {
        class Settle : TypeOnlyCommandData(), Commands
        class Create : TypeOnlyCommandData(), Commands
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        val out = tx.outputsOfType<ForwardState>().single()

        when (command.value) {
            is Commands.Settle -> {
                val timestamp = Instant.now()

                requireThat {
                    "Must have matured" using (timestamp >= out.settlementTimestamp)
//                    "Must destroy the contract after" using tx.outputs.isEmpty()
                    "The initiator and acceptor must be signers." using (command.signers.containsAll(listOf(
                            out.acceptor.owningKey, out.initiator.owningKey)))
                }
            }

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

            else -> throw IllegalArgumentException("Unrecognised command")
        }
    }
}

// *********
// * State *
// *********
data class ForwardState(val initiator: Party, val acceptor: Party, val instrument: String, val instrumentQuantity: BigDecimal, val deliveryPrice: BigDecimal,
                        val settlementTimestamp: Instant, val settlementType: String, val position: String) : ContractState {
    override val participants get() = listOf(initiator, acceptor)
}