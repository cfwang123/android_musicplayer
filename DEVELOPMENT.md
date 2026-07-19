# 音乐播放器 — 命令行开发指南

| 项目 | 说明 |
|------|------|
| 包名 | `com.whj.music` |
| 应用名 | 音乐播放器 |
| 语言 | Kotlin |
| 构建 | Gradle 8.4 + Android Gradle Plugin 8.3.2 |
| JDK | 17（Corretto，见 `gradle.properties`） |
| compileSdk / targetSdk | 34 |
| minSdk | 24（Android 7.0+） |
| 路径 | 目录名含中文时需 `android.overridePathCheck=true`（已配置） |

---

## 一、环境

本机已按参考项目 `krdict-android` 对齐：

| 项 | 路径 / 版本 |
|----|-------------|
| Android SDK | `E:\ProgramFiles\androidsdk`（`local.properties`） |
| JDK 17 | `E:\ProgramFiles\HBuilderX\plugins\amazon-corretto` |
| adb | 需在 PATH 中（或使用 SDK `platform-tools`） |

检查：

```powershell
# JDK（项目用 gradle.properties 指定，可不改系统 JAVA_HOME）
& "E:\ProgramFiles\HBuilderX\plugins\amazon-corretto\bin\java.exe" -version

# SDK
Test-Path "E:\ProgramFiles\androidsdk\platforms\android-34"

# 设备
adb devices
```

---

## 二、项目结构

```
music-player/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/whj/music/
│       │   ├── MusicApp.kt
│       │   ├── MainActivity.kt
│       │   ├── data/MusicRepository.kt      # MediaStore 扫描
│       │   ├── model/Song.kt
│       │   ├── player/MusicPlayerService.kt # 前台服务 + MediaPlayer
│       │   └── ui/SongAdapter.kt
│       └── res/
├── gradle/wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties          # 本机 SDK，不入库
├── gradlew.bat
├── build.js                  # 便捷构建/安装脚本
└── DEVELOPMENT.md
```

---

## 三、编译

在项目根目录 `music-player/`：

```powershell
cd D:\VS_Projects\AIPrototype\安卓\music-player

# debug
.\gradlew.bat assembleDebug

# release（未配置签名时使用 debug 签名逻辑外的默认，当前未强制签名）
.\gradlew.bat assembleRelease
```

或使用 Node 脚本：

```powershell
node build.js build --debug
node build.js build          # 默认 release
node build.js clean
node build.js rebuild --debug
```

成功后 APK：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk   # 未配置签名时
```

---

## 四、真机安装与运行

```powershell
# 确认设备
adb devices
# 或
node build.js devices

# 一键：编译 debug + 安装 + 启动
node build.js run

# 多设备时指定序列号
node build.js run -s 你的序列号
```

等价 Gradle / adb：

```powershell
.\gradlew.bat installDebug
adb shell am start -n com.whj.music/.MainActivity
```

查看 logcat（过滤本应用）：

```powershell
adb logcat --pid=$(adb shell pidof -s com.whj.music)
# 或按包名粗过滤
adb logcat | Select-String "whj.music|AndroidRuntime"
```

卸载：

```powershell
adb uninstall com.whj.music
```

---

## 五、功能说明

- 扫描本机音频（MediaStore），列表展示
- 点击曲目播放；底部栏：上一首 / 播放暂停 / 下一首、进度条
- 前台服务 + 通知栏媒体控制（切歌、播放暂停）
- 权限：
  - Android 13+：`READ_MEDIA_AUDIO`、`POST_NOTIFICATIONS`
  - 更低版本：`READ_EXTERNAL_STORAGE`

首次启动需在系统弹窗中授予音频读取权限。

---

## 六、日常改代码流程（纯命令行）

1. 用编辑器改 `app/src/main/...` 源码或资源
2. `.\gradlew.bat assembleDebug` 或 `node build.js build --debug` 编译
3. `node build.js run` 装到手机并启动
4. `adb logcat` 看崩溃与日志

增量编译一般只需 `assembleDebug` / `installDebug`，不必每次 `clean`。

---

## 七、常见问题

### 找不到 SDK

检查 `local.properties` 中 `sdk.dir` 是否与本机一致。

### Gradle 使用错误 JDK

确认 `gradle.properties` 中：

```properties
org.gradle.java.home=E\:\\ProgramFiles\\HBuilderX\\plugins\\amazon-corretto
```

### adb 无设备

- 手机开启开发者选项与 USB 调试
- 换线/口，授权调试弹窗
- `adb kill-server` 后重试 `adb devices`

### 列表为空

手机上无音频文件，或未授予读取权限。点「授予权限」或右上角刷新。
