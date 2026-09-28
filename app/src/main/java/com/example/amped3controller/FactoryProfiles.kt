package com.example.amped3controller

import org.json.JSONArray
import org.json.JSONObject

/** The 288 cabinet/mic/axis payloads captured from Architect, as shipped in cab_profiles.json. */
class FactoryProfiles(json: String) {
    private val profiles = JSONArray(json)

    fun find(cab: Int, mic: Int, axis: Int): JSONObject? = (0 until profiles.length()).map { profiles.getJSONObject(it) }
        .firstOrNull { it.getInt("cab") == cab && it.getInt("mic") == mic && it.getInt("axis") == axis }
}
