package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.base.SpotPrice
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap
import java.time.Instant

/** Called by the client to request an instrument's spot price at a point in time from an oracle. */
@InitiatingFlow
class OracleQuery(private val oracle: Party, private val instrument: String, private val atTime: Instant) : FlowLogic<SpotPrice>() {
    @Suspendable override fun call(): SpotPrice {
        val oracleSession = initiateFlow(oracle)
        return oracleSession.sendAndReceive<SpotPrice>(Pair(instrument, atTime)).unwrap { it }
    }
}