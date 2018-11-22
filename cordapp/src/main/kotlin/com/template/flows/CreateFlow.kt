import co.paralleluniverse.fibers.Suspendable
import com.template.ForwardContract
import com.template.ForwardState
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

/**
 * Create a new forward contract onto the ledger for scheduled settlement
 * @param state ForwardState
 * @return FlowLogic<SignedTransaction>
 */
@InitiatingFlow
@StartableByRPC
class CreateFlow(val state: ForwardState): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        val issueCommand = Command(ForwardContract.Commands.Create(), state.participants.map { it.owningKey })

        val builder = TransactionBuilder(notary)

        builder.addOutputState(state, ForwardContract.FORWARD_CONTRACT_ID)
        builder.addCommand(issueCommand)

        builder.verify(serviceHub)
        val ptx = serviceHub.signInitialTransaction(builder)

        val sessions = (state.participants - ourIdentity).map { initiateFlow(it) }.toSet()
        val stx = subFlow(CollectSignaturesFlow(ptx, sessions))

        return subFlow(FinalityFlow(stx))
    }
}

/**
 * Perform signing for forward contract creation
 * @param flowSession FlowSession
 */
@InitiatingFlow
@InitiatedBy(CreateFlow::class)
class CreateFlowResponder(val flowSession: FlowSession): FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be a Forward transaction" using (output is ForwardState)
            }
        }
        subFlow(signedTransactionFlow)
    }
}