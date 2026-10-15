package com.example.amped3controller

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** Rotation must not close USB halfway through a write or discard recovery state. */
class ControllerModel(application: Application) : AndroidViewModel(application) {
    val controller = AmpedMidiController(application)
    private var started = false

    /**
     * Runs [start] once per model. After a rotation the model survives and is already connected;
     * after process death it is new and must connect again. savedInstanceState cannot tell the
     * two apart: it is present in both cases.
     */
    fun startOnce(start: (AmpedMidiController) -> Unit) {
        if (started) return
        started = true
        start(controller)
    }
    override fun onCleared() { controller.close() }
}
