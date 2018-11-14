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
class Initiate(val initiator: Party, val acceptor: Party, val instrument: String, val instrumentQuantity: BigDecimal, val deliveryPrice: BigDecimal,
               val settlementTimestamp: Instant, val settlementType: String, val position: String) : FlowLogic<SignedTransaction>() {

    companion object {
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating Forward transaction (Initiate)")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key (Initiate)")
        object FINALISING_TRANSACTION : ProgressTracker.Step("Recording transaction (Initiate)") {
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

        // We create a transaction builder.
        val txBuilder = TransactionBuilder(notary = notary)

        // We create the transaction components.
        progressTracker.currentStep = GENERATING_TRANSACTION
        val outputState = ForwardState(initiator, acceptor, instrument, instrumentQuantity, deliveryPrice, settlementTimestamp, settlementType, position)
        val outputContractAndState = StateAndContract(outputState, FORWARD_CONTRACT_ID)
        val cmd = Command(ForwardContract.Commands.Create(), listOf(ourIdentity.owningKey, acceptor.owningKey))

        // We add the items to the builder.
        txBuilder.withItems(outputContractAndState, cmd)

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        progressTracker.currentStep = SIGNING_TRANSACTION
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        // Creating a session with the other party.
        val otherpartySession = initiateFlow(acceptor)

        // Obtaining the counterparty's signature.
        val fullySignedTx = subFlow(CollectSignaturesFlow(signedTx, listOf(otherpartySession), CollectSignaturesFlow.tracker()))

        // Finalising the transaction.
        progressTracker.currentStep = FINALISING_TRANSACTION
        return subFlow(FinalityFlow(fullySignedTx))
    }
}

// Define InitiateFlowResponder:
@InitiatedBy(Initiate::class)
class InitiateResponder(val otherPartySession: FlowSession) : FlowLogic<Unit>() {
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