package com.example.amped3controller

import org.json.JSONArray
import org.json.JSONObject

data class LocalPreset(val id:String,val name:String,val amp:List<Int>,val cab:List<Int>, val ampSlot:Int = 0, val dsp:String? = null, val scope:String = "both") {
    fun json()=JSONObject().put("id",id).put("name",name).put("amp",JSONArray(amp)).put("cab",JSONArray(cab)).put("ampSlot",ampSlot).put("scope",scope).put("dsp",dsp?.let { JSONObject(it) })
    companion object {
        fun parseList(s:String):List<LocalPreset> {
            val a=JSONArray(s);require(a.length()<=1000)
            return (0 until a.length()).map { i ->
                val o=a.getJSONObject(i)
                fun values(key:String,size:Int):List<Int> {val ar=o.getJSONArray(key);require(ar.length()==size);return (0 until size).map { ar.getInt(it).also { n->require(n in 0..255) } }}
                val scope = o.optString("scope", "both"); require(scope in listOf("both", "amp", "cab"))
                val slot = o.optInt("ampSlot", 0); require(slot in 0..3)
                val payload = o.optJSONObject("dsp")?.let { Recovery.validateProfile(it).toString() }
                LocalPreset(o.getString("id").take(100),o.getString("name").take(60),values("amp",52),values("cab",84),slot,payload,scope)
            }
        }
    }
}
