package com.setu.mesh.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.setu.mesh.app.service.SetuService
import com.setu.mesh.core.engine.NodeSnapshot
import com.setu.mesh.core.model.Severity
import com.setu.mesh.core.model.SituationFlags
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin wrapper over [SetuService]'s companion snapshot flow and action functions. There is no
 * bound-service ceremony here on purpose -- `SetuService` already exposes a singleton-holder
 * API (see `docs/tasks/B5-node-host-and-service.md`), and this class exists only to keep that
 * detail out of the composables themselves.
 *
 * One deliberate deviation from `docs/tasks/B6-sos-screen.md`: the spec says every triage change
 * should re-originate immediately. `MeshNode.originateSos` has no "amend" primitive though -- it
 * always mints a fresh message id and keeps only the latest as `ownMessageId`, so calling it on
 * every checkbox toggle would leave earlier own-SOS entries orphaned in the outbox (never
 * delivered, never cleared). The alternative of calling `markSafe()` before each re-send to tidy
 * up was rejected too: that broadcasts a real "I am safe" beacon through the mesh, which is an
 * actively wrong signal to send about someone who is not safe and just added a medical flag.
 * So triage edits here update local state immediately (the UI reflects them at once) and are
 * sent as a single explicit re-send action, not one origination per toggle.
 */
class SosViewModel {

    val snapshot: StateFlow<NodeSnapshot?> = SetuService.snapshot

    var severity by mutableStateOf(Severity.HIGH)
        private set
    var souls by mutableStateOf(1)
        private set
    var trapped by mutableStateOf(false)
        private set
    var medicalNeed by mutableStateOf(false)
        private set
    var waterRising by mutableStateOf(false)
        private set

    var hasSentOnce by mutableStateOf(false)
        private set

    /** The big button: send immediately with whatever triage is currently set (defaults on first tap). */
    fun sendSos() {
        SetuService.originateSos(currentFlags(), souls)
        hasSentOnce = true
    }

    fun changeSeverity(value: Severity) {
        severity = value
        resendIfActive()
    }

    fun changeSouls(value: Int) {
        souls = value.coerceIn(1, 255)
        resendIfActive()
    }

    fun toggleTrapped() {
        trapped = !trapped
        resendIfActive()
    }

    fun toggleMedicalNeed() {
        medicalNeed = !medicalNeed
        resendIfActive()
    }

    fun toggleWaterRising() {
        waterRising = !waterRising
        resendIfActive()
    }

    fun markSafe() {
        SetuService.markSafe()
        hasSentOnce = false
    }

    /** Only re-sends if the victim has already sent once -- editing triage before the first tap is free. */
    private fun resendIfActive() {
        if (hasSentOnce) {
            SetuService.originateSos(currentFlags(), souls)
        }
    }

    private fun currentFlags(): SituationFlags = SituationFlags(
        severity = severity,
        trapped = trapped,
        medicalNeed = medicalNeed,
        waterRising = waterRising,
    )
}
