package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.StateRef
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.webserver.services.WebServerPluginRegistry
import java.util.function.Function
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.SignedTransaction
import java.math.BigDecimal
import java.time.Instant

// *****************
// * API Endpoints *
// *****************
@Path("template")
class TemplateApi(val rpcOps: CordaRPCOps) {
    // Accessible at /api/template/templateGetEndpoint.
    @GET
    @Path("templateGetEndpoint")
    @Produces(MediaType.APPLICATION_JSON)
    fun templateGetEndpoint(): Response {
        return Response.ok("Template GET endpoint.").build()
    }
}

// *********
// * Flows *
// *********
@InitiatingFlow
@StartableByRPC
class ForwardFlow(val initiator: Party, val acceptor: Party, val asset: String, val deliveryPrice: BigDecimal,
                  val settlementTimestamp: Instant, val buySell: String) : FlowLogic<Unit>() {

    companion object {
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating Forward transaction (ForwardFlow)")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key (ForwardFlow)")
        object FINALISING_TRANSACTION : ProgressTracker.Step("Recording transaction (ForwardFlow)") {
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
    override fun call() {
        // We retrieve the notary identity from the network map.
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        // We create a transaction builder.
        val txBuilder = TransactionBuilder(notary = notary)

        // We create the transaction components.
        progressTracker.currentStep = GENERATING_TRANSACTION
        val outputState = ForwardState(initiator, acceptor, asset, deliveryPrice, settlementTimestamp, buySell)
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
        subFlow(FinalityFlow(fullySignedTx))
    }
}

// Define ForwardFlowResponder:
@InitiatedBy(ForwardFlow::class)
class ForwardFlowResponder(val otherPartySession: FlowSession) : FlowLogic<Unit>() {
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

@InitiatingFlow
@SchedulableFlow
@StartableByRPC
class ForwardSettleFlow(val initiator: Party, val acceptor: Party, val asset: String, val deliveryPrice: BigDecimal, val settlementTimestamp: Instant, val buySell: String,
                        val thisStateRef: StateRef) : FlowLogic<Unit>() {

    companion object {
        object GENERATING_TRANSACTION : ProgressTracker.Step("Updating Forward transaction for settlement process (ForwardSettleFlow)")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing settlement transaction with our private key (ForwardSettleFlow)")
        object FINALISING_TRANSACTION : ProgressTracker.Step("Recording settlement transaction (ForwardSettleFlow)") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(
                GENERATING_TRANSACTION,
                SIGNING_TRANSACTION,
                FINALISING_TRANSACTION
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call() {
        progressTracker.currentStep = GENERATING_TRANSACTION
        val input = serviceHub.toStateAndRef<ForwardState>(thisStateRef)
        val output = ForwardState(initiator, acceptor, asset, deliveryPrice, settlementTimestamp, buySell)
        val beatCmd = Command(ForwardContract.Commands.Settle(), ourIdentity.owningKey)
        val txBuilder = TransactionBuilder(serviceHub.networkMapCache.notaryIdentities.first())
                .addInputState(input)
                .addOutputState(output, FORWARD_CONTRACT_ID)
                .addCommand(beatCmd)

        progressTracker.currentStep = SIGNING_TRANSACTION
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        progressTracker.currentStep = FINALISING_TRANSACTION
        subFlow(FinalityFlow(signedTx))
    }
}

// Define ForwardFlowResponder:
@InitiatedBy(ForwardSettleFlow::class)
class ForwardSettleFlowResponder(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(otherPartySession, SignTransactionFlow.tracker()) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an Forward transaction." using (output is ForwardState)
            }
        }

        return subFlow(signTransactionFlow)
    }
}

// ***********
// * Plugins *
// ***********
class TemplateWebPlugin : WebServerPluginRegistry {
    // A list of classes that expose web JAX-RS REST APIs.
    override val webApis: List<Function<CordaRPCOps, out Any>> = listOf(Function(::TemplateApi))
    //A list of directories in the resources directory that will be served by Jetty under /web.
    // This template's web frontend is accessible at /web/template.
    override val staticServeDirs: Map<String, String> = mapOf(
            // This will serve the templateWeb directory in resources to /web/template
            "template" to javaClass.classLoader.getResource("templateWeb").toExternalForm()
    )
}

// Serialization whitelist.
class TemplateSerializationWhitelist : SerializationWhitelist {
    override val whitelist: List<Class<*>> = listOf(TemplateData::class.java)
}

// This class is not annotated with @CordaSerializable, so it must be added to the serialization whitelist, above, if
// we want to send it to other nodes within a flow.
data class TemplateData(val payload: String)