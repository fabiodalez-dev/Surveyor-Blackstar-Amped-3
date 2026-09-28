package com.example.amped3controller

import org.json.JSONArray
import org.json.JSONObject

data class LibraryArchive(val presets: List<LocalPreset>, val custom: List<CustomProfile>,
    val backups: List<String>, val audition: JSONObject?) {
    companion object {
        fun parse(text: String): LibraryArchive {
            val root = JSONObject(text)
            require(root.getString("format") in listOf("amped-usb-library-v1", "amped-usb-library-v2"))
            val presets = LocalPreset.parseList(root.getJSONArray("presets").toString())
            val custom = CustomProfile.parseList((root.optJSONArray("customProfiles") ?: JSONArray()).toString())
            custom.forEach { Recovery.validateProfile(it.transferJson()) }
            val raw = root.optJSONArray("hardwareBackups") ?: JSONArray()
            require(raw.length() <= 10000)
            val backups = (0 until raw.length()).map {
                val o = raw.getJSONObject(it)
                if (o.has("contents")) o.getString("contents") else o.toString()
            }
            val audition = root.optJSONObject("audition")
            audition?.let {
                Recovery.validateProfile(it.getJSONObject("factory"))
                require(it.getJSONArray("cab").length() == 84 && it.getInt("level") in 0..127)
            }
            return LibraryArchive(presets, custom, backups, audition)
        }
    }
}
