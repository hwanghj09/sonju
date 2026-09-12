package com.hwanghj09.sonju.agent

import android.content.Context

/** Only the goal and return binding survive a restart. No page, credential or user input data. */
class PendingUserInterventionStore(context: Context) {
    private val preferences = context.getSharedPreferences("pending_user_login", Context.MODE_PRIVATE)

    fun save(command: String, handoff: AutonomySession.UserHandoff) {
        if (com.hwanghj09.sonju.logging.RedactionPolicy.redact(command) != command) {
            clear()
            return
        }
        preferences.edit().putString("goal", command.take(1_000))
            .putString("origin", handoff.origin).putString("package", handoff.resumePackage)
            .putString("before_package", handoff.beforePackage).putString("kind", handoff.kind.name)
            .putString("before", handoff.beforeFingerprint).putString("template", handoff.beforeTemplateFingerprint)
            .putLong("saved_at", System.currentTimeMillis()).apply()
    }

    fun read(): String? {
        val age = System.currentTimeMillis() - preferences.getLong("saved_at", 0)
        return preferences.getString("goal", null)?.takeIf {
            age in 0..86_400_000L && it.isNotBlank()
        }.also { if (it == null && preferences.contains("goal")) clear() }
    }

    fun restore(snapshot: UiSnapshot): AutonomySession.UserHandoff? {
        val goal = read() ?: return null
        val origin = preferences.getString("origin", null) ?: HospitalReservationWorkflow.siteFor(goal)?.origin
        val owner = preferences.getString("package", null)
            // Migrate the previous hospital-only goal, retaining its reviewed origin guard.
            ?: snapshot.packageName.takeIf { HospitalReservationWorkflow.location(snapshot, goal) != null }
            ?: return null
        val kind = runCatching { UserIntervention.Kind.valueOf(preferences.getString("kind", "LOGIN")!!) }
            .getOrDefault(UserIntervention.Kind.OTHER)
        return AutonomySession.UserHandoff(origin, 0,
            preferences.getString("before_package", owner)!!, preferences.getString("before", "")!!,
            preferences.getString("template", "")!!, null, kind, owner, automaticResume = false)
    }

    fun clear() { preferences.edit().clear().apply() }
}
