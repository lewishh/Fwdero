package com.template

import net.corda.core.contracts.*
import net.corda.core.flows.FlowLogicRefFactory
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import java.time.Instant
import com.template.base.SpotPrice
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.utils.sumCash
import java.math.BigDecimal
import java.security.PublicKey
import java.util.*

// *****************
// * Contract Code *
// *****************
class ForwardContract : Contract {
    companion object {
        @JvmStatic
        val FORWARD_CONTRACT_ID = "com.template.ForwardContract"
    }

    interface Commands : CommandData {
        class Create : TypeOnlyCommandData(), Commands
        class SettlePhysical : TypeOnlyCommandData(), Commands
        class SettleCash : TypeOnlyCommandData(), Commands
    }

    private fun keysFromParticipants(forwardState: ForwardState): Set<PublicKey> {
        return forwardState.participants.map {
            it.owningKey
        }.toSet()
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<ForwardContract.Commands>()

        when (command.value) {
            is Commands.Create -> {
                requireThat {
                    "No inputs should be consumed when issuing the contract." using (tx.inputs.isEmpty())
                    "Only one output state should be created." using (tx.outputs.size == 1)
                    val forward = tx.outputStates.single() as ForwardState
                    "The initiator and acceptor cannot have the same identity." using (forward.acceptor != forward.initiator)
                    "Both initiator and acceptor together only may sign the transaction." using
                            (command.signers.toSet() == forward.participants.map { it.owningKey }.toSet())
                }
            }

            is Commands.SettlePhysical -> {
                requireThat {
                    val timestamp = Instant.now()
//                    val forward = tx.inputStates.single() as ForwardState
                    val forwards = tx.groupStates<ForwardState, UniqueIdentifier> { it.linearId }.single()
                    val inputForward = forwards.inputs.single()

                    "Must have matured" using (timestamp >= inputForward.settlementTimestamp)

                    "There must be one input." using (forwards.inputs.size == 1)

//                    val cash = tx.outputsOfType<Cash.State>()
//                    "There must be output cash." using (cash.isNotEmpty())

//                    val acceptableCash = cash.filter { it.owner == inputForward.initiator }
//                    "There must be output cash paid to the recipient." using (acceptableCash.isNotEmpty())

                    "There must be no output Forward as it has been fully settled." using (forwards.outputs.isEmpty())

                    "Both initiator and acceptor must sign during settlement." using
                            (command.signers.toSet() == inputForward.participants.map { it.owningKey }.toSet())
                }
            }

            is Commands.SettleCash -> {
                requireThat {
                    val timestamp = Instant.now()
                    val forward = tx.inputStates.single() as ForwardState
                    val forwards = tx.groupStates<ForwardState, UniqueIdentifier> { it.linearId }.single()
                    val inputForward = forwards.inputs.single()

                    "Must have matured" using (timestamp >= inputForward.settlementTimestamp)

                    "There must be one input." using (forwards.inputs.size == 1)

//                    val cash = tx.outputsOfType<Cash.State>()
//                    "There must be output cash." using (cash.isNotEmpty())

//                    val acceptableCash = cash.filter { it.owner == inputForward.initiator }
//                    "There must be output cash paid to the recipient." using (acceptableCash.isNotEmpty())

                    "There must be no output Forward as it has been fully settled." using (forwards.outputs.isEmpty())

                    "Both initiator and acceptor must sign during settlement." using
                                (command.signers.toSet() == forward.participants.map { it.owningKey }.toSet())
                }
            }

            else -> throw IllegalArgumentException("Command doesn't exist")
        }
    }

    class OracleCommand(val spotPrice: SpotPrice) : CommandData
}

// *********
// * State *
// *********
data class ForwardState(val initiator: Party, val acceptor: Party, val instrument: String, val instrumentQuantity: Int, val currency: String, val amount: Amount<Currency>,
                        val settlementTimestamp: Instant, val settlementType: String, val paid: Amount<Currency> = Amount(0, amount.token),
                        override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState, SchedulableState {

    override val participants get() = listOf(initiator, acceptor)

    override fun nextScheduledActivity(thisStateRef: StateRef, flowLogicRefFactory: FlowLogicRefFactory): ScheduledActivity? {
        if (settlementType == "cash") {
            return ScheduledActivity(flowLogicRefFactory.create("com.template.flows.SettleCashFlow", thisStateRef, linearId, this, amount), settlementTimestamp)
        } else if (settlementType == "physical") {
            return ScheduledActivity(flowLogicRefFactory.create("com.template.flows.SettlePhysicalFlow", thisStateRef, linearId, Amount(1, Currency.getInstance(currency))), settlementTimestamp)
        }

        return null
    }

    companion object {
        /**
         * Calculate the balance required to pay the acceptor for a cash settlement
         * @param forwardState ForwardState
         * @param oracleResults SpotPrice
         * @param currency String
         * @return Amount<Currency>
         * @return String
         */
        fun calculateCash(forwardState: ForwardState, oracleResults: SpotPrice, currency: String): Pair<Amount<Currency>, Party?> {
            if (forwardState.amount.toDecimal() < oracleResults.value) {
                val difference = (oracleResults.value - forwardState.amount.toDecimal()) * BigDecimal(forwardState.instrumentQuantity)
                return Pair(Amount(difference.toLong(), Currency.getInstance(currency)), forwardState.initiator)
            } else if (forwardState.amount.toDecimal() > oracleResults.value) {
                val difference = (forwardState.amount.toDecimal() - oracleResults.value) * BigDecimal(forwardState.instrumentQuantity)
                return Pair(Amount(difference.toLong(), Currency.getInstance(currency)), forwardState.acceptor)
            }

            return Pair(Amount(0, Currency.getInstance(currency)), null)
        }
    }
}