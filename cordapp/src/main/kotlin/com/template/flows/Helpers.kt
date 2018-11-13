import net.corda.core.identity.CordaX500Name
import net.corda.core.node.ServiceHub

fun ServiceHub.firstNotary() = networkMapCache.notaryIdentities.first()

fun ServiceHub.firstIdentityByName(name: CordaX500Name) = networkMapCache.getNodeByLegalName(name)?.legalIdentities?.first()
        ?: throw IllegalArgumentException("Requested oracle $name not found on network.")