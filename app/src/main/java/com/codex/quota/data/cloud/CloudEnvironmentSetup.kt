package com.codex.quota.data.cloud

/** Official configuration uses the browser's own login. Never put app credentials in its URL or headers. */
object CloudEnvironmentSetup {
    const val url = "https://chatgpt.com/settings/codex-cloud"

    fun reconcile(cache: CloudCache, identity: CloudIdentity, environments: List<CloudEnvironment>): CloudCache {
        require(cache.identity == identity) { "Cloud identity changed" }
        return cache.copy(environments = environments,
            selectedEnvironment = cache.selectedEnvironment.takeIf { selected -> environments.any { it.id == selected } }
                ?: environments.firstOrNull { it.published }?.id.orEmpty())
    }
}
