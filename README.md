# 同声传译 · Gemma 4 本地版

## 架构

```
手机端 (完全离线)
┌─────────────────────────────────────┐
│ AudioRecord 持续录音                 │
│         ↓                            │
│  Gemma 4 E2B (本地端到端)            │
│  ┌─────────────────────────────────┐ │
│  │ 语音识别 + 翻译 一步完成         │ │
│  │ 支持6种语言任意互译              │ │
│  │ 140+种语言识别能力               │ │
│  │ 完全离线，无需网络               │ │
│  └─────────────────────────────────┘ │
│         ↓                            │
│ 追加显示识别+翻译结果                │
│ 历史记录                             │
└─────────────────────────────────────┘
```

## ⚡ 特点

- **Gemma 4 E2B 端到端** — 音频直接输入，直接出翻译结果，不需要ASR+翻译两步走
- **完全离线** — 不依赖服务器，所有推理在手机本地完成
- **6种语言互译** — 中文/English/Русский/العربية/Español/Français
- **140+种语言识别** — Gemma 4 原生支持，阿拉伯语也支持
- **连续同传** — 点击开始→说话→自动识别翻译→追加显示→继续监听

## 首次使用

### 1️⃣ 下载 Gemma 4 模型（必需）

APK 不包含模型文件（~2.54GB），需要手动通过 Google AI Edge Gallery 下载：

1. 在手机下载安装 **Google AI Edge Gallery**（应用商店搜索）
2. 打开 App，找到 **Gemma-4-E2B-it**（约 2.54GB），点击下载
3. 下载完成后，模型会自动解压到 `/data/data/com.google.android.edgegallery/files/models/`

### 2️⃣ 安装 APK

从 GitHub Actions 下载最新 APK 安装：

https://github.com/kkshishuaige/translator-android/actions

### 3️⃣ 使用

1. 打开 App
2. 等待 Gemma 4 模型初始化完成（状态显示绿色"就绪"）
3. 选择源语言和目标语言（6种语言互译）
4. 点击 **🎤 开始同传**
5. 说话 → Gemma 4 本地端到端识别翻译 → 追加显示
6. 点击 **⏹ 停止同传** 结束

## 项目结构

```
translator-android-simple/
├── app/
│   ├── build.gradle.kts          # 依赖 ML Kit GenAI Prompt API
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/translator/app/
│       │   └── MainActivity.kt   # 主界面 + 录音 + Gemma 4 推理
│       └── res/
├── .github/workflows/build.yml   # CI: 编译 APK
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```
