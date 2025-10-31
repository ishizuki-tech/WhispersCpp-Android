# WhispersCpp-Android

An **Android + JNI integration template** for [whisper.cpp](https://github.com/ggerganov/whisper.cpp), optimized for **on-device speech recognition** in English, Japanese, and Swahili.  
This repository provides a minimal yet production-ready setup combining Kotlin (Jetpack Compose) and native C++ for real-time transcription.

---

## 🎯 Features

- ✅ Fully offline speech-to-text using `whisper.cpp`
- 🎙️ Real-time recording and transcription (16 kHz PCM)
- 🧩 JNI bridge for model loading and inference
- ⚙️ CMake-based native build (NDK)
- 📱 Kotlin + Jetpack Compose frontend
- 🌍 Multi-language model support (English, Japanese, Swahili)
- 🔋 Optimized for FP16 / NEON ARM acceleration

---

## 🏗️ Project Structure

```
WhispersCpp-Android/
 ├── app/                 # Android app (Jetpack Compose UI)
 ├── nativelib/           # JNI C/C++ bridge + whisper.cpp core
 ├── gradle/              # Gradle wrapper
 ├── .gitignore
 ├── .gitmodules
 ├── build.gradle.kts
 ├── settings.gradle.kts
 ├── LICENSE
 └── README.md
```

---

## ⚙️ Build Setup

### 1. Prerequisites

- **Android Studio** 2025.1+
- **NDK** 26.2+
- **CMake** 3.22+
- **Kotlin** 2.2+
- Device with **ARMv8 / NEON support**

---

### 2. Clone with Submodules

```bash
git clone --recursive https://github.com/ishizuki-tech/WhispersCpp-Android.git
```

If you already cloned without submodules:
```bash
git submodule update --init --recursive
```

---

### 3. Model Placement

Download a Whisper model (e.g., `ggml-base-q4_0.bin`) from:
> [https://huggingface.co/ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp)

Place it under:
```
app/src/main/assets/models/model-q4_0.bin
```

---

### 4. Build & Run

In **Android Studio**:
1. Select **Build → Make Project**
2. Connect a device or emulator
3. Run **“app”**

You can also build from CLI:
```bash
./gradlew assembleDebug
```

---

## 🧠 Native Layer Overview

The JNI bridge (`WhisperLib.c`) wraps:
- `whisper_init_from_file()` for model loading
- `whisper_full()` for transcription
- Global reference management for thread-safe use

Native source paths (CMakeLists.txt):
```cmake
${WHISPER_LIB_DIR}/src/whisper.cpp
${WHISPER_LIB_DIR}/ggml/src/ggml.c
${CMAKE_CURRENT_SOURCE_DIR}/WhisperLib.c
```

---

## 📦 Dependencies

- [whisper.cpp](https://github.com/ggerganov/whisper.cpp)
- Android Jetpack Compose
- Kotlin Coroutines
- Android NDK + CMake

---

## 🧪 Testing

Basic sanity check:
```bash
adb logcat | grep whisper
```

You should see logs like:
```
I/JNI-Whisper: model loaded successfully
I/JNI-Whisper: transcription started...
```

---

## ⚡ Performance Tips

| Device | Model | Avg Latency | Notes |
|--------|--------|-------------|-------|
| Pixel 8 | base-q4_0 | ~1.2× real-time | CPU only |
| Galaxy S23 | small-q4_0 | ~0.9× real-time | FP16 |
| Redmi Note 12 | tiny-q8_0 | ~1.4× real-time | low RAM |

---

## 🪶 License

This project is licensed under the [MIT License](LICENSE).

You are free to use, modify, and distribute this code in your own apps.

---

## 👤 Author

**Shu Ishizuki**  
📍 Seattle, WA  
🔗 [https://github.com/ishizuki-tech](https://github.com/ishizuki-tech)

---

## 💬 Future Work

- Add streaming transcription support
- Integrate ONNX Runtime backend
- Enable dynamic model download
- Add Gemma / Llama.cpp interop layer

---

> _“Run Whisper anywhere — no cloud required.”_ 🌀
