package com.zjf.edgeai.agent.api

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class RunId(val value: String) {
    init { require(value.isNotBlank()) { "runId 不能为空" } }
    companion object { fun create(): RunId = RunId(UUID.randomUUID().toString()) }
}

@Serializable
@JvmInline
value class SessionId(val value: String) {
    init { require(value.isNotBlank()) { "sessionId 不能为空" } }
    companion object { fun create(): SessionId = SessionId(UUID.randomUUID().toString()) }
}

@Serializable
@JvmInline
value class AgentId(val value: String) {
    init { require(value.isNotBlank()) { "agentId 不能为空" } }
}

@Serializable
@JvmInline
value class WorkerId(val value: String) {
    init { require(value.isNotBlank()) { "workerId 不能为空" } }
    companion object { fun create(): WorkerId = WorkerId(UUID.randomUUID().toString()) }
}

@Serializable
@JvmInline
value class StepId(val value: String) {
    init { require(value.isNotBlank()) { "stepId 不能为空" } }
    companion object { fun create(): StepId = StepId(UUID.randomUUID().toString()) }
}

@Serializable
@JvmInline
value class ToolCallId(val value: String) {
    init { require(value.isNotBlank()) { "toolCallId 不能为空" } }
    companion object { fun create(): ToolCallId = ToolCallId(UUID.randomUUID().toString()) }
}

@Serializable
@JvmInline
value class ApprovalId(val value: String) {
    init { require(value.isNotBlank()) { "approvalId 不能为空" } }
    companion object { fun create(): ApprovalId = ApprovalId(UUID.randomUUID().toString()) }
}

@Serializable
@JvmInline
value class ArtifactId(val value: String) {
    init { require(value.isNotBlank()) { "artifactId 不能为空" } }
    companion object { fun create(): ArtifactId = ArtifactId(UUID.randomUUID().toString()) }
}

@Serializable
@JvmInline
value class MemoryId(val value: String) {
    init { require(value.isNotBlank()) { "memoryId 不能为空" } }
    companion object { fun create(): MemoryId = MemoryId(UUID.randomUUID().toString()) }
}

@Serializable
@JvmInline
value class CapabilityId(val value: String) {
    init { require(value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}"))) { "非法 capabilityId: $value" } }
}

@Serializable
@JvmInline
value class ModelId(val value: String) {
    init { require(value.isNotBlank()) { "modelId 不能为空" } }
}
