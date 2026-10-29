package com.openminis.app.ui.chat

/**
 * [ISS-036, T-provider-live-check] Whether the provider behind the model a
 * running task uses can still take a request. Port of iOS
 * ProviderAvailabilityRules (a45ef439b).
 *
 * The agent loop builds its provider object once, with the API key in memory,
 * and reuses it for every round. Removing or disabling the provider in
 * Settings did not stop a task that was already running: "I deleted every
 * provider, the models are all gone, and it can still be used". So the loop
 * asks this before every request.
 *
 * Only the provider instance decides. A hidden model entry is not a reason,
 * and neither is a model entry that vanished while its provider is still
 * present and enabled (a model-list refresh) - the request would still work.
 */
internal object ProviderAvailability {

    enum class Problem { REMOVED, DISABLED }

    /** Who a provider object was built for, kept because removal deletes the entry too. */
    data class Identity(val entryId: String, val instanceId: String, val instanceLabel: String, val modelName: String)

    fun problem(instanceExists: Boolean, instanceEnabled: Boolean): Problem? = when {
        !instanceExists -> Problem.REMOVED
        !instanceEnabled -> Problem.DISABLED
        else -> null
    }
}
