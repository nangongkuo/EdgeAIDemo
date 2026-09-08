package com.zjf.edgeai.agent.adk

import com.google.adk.kt.agents.LlmAgent

/** 固定 ADK 0.8.0 的编译期兼容探针；升级时本模块的契约测试必须先通过。 */
object AdkCompatibility {
    const val BASELINE_VERSION: String = "0.8.0"

    val llmAgentClassName: String
        get() = LlmAgent::class.java.name
}
