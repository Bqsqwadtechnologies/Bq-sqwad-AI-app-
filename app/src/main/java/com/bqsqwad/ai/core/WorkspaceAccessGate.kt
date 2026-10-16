package com.bqsqwad.ai.core

import com.bqsqwad.ai.domain.Workspace
import org.json.JSONArray
import org.json.JSONObject

data class WorkspaceAccessSnapshot(
    val admin: Boolean,
    val adminTestMode: Boolean,
    val entitlements: Set<String>,
    val entitlementModules: Set<String>,
    val planModules: Map<String, List<String>>,
    val loaded: Boolean = true
) {
    fun hasVerifiedAccess(workspace: Workspace): Boolean {
        if (admin && adminTestMode) return true
        val module = workspaceModule(workspace) ?: return true
        val plans = planModules[module].orEmpty()
        if (plans.isEmpty()) {
            // Data Analyst is credit-metered unless the live catalog explicitly adds a plan.
            if (module == "data-analysis") return true
            // A module with no commercial catalog entry is not treated as free.
            // Existing website/legacy entitlements can still authorize it by module.
            return entitlementModules.contains(module)
        }
        val hasFreePlan = plans.any { it.endsWith(":free") }
        return hasFreePlan || plans.any { entitlements.contains(it.substringBefore(":")) }
    }

    fun requiresPaidAccess(workspace: Workspace): Boolean {
        val module = workspaceModule(workspace) ?: return false
        val plans = planModules[module].orEmpty()
        return plans.isNotEmpty() && plans.none { it.endsWith(":free") }
    }

    companion object {
        fun empty() = WorkspaceAccessSnapshot(false, false, emptySet(), emptySet(), emptyMap(), false)

        fun fromCatalog(catalog: JSONObject, entitlementsBody: JSONObject, admin: Boolean, testMode: Boolean): WorkspaceAccessSnapshot {
            val plans = mutableMapOf<String, MutableList<String>>()
            val planArray = catalog.optJSONArray("plans") ?: JSONArray()
            for (i in 0 until planArray.length()) {
                val p = planArray.optJSONObject(i) ?: continue
                val id = p.optString("id")
                val module = p.optString("module")
                if (id.isBlank() || module.isBlank()) continue
                val free = p.optJSONObject("price")?.has("free") == true ||
                    p.optString("productType") == "free" ||
                    p.optString("paymentChannel") == "free"
                plans.getOrPut(module) { mutableListOf() }.add(id + if (free) ":free" else ":paid")
            }
            val entitlementIds = mutableSetOf<String>()
            val entitlementModules = mutableSetOf<String>()
            val items = entitlementsBody.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val state = item.optString("state")
                if (state !in setOf("active", "grace", "on_hold", "paused")) continue
                item.optString("planId").takeIf { it.isNotBlank() }?.let(entitlementIds::add)
                item.optString("module").takeIf { it.isNotBlank() }?.let(entitlementModules::add)
            }
            return WorkspaceAccessSnapshot(admin, testMode, entitlementIds, entitlementModules, plans, true)
        }

        private fun workspaceModule(workspace: Workspace): String? = when (workspace) {
            Workspace.BUSINESS -> "business"
            Workspace.ORGANIZATION_STUDIO -> "organization"
            Workspace.LEARNING -> "learning"
            Workspace.TRUTH_GUARD -> "truthguard"
            Workspace.LIFE -> "life-mathis"
            Workspace.COMPANION -> "companion"
            Workspace.FRONTIER_LAB -> "frontier"
            Workspace.DATA_ANALYSIS -> "data-analysis"
            else -> null
        }
    }
}
