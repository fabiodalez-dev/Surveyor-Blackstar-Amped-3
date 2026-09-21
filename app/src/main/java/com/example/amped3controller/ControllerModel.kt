package com.example.amped3controller

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** Rotation must not close USB halfway through a write or discard recovery state. */
class ControllerModel(application: Application) : AndroidViewModel(application) {
    val controller = AmpedMidiController(application)
    override fun onCleared() { controller.close() }
}
