# Android 客户端风控检测方案（Root/Frida/Xposed 综合检测 · AI可直接实现版）

## 1、方案整体设计思想（核心原则）

1\. **分层检测**：核心高安全检测逻辑放在 Native C 层（防Java层Hook），辅助特征放在Kotlin层做补充。

2\. **不上本地强拦截**：客户端只采集全量风险特征，**全部上报服务端**，由服务端权重打分判定风险等级，避免被单函数Hook绕过。

3\. **多维交叉校验**：进程、文件、端口、内存maps、ptrace状态、挂载点、应用包名多维度组合，杜绝单一检测绕过。

4\. **对抗加固设计**：Native代码配合商业加固虚拟化，防止SO dump、Native Hook、静态篡改。

## 2、检测覆盖范围

完整覆盖三类作弊环境：

- **Root环境**：SU文件检测、Root应用包名检测、系统特征检测

- **Frida注入环境**：ptrace追踪检测、内存SO特征、端口扫描、进程检测

- **Xposed/LSPosed注入环境**：内存SO特征、挂载目录特征识别

## 3、技术架构分层（固定开发结构）

### 3\.1 Native C层（核心安全层，不可Hook篡改）

承担**最终风险判定核心逻辑**，包含4大能力：

1. 读取 /proc/self/status 解析 TracerPid，检测是否被 ptrace 附加（Frida核心特征）

2. 读取 /proc/self/maps 检测内存恶意SO：frida\-agent、frida\-gadget、lsposed、xposed

3. 遍历系统SU路径，识别原生Root文件

4. 统一输出结构化风险实体（Frida风险、Xposed风险、Root风险）

### 3\.2 Kotlin辅助层（特征补充上报）

不做主判定，只补充弱特征，丰富服务端风控维度：

1. Frida默认27042端口连通性检测

2. 遍历/proc进程匹配frida\-server进程名

3. 检测Magisk、SuperSU等Root管理App包名

4. 系统SU文件二次校验

### 3\.3 服务端层（最终风控决策）

客户端**不做任何拦截、弹窗、封禁逻辑**，仅上报所有特征，服务端配置权重打分：

- Native层命中 = 高风险（权重极高）

- 辅助特征命中 = 中低风险（加权累计）

- 多特征同时命中 = 判定作弊设备、改机设备

## 4、固定代码文件结构（AI复刻直接新建）

```Plain Text
src/main/cpp/
 ├─ security_detect.c   // 核心Native检测代码
 ├─ CMakeLists.txt     // NDK编译配置

src/main/java/包名/security/
 ├─ RiskInfo.kt               // Native返回数据实体
 ├─ SecurityFullReport.kt     // 全量上报实体
 └─ NativeSecurityDetector.kt // JNI调用 + 辅助特征采集
```

## 5、核心实现规范（AI严格遵守）

### 5\.1 Native C层必须实现的4个工具方法

- `file_contains`：读取文件匹配关键字

- `get_tracer_pid`：获取当前进程追踪PID

- `maps_contain_lib`：检测内存是否存在恶意SO

- 三大检测入口：detect\_frida / detect\_xposed / detect\_root\_native

### 5\.2 强制检测特征清单

**Frida特征：**

- TracerPid \> 0

- libfrida\-agent\.so

- libfrida\-gadget\.so

**Xposed/LSPosed特征：**

- libxposed\_agent\.so

- liblsposed\.so / liblspd\.so

- /data/adb/lspd 挂载节点

**Root特征：**

- 遍历主流su系统路径是否存在

### 5\.3 Kotlin辅助检测强制清单

- 27042端口连通性检测

- 遍历/proc目录检测frida\-server进程

- 检测Magisk/SuperSU包名

- 二次校验SU文件路径

## 6、输出数据结构（固定上报字段）

最终上报服务端的结构体固定包含：

1. **nativeRisk**：三层核心风险（fridaRisk、xposedRisk、rootNativeRisk）

2. **javaSuFileExist**：Java层SU文件存在性

3. **fridaPortOpen**：Frida端口是否开放

4. **rootAppPackageList**：设备已安装Root工具包名

## 7、对抗绕过与方案局限（必须备注）

1\. Shamiko、Zygisk、KernelSU内核级隐藏，可篡改/proc数据绕过客户端检测，**客户端无法根治**，依赖服务端大数据画像。

2\. 所有Native代码必须开启商业加固（虚拟化），防止SO被dump、Hook。

3\. 禁止本地任意布尔值拦截，所有风控决策交给服务端。

## 8、编译配置要求

Module层build\.gradle必须配置CMake编译，关联cpp目录，编译生成 securitydetect\.so。

## 9、调用方式（固定）

App启动、关键业务接口前置，调用：

```Plain Text
val report = NativeSecurityDetector.collectAllRiskData()
// 直接将report完整JSON上传服务端
```

## 10、方案优势总结

- 防Java层Hook：核心逻辑Native实现

- 防单点绕过：多维交叉检测

- 安全合规：客户端无强拦截，靠云端打分，对抗性拉满

- 全覆盖：Root / Frida / LSPosed 全作弊环境识别

> （注：部分内容可能由 AI 生成）
