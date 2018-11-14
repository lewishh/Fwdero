package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.FORWARD_CONTRACT_ID
import com.template.ForwardContract
import com.template.ForwardState
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.lang.IllegalArgumentException
import java.math.BigDecimal
import java.time.Instant

@InitiatingFlow
@StartableByRPC
class Settle(private val initiator: Party, private val acceptor: Party, private val instrument: String, private val instrumentQuantity: BigDecimal,
             private val deliveryPrice: BigDecimal, private val settlementTimestamp: Instant, private val settlementType: String, private val position: String): FlowLogic<SignedTransaction>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call(): SignedTransaction {
        // We retrieve the notary identity from the network map.
        val notary = serviceHub.networkMapCache.notaryIdentities[0]
        // We create a transaction builder.
        val txBuilder = TransactionBuilder(notary = notary)

        val settleCommand = Command(ForwardContract.Commands.Settle(), listOf(acceptor.owningKey, ourIdentity.owningKey))
        val outputState = ForwardState(initiator, acceptor, instrument, instrumentQuantity, deliveryPrice, settlementTimestamp, settlementType, position)
        // Add the input and settle command.
        txBuilder.addCommand(settleCommand)
        txBuilder.addOutputState(outputState, FORWARD_CONTRACT_ID)
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        // Creating a session with the other party.
        val otherpartySession = initiateFlow(acceptor)

        // Obtaining the counterparty's signature.
        val fullySignedTx = subFlow(CollectSignaturesFlow(signedTx, listOf(otherpartySession), CollectSignaturesFlow.tracker()))

        // Finalising the transaction.
        return subFlow(FinalityFlow(fullySignedTx))
    }
}

// Define ForwardFlowResponder:
@InitiatedBy(Settle::class)
class SettleResponder(val otherPartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signTransactionFlow = object : SignTransactionFlow(otherPartySession, SignTransactionFlow.tracker()) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be a Forward transaction." using (output is ForwardState)
            }
        }

        subFlow(signTransactionFlow)
    }
}