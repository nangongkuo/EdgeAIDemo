package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.ToolCall

class ToolProgressGuard(private val repeatLimit: Int = 2) {
    private var previousKey: String? = null
    private var previousState: String? = null
    private var repeated = 0

    fun record(call: ToolCall, relevantStateFingerprint: String): Boolean {
        val key = "${call.capabilityId.value}:${normalize(call.argumentsJson)}"
        repeated = if (key == previousKey && relevantStateFingerprint == previousState) {
            repeated + 1
        } else {
            1
        }
        previousKey = key
        previousState = relevantStateFingerprint
        return repeated < repeatLimit
    }

    private fun normalize(value: String): String = value.filterNot(Char::isWhitespace)
}
