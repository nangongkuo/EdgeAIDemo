## 新增需求

### 需求：环境诊断
应用 SHALL 展示应用、LiteRT-LM、设备、Android API、ABI、已选模型、首选后端和实际后端信息，以便复现验证运行。

#### 场景：运行时就绪
- **WHEN** 模型初始化完成
- **THEN** “诊断”页面 SHALL 展示已选模型和实际后端，且不得暴露用户源 URI 或凭据

#### 场景：运行时未初始化
- **WHEN** 尚未加载 Engine
- **THEN** “诊断”页面 SHALL 展示环境和模型元数据，并将运行时指标标为不可用

### 需求：后端回退诊断
应用 MUST 展示是否发生 GPU 回退，并为当前运行时会话保留经过脱敏的失败原因。

#### 场景：模拟器兼容性回退
- **WHEN** 因 Android 模拟器无法提供 LiteRT-LM GPU/OpenCL 运行时而在启动时选择 CPU
- **THEN** 诊断 SHALL 标识 GPU 为首选、CPU 为实际后端，并展示不暴露内部模型路径的模拟器兼容性回退原因

#### 场景：GPU 失败后使用 CPU
- **WHEN** GPU 出错后 CPU 初始化成功
- **THEN** 诊断 SHALL 标识 CPU 为实际后端、GPU 为首选后端，并展示回退原因

### 需求：推理基准诊断
应用 SHALL 将 LiteRT-LM 的 BenchmarkInfo 映射为初始化时间、首 Token 时间、Prefill Token 数、Decode Token 数、Prefill 每秒 Token 数和 Decode 每秒 Token 数。

#### 场景：生成完成
- **WHEN** LiteRT-LM 在响应后报告基准信息
- **THEN** “诊断”页面 SHALL 以明确单位展示所有已报告的指标

#### 场景：基准信息不可用
- **WHEN** 生成尚未完成，或 SDK 未返回可用值
- **THEN** 应用 MUST 将该指标显示为不可用，而不得虚构或估算它
