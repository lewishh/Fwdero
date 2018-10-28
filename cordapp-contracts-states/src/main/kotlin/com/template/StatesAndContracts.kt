package com.template

import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import java.math.BigDecimal
import java.util.*

// *****************
// * Contract Code *
// *****************
const val FORWARD_CONTRACT_ID = "com.template.ForwardContract"

class ForwardContract : Contract {
    class Create : CommandData
    // This is used to identify our contract when building a transaction
    companion object {
        val ID = "com.template.ForwardContract"
    }

    // A transaction is considered valid if the verify() function of the contract of each of the transaction's input
    // and output states does not throw an exception.
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Create>()

        requireThat {
            // Constraints on the shape of the transaction.
            "No inputs should be consumed when issuing a Forward Contract." using (tx.inputs.isEmpty())
            "There should be one output state of type IOUState." using (tx.outputs.size == 1)

            // IOU-specific constraints.
            val out = tx.outputsOfType<ForwardState>().single()
            "The asset deliveryPrice must be non-negative." using (out.deliveryPrice.compareTo(BigDecimal.ZERO) > 0)
            "The initiator and acceptor cannot be the same entity." using (out.initiator != out.acceptor)

            // Constraints on the signers.
            "There must be two signers." using (command.signers.toSet().size == 2)
//            "The initiator and and acceptor must be signers." using (command.signers.containsAll(listOf(
//                    out.acceptor.owningKey, out.initiator.owningKey)))
            "All of the participants must be signers." using (command.signers.containsAll(out.participants.map { it.owningKey }))
        }
    }

    // Used to indicate the transaction's intent.
    interface Commands : CommandData {
        class Create : Commands
    }
}

// *********
// * State *
// *********
//data class ForwardState(val value: Int, val lender: Party, val borrower: Party) : ContractState {
//    override val participants: List<AbstractParty> = listOf() // Entities which state is relevant
//}
data class ForwardState(val initiator: Party, val acceptor: Party, val asset: String, val deliveryPrice: BigDecimal,
                        val agreementDate: Date, val settlementDate: Date, val buySell: String) : ContractState {
    override val participants: List<AbstractParty> = listOf() // Entities which state is relevant
}