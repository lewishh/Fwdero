package com.template

import net.corda.core.contracts.*
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import java.math.BigDecimal
import java.util.*

// *****************
// * Contract Code *
// *****************
// This is used to identify our contract when building a transaction
val FORWARD_CONTRACT_ID = "com.template.ForwardContract"

class ForwardContract : Contract {
    // Our Create command.
    class Create : CommandData

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Create>()

        requireThat {
            // Constraints on the shape of the transaction.
            "No inputs should be consumed when issuing an IOU." using (tx.inputs.isEmpty())
            "There should be one output state of type IOUState." using (tx.outputs.size == 1)

            // IOU-specific constraints.
            val out = tx.outputsOfType<ForwardState>().single()
            "The IOU's value must be non-negative." using (out.deliveryPrice.compareTo(BigDecimal.ZERO) > 0)
            "The lender and the borrower cannot be the same entity." using (out.initiator != out.acceptor)

            // Constraints on the signers.
            "There must be two signers." using (command.signers.toSet().size == 2)
            "The borrower and lender must be signers." using (command.signers.containsAll(listOf(
                    out.acceptor.owningKey, out.initiator.owningKey)))
        }
    }
}

// *********
// * State *
// *********
class ForwardState(val initiator: Party, val acceptor: Party, val asset: String, val deliveryPrice: BigDecimal,
                   val agreementDate: Date, val settlementDate: Date, val buySell: String) : ContractState {
    override val participants get() = listOf(initiator, acceptor)
}