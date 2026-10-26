package com.owlacoustics.domain.services

import com.owlacoustics.domain.catalog.Money
import kotlinx.serialization.Serializable

/**
 * Expert-service catalog and purchase lifecycle (C-24). Package data, provider-authoritative price, entitlement and
 * fulfilment have distinct ownership. Nothing here is live until a real provider and approved package exist (B-04/B-05).
 */
@Serializable
data class ServicePackage(
    val id: String,
    val version: Int,
    val name: String,
    val description: String,
    val inclusions: List<String>,
    /** Capabilities the package depends on; all must be implemented for the package to be offered. */
    val capabilityIds: List<String>,
    val published: Boolean,
    val price: Money?,
    val providerProductRef: String?,
    val terms: String,
    val fulfillment: String,
)

object ImplementedCapabilities {
    val ids: Set<String> = setOf("room-snapshot-share", "special-ceiling-draft")
}

sealed interface OfferAvailability {
    data class Ready(val pkg: ServicePackage) : OfferAvailability
    data class Unavailable(val reason: String) : OfferAvailability
}

object ServiceCatalog {
    fun availability(pkg: ServicePackage?, providerConfigured: Boolean): OfferAvailability = when {
        pkg == null -> OfferAvailability.Unavailable("Expert help is not configured yet.")
        !pkg.published -> OfferAvailability.Unavailable("This offering is not published.")
        pkg.price == null || pkg.providerProductRef == null -> OfferAvailability.Unavailable("This offering has no approved price.")
        !providerConfigured -> OfferAvailability.Unavailable("Payments are not configured yet.")
        !ImplementedCapabilities.ids.containsAll(pkg.capabilityIds) -> OfferAvailability.Unavailable("This offering depends on a capability that is not available.")
        else -> OfferAvailability.Ready(pkg)
    }
}

@Serializable
enum class OrderState { CHECKOUT_PENDING, VERIFIED_PAID, FULFILLMENT_REQUESTED, FULFILLED, CANCELED, FAILED, REVOKED }

/** Immutable once created: later catalog edits never rewrite these. */
@Serializable
data class PurchasedTerms(
    val packageId: String,
    val packageVersion: Int,
    val name: String,
    val inclusions: List<String>,
    val price: Money,
    val terms: String,
    val purchaseRef: String,
)

/** A submitted request bound to a specific room revision/snapshot; later edits do not change it. */
@Serializable
data class ServiceRequest(
    val roomId: String,
    val historySeq: Long,
    val modelHash: String,
    val snapshotJson: String,
    val notes: String,
    val sharedData: List<String>,
    /** Raw media only when explicitly authorised for this request. */
    val authorizedMediaIds: List<String>,
    val submittedMs: Long,
)

@Serializable
data class Transition(val from: OrderState?, val to: OrderState, val atMs: Long, val note: String)

@Serializable
data class Order(
    /** Stable idempotency key, reused for every retry of the same checkout. */
    val idempotencyKey: String,
    val packageId: String,
    val packageVersion: Int,
    val displayedPrice: Money,
    val sandbox: Boolean,
    val state: OrderState,
    val terms: PurchasedTerms? = null,
    val request: ServiceRequest? = null,
    val transitions: List<Transition> = emptyList(),
)

/** Result reported by trusted server-side verification with the billing provider. Never built from client flags. */
sealed interface Verification {
    data class Paid(val purchaseRef: String, val price: Money, val packageId: String, val packageVersion: Int) : Verification
    data object Pending : Verification
    data object Canceled : Verification
    data class Failed(val reason: String) : Verification
    data object Revoked : Verification
    /** Network/timeout: says nothing about payment either way. */
    data object Unknown : Verification
}

sealed interface OrderOutcome {
    data class Updated(val order: Order) : OrderOutcome
    data class Rejected(val reason: String) : OrderOutcome
}

object Orders {
    fun begin(existing: Order?, key: String, pkg: ServicePackage, sandbox: Boolean, now: Long): OrderOutcome {
        if (existing != null) return OrderOutcome.Updated(existing) // duplicate tap/retry → same order
        val price = pkg.price ?: return OrderOutcome.Rejected("No approved price.")
        return OrderOutcome.Updated(Order(key, pkg.id, pkg.version, price, sandbox, OrderState.CHECKOUT_PENDING,
            transitions = listOf(Transition(null, OrderState.CHECKOUT_PENDING, now, "checkout started"))))
    }

    /** Applies trusted verification. The paid price/currency/package must match what was displayed. */
    fun applyVerification(o: Order, v: Verification, pkgAtCheckout: ServicePackage, now: Long): OrderOutcome {
        fun move(to: OrderState, note: String, terms: PurchasedTerms? = o.terms) =
            OrderOutcome.Updated(o.copy(state = to, terms = terms, transitions = o.transitions + Transition(o.state, to, now, note)))
        return when (v) {
            is Verification.Unknown -> OrderOutcome.Updated(o) // never treated as success or as loss of entitlement
            is Verification.Pending -> if (o.state == OrderState.CHECKOUT_PENDING) OrderOutcome.Updated(o) else OrderOutcome.Updated(o)
            is Verification.Paid -> when {
                o.state != OrderState.CHECKOUT_PENDING -> OrderOutcome.Updated(o) // duplicate callback: no second grant
                v.packageId != o.packageId || v.packageVersion != o.packageVersion -> move(OrderState.FAILED, "verified purchase does not match package")
                v.price != o.displayedPrice -> move(OrderState.FAILED, "verified amount/currency differs from displayed price")
                else -> move(OrderState.VERIFIED_PAID, "verified by provider", PurchasedTerms(pkgAtCheckout.id, pkgAtCheckout.version, pkgAtCheckout.name,
                    pkgAtCheckout.inclusions, v.price, pkgAtCheckout.terms, v.purchaseRef))
            }
            is Verification.Canceled -> if (o.state == OrderState.CHECKOUT_PENDING) move(OrderState.CANCELED, "canceled") else OrderOutcome.Updated(o)
            is Verification.Failed -> if (o.state == OrderState.CHECKOUT_PENDING) move(OrderState.FAILED, v.reason) else OrderOutcome.Updated(o)
            is Verification.Revoked -> if (o.state in setOf(OrderState.VERIFIED_PAID, OrderState.FULFILLMENT_REQUESTED, OrderState.FULFILLED))
                move(OrderState.REVOKED, "revoked/refunded per provider") else OrderOutcome.Updated(o)
        }
    }

    fun submitRequest(o: Order, req: ServiceRequest, now: Long): OrderOutcome {
        if (o.request != null) return OrderOutcome.Updated(o) // retry of an already submitted request: unchanged
        if (o.state != OrderState.VERIFIED_PAID) return OrderOutcome.Rejected("A verified purchase is required before submitting.")
        return OrderOutcome.Updated(o.copy(state = OrderState.FULFILLMENT_REQUESTED, request = req,
            transitions = o.transitions + Transition(o.state, OrderState.FULFILLMENT_REQUESTED, now, "request submitted")))
    }

    val entitled = setOf(OrderState.VERIFIED_PAID, OrderState.FULFILLMENT_REQUESTED, OrderState.FULFILLED)
}
