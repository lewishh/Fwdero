package com.template.base
import net.corda.core.identity.CordaX500Name
import java.math.BigDecimal
import java.time.Instant

val ORACLE_NAME = CordaX500Name("Oracle", "New York","US")
val SPOT_TIMESTAMP = Instant.now()
val INSTRUMENT = "Robusta Coffee"
val INSTRUMENT_PRICE = BigDecimal(1.17)

val KNOWN_SPOTS = listOf(
        SpotPrice(INSTRUMENT, SPOT_TIMESTAMP, INSTRUMENT_PRICE)
)