package com.hwanghj09.sonju.adapter

import com.hwanghj09.sonju.perception.ScreenState
import com.hwanghj09.sonju.verifier.Predicate
import com.hwanghj09.sonju.verifier.StateValue

data class Interruption(val type: String, val dismissible: Boolean, val reason: String)
data class PredicateDefinition(val name: String, val predicate: Predicate)

interface AppStateExtractor {
    fun supports(packageName: String): Boolean
    fun extract(screen: ScreenState): Map<String, StateValue>
}

interface AppAdapter : AppStateExtractor {
    val appId: String
    fun classifyScreen(screen: ScreenState): String?
    fun knownInterruptions(screen: ScreenState): List<Interruption>
}

interface DomainKnowledgeProvider {
    fun appId(): String
    fun taskTypes(): List<String>
    fun predicates(): List<PredicateDefinition>
    fun irreversibleActions(): Set<String>
}

class AppAdapterRegistry(private val adapters: List<AppAdapter>) {
    fun forPackage(packageName: String): AppAdapter? =
        adapters.firstOrNull { it.supports(packageName) }
}

