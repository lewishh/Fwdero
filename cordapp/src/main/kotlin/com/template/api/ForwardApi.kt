import com.template.ForwardState
import com.template.flows.IssueCashFlow
import com.template.flows.SettleFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.x500Name
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.NodeInfo
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.loggerFor
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.getCashBalances
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.slf4j.Logger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Currency
import javax.servlet.http.Part
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

/**
 * This API is accessible from /api/forward. The endpoint paths specified below are relative to it.
 * We've defined a bunch of endpoints to deal with Forwards, cash and the various operations you can perform with them.
 */
@Path("forward")
class ForwardApi(val rpcOps: CordaRPCOps) {
    private val me = rpcOps.nodeInfo().legalIdentities.first().name

    companion object {
        private val logger: Logger = loggerFor<ForwardApi>()
    }

    fun X500Name.toDisplayString() : String  = BCStyle.INSTANCE.toString(this)

    /** Helpers for filtering the network map cache. */
    private fun isNotary(nodeInfo: NodeInfo) = rpcOps.notaryIdentities().any { nodeInfo.isLegalIdentity(it) }
    private fun isMe(nodeInfo: NodeInfo) = nodeInfo.legalIdentities.first().name == me
    private fun isNetworkMap(nodeInfo : NodeInfo) = nodeInfo.legalIdentities.single().name.organisation == "Network Map Service"

    /**
     * Returns the node's name.
     */
    @GET
    @Path("me")
    @Produces(MediaType.APPLICATION_JSON)
    fun whoami() = mapOf("me" to me.toString())

    /**
     * Returns all parties registered with the [NetworkMapService]. These names can be used to look up identities
     * using the [IdentityService].
     */
    @GET
    @Path("peers")
    @Produces(MediaType.APPLICATION_JSON)
    fun getPeers(): Map<String, List<String>> {
        return mapOf("peers" to rpcOps.networkMapSnapshot()
                .filter { isNotary(it).not() && isMe(it).not() && isNetworkMap(it).not() }
                .map { it.legalIdentities.first().name.x500Name.toDisplayString() })
    }

    /**
     * Task 1
     * Displays all Fordward states that exist in the node's vault.
     * TODO: Return a list of ForwardState on ledger
     * Hint - Use [rpcOps] to query the vault all unconsumed [ForwardState]s
     */
    @GET
    @Path("forwards")
    @Produces(MediaType.APPLICATION_JSON)
    fun getForwards(): List<StateAndRef<ContractState>> {
        // Filter by state type: Forward.
        return rpcOps.vaultQueryBy<ForwardState>().states
    }

    /**
     * Displays all cash states that exist in the node's vault.
     */
    @GET
    @Path("cash")
    @Produces(MediaType.APPLICATION_JSON)
    fun getCash(): List<StateAndRef<ContractState>> {
        // Filter by state type: Cash.
        return rpcOps.vaultQueryBy<Cash.State>().states
    }

    /**
     * Displays all cash states that exist in the node's vault.
     */
    @GET
    @Path("cash-balances")
    @Produces(MediaType.APPLICATION_JSON)
    // Display cash balances.
    fun getCashBalances() = rpcOps.getCashBalances()

    /**
     * Initiates a flow to agree a Forward between two parties.
     * Example request:
     */
    @GET
    @Path("issue-forward")
    fun issueForward(@QueryParam(value = "acceptor") acceptor: String,
                     @QueryParam(value = "instrument") instrument: String,
                     @QueryParam(value = "quantity") quantity: Int,
                     @QueryParam(value = "currency") currency: String,
                     @QueryParam(value = "amount") amount: Int,
                     @QueryParam(value = "settlementTimestamp") settlementTimestamp: String, // this
                     @QueryParam(value = "settlementType") settlementType: String): Response {

        val me = rpcOps.nodeInfo().legalIdentities.first()
        val lender = rpcOps.wellKnownPartyFromX500Name(CordaX500Name.parse(acceptor)) ?: throw IllegalArgumentException("Unknown party name.")
        val amountDue = Amount(amount.toLong() * 100, Currency.getInstance(currency))
        val expiryDate = LocalDate.parse(settlementTimestamp).atStartOfDay().toInstant(ZoneOffset.UTC)
        // Create new State
        try {
            val state = ForwardState(
                    initiator = me, acceptor = lender, instrument = instrument, instrumentQuantity = quantity, currency = currency,
                    amount = amountDue, settlementTimestamp = expiryDate, settlementType = settlementType)
            // Start the IssueFlow. We block and waits for the flow to return.
            val result = rpcOps.startTrackedFlow(::CreateFlow, state).returnValue.get()
            // Return the response.
            return Response
                    .status(Response.Status.CREATED)
                    .entity("Transaction id ${result.id} committed to ledger.\n${result.tx.outputs.single()}")
                    .build()
            // For the purposes of this demo app, we do not differentiate by exception type.
        } catch (e: Exception) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity(e.message)
                    .build()
        }
    }


    /**
     * Settles an Forward. Requires cash in the right currency to be able to settle.
     */
    @GET
    @Path("settle-forward")
    fun settleForward(@QueryParam(value = "id") id: String,
                      @QueryParam(value = "amount") amount: Int,
                      @QueryParam(value = "currency") currency: String): Response {
        val linearId = UniqueIdentifier.fromString(id)
        val settleAmount = Amount(amount.toLong() * 100, Currency.getInstance(currency))

        try {
            rpcOps.startFlow(::SettleFlow, linearId, settleAmount).returnValue.get()
            return Response.status(Response.Status.CREATED).entity("$amount $currency paid off on Forward id $id.").build()

        } catch (e: Exception) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity(e.message)
                    .build()
        }
    }

    /**
     * Helper end-point to issue some cash to ourselves.
     */
    @GET
    @Path("self-issue-cash")
    fun selfIssueCash(@QueryParam(value = "amount") amount: Int,
                      @QueryParam(value = "currency") currency: String): Response {
        val issueAmount = Amount(amount.toLong() * 100, Currency.getInstance(currency))

        try {
            val cashState = rpcOps.startFlow(::IssueCashFlow, issueAmount).returnValue.get()
            return Response.status(Response.Status.CREATED).entity(cashState.toString()).build()

        } catch (e: Exception) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity(e.message)
                    .build()
        }
    }
}