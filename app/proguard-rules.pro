# ===================================================================
# SessionClock ProGuard / R8 规则
# MVP 阶段 release 构建暂未开启混淆（isMinifyEnabled = false）。
# Sprint 4 开启 R8 时，本工程无反射框架、无 JSON 库，默认规则即可；
# 以下保留规则为届时预留。
# ===================================================================

# DataStore / Coroutines 自带 consumer rules，通常无需额外配置。
# 若后续引入 Room 等反射/生成代码组件，在此追加 keep 规则。

# 保留引擎状态机相关类的泛型签名（协程 Channel/Flow 调试友好）
-keepattributes Signature, InnerClasses, EnclosingMethod

# 行号与源文件信息，便于线上崩溃定位（离线包可保留）
-keepattributes SourceFile,LineNumberTable
