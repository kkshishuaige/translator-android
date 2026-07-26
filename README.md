# 同声传译 · Whisper + Gemma 4 本地版

## 架构

```
手机端 (完全离线)
┌──────────────────────────────────────────┐
│ AudioRecord 持续录音 (16kHz PCM)          │
│         ↓                                │
│  Whisper.cpp (本地)                       │
│  ┌────────────────────────────────────┐  │
│  │ ggml-base.bin (多语言语音识别)       │  │
│  │ 支持 99 种语言识别                   │  │
│  │ 原始音频直接输入 → 文本输出          │  │
│  └────────────────────────────────────┘  │
│         ↓ 识别文本                       │
│  Gemma 4 (ML Kit Prompt API)             │
│  ┌────────────────────────────────────┐  │
│  │ 本地翻译引擎                        │  │
│  │ 6种语言任意互译                     │  │
│  │ 完全离线，无需网络                   │  │
│  └────────────────────────────────────┘  │
│         ↓ 翻译结果                       │
│ 追加显示识别+翻译结果                     │
│ 历史记录                                 │
└──────────────────────────────────────────┘
```

## ⚡ 特点

- **Whisper 本地语音识别** — whisper.cpp 编译进 APK，6种语言精准识别
- **Gemma 4 本地翻译** — 通过 Google ML Kit 调用 Gemma 4，翻译完全离线
- **6种语言互译** — 中文/English/Русский/العربية/Español/Français
- **99种语言识别** — Whisper 多语言模型支持阿拉伯语、俄语等
- **连续同传** — 点击开始→说话→自动识别翻译→追加显示→继续监听
- **完全离线** — 所有推理在手机本地完成，无需联网

## 首次使用

### 1️⃣ 下载 Whisper 模型（必需）

APK 不包含模型文件（~148MB），需要手动下载并放到手机：

| 推荐模型 | 大小 | 下载链接 |
|---------|------|---------|
| **base（推荐）** | **148MB** | [hf-mirror.com](https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-base.bin) |
| small | 488MB | [hf-mirror.com](https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-small.bin) |
| medium | 1.5GB | [hf-mirror.com](https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin) |

下载后将 `ggml-base.bin` 放到手机 `Download/` 目录。

### 2️⃣ 下载 Gemma 4 模型（可选，用于翻译）

如果不需要翻译，也可以只用 Whisper 做语音识别（纯转写）。

如需翻译：
1. 在手机下载安装 **Google AI Edge Gallery**（应用商店搜索）
2. 打开 App，找到 **Gemma-4-E2B-it**（约 2.54GB），点击下载
3. 下载完成后，模型会自动解压到 `/data/data/com.google.android.edgegallery/files/models/`

### 3️⃣ 安装 APK

从 GitHub Actions 下载最新 APK 安装：

https://github.com/kkshishuaige/translator-android/actions

### 4️⃣ 使用

1. 打开 App
2. 在"模型路径"输入框中填入模型文件路径（默认 `/storage/emulated/0/Download/ggml-base.bin`）
3. 点击 **加载模型**，等待 Whisper 模型就绪
4. 等待 Gemma 4 就绪（状态显示绿色"就绪"）
5. 选择源语言和目标语言（6种语言互译）
6. 点击 **🎤 开始同传**
7. 说话 → Whisper 识别 → Gemma 4 翻译 → 追加显示
8. 点击 **⏹ 停止同传** 结束

## 模型文件存放位置

推荐路径：`/storage/emulated/0/Download/ggml-base.bin`

也可以放在其他目录，在 App 中输入对应的路径即可。

## 项目结构

```
translator-android-simple/
├── app/
│   ├── build.gradle.kts          # 依赖 + NDK/CMake 配置
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/
│       │   ├── CMakeLists.txt     # NDK 构建脚本
│       │   ├── whisper-jni.cpp    # Kotlin ↔ C++ JNI 桥接
│       │   ├── whisper.cpp        # whisper.cpp 核心
│       │   ├── include/whisper.h
│       │   └── ggml/              # GGML 张量库
│       ├── java/com/translator/app/
│       │   ├── MainActivity.kt    # 主界面 + 录音 + Whisper + Gemma 4
│       │   └── WhisperModel.kt    # JNI 封装
│       └── res/
├── .github/workflows/build.yml   # CI: 编译 APK
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```
