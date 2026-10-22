package io.github.kevin249.voicetoai

import org.junit.Assert.*
import org.junit.Test

class BridgePolicyTest {
    @Test fun exactMatchingDoesNotClickConversationText() {
        assertTrue(BridgePolicy.matches("  Start  voice mode  ", listOf("Start voice mode")))
        assertFalse(BridgePolicy.matches("Do not Start voice mode", listOf("Start voice mode")))
        assertFalse(BridgePolicy.matches("", listOf("")))
        assertFalse(BridgePolicy.matches(null, listOf("Voice")))
        assertFalse(BridgePolicy.matches("Microphone", ClientProfile.defaults[0].labels))
    }
    @Test fun ambiguousButtonsFailClosedEvenIfLabelsMatch() {
        assertNull(BridgePolicy.selectUnique(setOf(1, 2), setOf(1)))
        assertNull(BridgePolicy.selectUnique(emptySet(), setOf(1, 2)))
        assertNull(BridgePolicy.selectUnique(emptySet(), emptySet()))
        assertEquals(2, BridgePolicy.selectUnique(setOf(2), setOf(1, 3)))
        assertEquals(1, BridgePolicy.selectUnique(emptySet(), setOf(1)))
    }
    @Test fun stoppedOrSupersededWorkCannotResumeTheSession() {
        val gate = SessionGate()
        val loading = gate.next()
        assertTrue(gate.accepts(loading))
        val stopped = gate.next()
        assertFalse(gate.accepts(loading))
        val restarted = gate.next()
        assertFalse(gate.accepts(stopped))
        assertTrue(gate.accepts(restarted))
    }
    @Test fun packageValidationRejectsWildcardsAndUriInputs() {
        assertTrue(BridgePolicy.validPackage("com.openai.chatgpt"))
        assertFalse(BridgePolicy.validPackage("com.openai.*"))
        assertFalse(BridgePolicy.validPackage("https://example.com"))
        assertFalse(BridgePolicy.validPackage(""))
    }
    @Test fun defaultsValidateButCustomRequiresConfiguration() {
        ClientProfile.defaults.take(2).forEach { it.validate() }
        assertThrows(IllegalArgumentException::class.java) { ClientProfile.defaults.last().validate() }
    }
    @Test fun viewIdCannotEscapeConfiguredPackages() {
        val profile = ClientProfile.defaults[0].copy(viewIds = listOf("com.other.app:id/voice"))
        assertThrows(IllegalArgumentException::class.java) { profile.validate() }
    }
    @Test fun activeMarkerCannotAlsoBeAStartButton() {
        val profile = ClientProfile.defaults[0].copy(activeLabels = listOf("Start voice mode"))
        assertThrows(IllegalArgumentException::class.java) { profile.validate() }
    }
    @Test fun unlearnedProfileCanBeSavedButNotActivated() {
        val profile = ClientProfile.defaults[0].copy(labels = emptyList(), viewIds = emptyList())
        profile.validate(requireButton = false)
        assertThrows(IllegalArgumentException::class.java) { profile.validate() }
    }
}
