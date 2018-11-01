package com.template

import net.corda.core.contracts.*
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import java.math.BigDecimal
import java.time.Instant

// *****************
// * Contract Code *
// *****************
// This is used to identify our contract when building a transaction
val FORWARD_CONTRACT_ID = "com.template.ForwardContract"

class ForwardContract : Contract {
    // Our Create command.
    class Create : CommandData

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
                val out = tx.outputs.single().data as ForwardState

                requireThat {
                    "The contract is settled at the specified date" using (out.settlementDate > Instant.now())
                }
            }

            else -> throw IllegalArgumentException("Command doesn't exist")
        }
    }

    interface Commands: CommandData {
        class Create: TypeOnlyCommandData(), Commands
        class Settle: TypeOnlyCommandData(), Commands
    }
}

// *********
// * State *
// *********
class ForwardState(val initiator: Party, val acceptor: Party, val asset: String, val deliveryPrice: BigDecimal,
                   val settlementDate: Instant, val buySell: String) : ContractState {
    override val participants get() = listOf(initiator, acceptor)
}