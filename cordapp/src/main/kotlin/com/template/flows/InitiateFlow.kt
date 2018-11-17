package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.FORWARD_CONTRACT_ID
import com.template.ForwardContract
import com.template.ForwardState
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.math.BigDecimal
import java.time.Instant

@InitiatingFlow
@StartableByRPC
class InitiateFlow(val initiator: Party, val acceptor: Party, val instrument: String, val instrumentQuantity: BigDecimal, val deliveryPrice: BigDecimal,
                   val settlementTimestamp: Instant, val settlementType: String, val position: String) : FlowLogic<SignedTransaction>() {

    companion object {
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating Forward transaction (InitiateFlow)")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key (InitiateFlow)") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING_TRANSACTION : ProgressTracker.Step("Finalising transaction") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(
                GENERATING_TRANSACTION,
                SIGNING_TRANSACTION,
                FINALISING_TRANSACTION
        )
    }

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = tracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call(): SignedTransaction {
        // We retrieve the notary identity from the network map.
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        // We create the transaction components.
        progressTracker.currentStep = GENERATING_TRANSACTION
        val outputState = ForwardState(initiator, acceptor, instrument, instrumentQuantity, deliveryPrice, settlementTimestamp, settlementType, position)
        val command = Command(ForwardContract.Commands.Create(), listOf(ourIdentity.owningKey, acceptor.owningKey))

        // We create a transaction builder and add the components.
        val txBuilder = TransactionBuilder(notary = notary)
                .addOutputState(outputState, FORWARD_CONTRACT_ID)
                .addCommand(command)

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        progressTracker.currentStep = SIGNING_TRANSACTION
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        // Creating a session with the other party.
        val otherPartySession = initiateFlow(acceptor)

        // Obtaining the counterparty's signature.
        val fullySignedTx = subFlow(CollectSignaturesFlow(signedTx, listOf(otherPartySession), CollectSignaturesFlow.tracker()))

        // Finalising the transaction.
        progressTracker.currentStep = FINALISING_TRANSACTION
        subFlow(FinalityFlow(fullySignedTx))
        return fullySignedTx
    }
}

// Define InitiateFlowResponder:
@InitiatedBy(InitiateFlow::class)
class InitiateFlowResponder(val otherPartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signTransactionFlow = object : SignTransactionFlow(otherPartySession, SignTransactionFlow.tracker()) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an Forward transaction." using (output is ForwardState)
            }
        }

        subFlow(signTransactionFlow)
    }
}