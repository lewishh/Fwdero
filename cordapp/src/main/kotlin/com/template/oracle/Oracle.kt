package com.template.oracle
import com.template.ForwardContract
import com.template.base.KNOWN_SPOTS
import com.template.base.SpotPrice
import net.corda.core.crypto.TransactionSignature
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.FilteredTransaction
import java.time.Instant
import net.corda.core.contracts.Command

@CordaService
class Oracle(val services: ServiceHub) : SingletonSerializeAsToken() {
    private val myKey = services.myInfo.legalIdentities.first().owningKey

    private val knownSpots = KNOWN_SPOTS

    /** Returns spot for a given instrument. */
    fun querySpot(instrument: String, settlementTimestamp: Instant): SpotPrice {
        return knownSpots.find { it.instrument == instrument && it.settlementTimestamp == settlementTimestamp}
                ?: throw IllegalArgumentException("Unknown instrument")
    }

    /**
     * Signs over a transaction if the specified spot price is correct.
     * This function takes a filtered transaction which is a partial Merkle tree. Any parts of the transaction which
     * the oracle doesn't need to see in order to verify the correctness of the nth prime have been removed. In this
     * case, all but the [ForwardContract.Oracle] commands have been removed. If the spot price and
     * volatility are correct then the oracle signs over the Merkle root (the hash) of the transaction.
     */
    fun sign(ftx: FilteredTransaction): TransactionSignature {
        // Is the partial Merkle tree valid?
        ftx.verify()

        /** Returns true if the component is an OracleCommand that:
         *  - States the correct price
         *  - Has the oracle listed as a signer
         */
        fun isCommandWithCorrectPriceAndAndIAmSigner(elem: Any) = when {
            elem is Command<*> && elem.value is ForwardContract.OracleCommand -> {
                val cmdData = elem.value as ForwardContract.OracleCommand
                val cmdSpotPrice = cmdData.spotPrice
                myKey in elem.signers
                        && querySpot(cmdSpotPrice.instrument, cmdSpotPrice.settlementTimestamp) == cmdData.spotPrice}
            else -> false
        }

        // Is it a Merkle tree we are willing to sign over?
        val isValidMerkleTree = ftx.checkWithFun(::isCommandWithCorrectPriceAndAndIAmSigner)

        return if (isValidMerkleTree) {
            services.createSignature(ftx, myKey)
        } else {
            throw IllegalArgumentException("Oracle signature requested over invalid transaction.")
        }
    }
}