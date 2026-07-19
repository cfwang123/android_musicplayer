# 音乐播放器

本机媒体文件夹浏览与后台播放的 Android 应用。

[English](README.md) · 更细的开发说明见 [DEVELOPMENT.md](DEVELOPMENT.md)

## 截图

| 1 屏 · 浏览 / 播放列表 | 2 屏 · 播放 | 3 屏 · 歌词 |
|:---:|:---:|:---:|
| <img src="screenshots/1.jpg" width="240" alt="文件夹浏览与播放列表" /> | <img src="screenshots/2.jpg" width="240" alt="播放页" /> | <img src="screenshots/3.jpg" width="240" alt="歌词页" /> |

- **1 屏**：文件夹 / 播放列表浏览，当前曲高亮，底栏控制  
- **2 屏**：封面、进度、收藏与加入列表、倍速 / 定时、迷你歌词  
- **3 屏**：全屏歌词（中外文对照）、跟唱高亮  

更多截图见 [`screenshots/`](screenshots/)。

## 简介

- **包名**：`com.whj.music`
- **语言**：Kotlin
- **minSdk 24** / **targetSdk 34**
- **版本**：1.0.1（`versionCode` 2）
- **构建**：Gradle 8.4 + AGP 8.3.2，JDK 17

## 主要功能

| 模块 | 说明 |
|------|------|
| 三屏滑动 | 文件夹浏览 / 播放器 / 歌词 |
| 媒体扫描 | MediaStore 音频 + 视频（仅播声音） |
| 主目录 | 可配置根目录入口（系统选目录） |
| 播放列表 | 用户自建列表；加入 / 移出 / 拖动排序；列表数据本地持久化 |
| 多选操作 | 1 屏长按多选：批量加入列表、批量删除文件；列表内批量移出 |
| 播放模式 | 单曲 / 文件夹循环 / 下一文件夹 / 随机 |
| 进度记忆 | 全局上次曲目；按文件夹记忆；启动定位 |
| 歌词 | 同目录同名 `.lrc`，卡拉 OK 进度、滚动跳转 |
| 定时关闭 / 倍速 | 支持 |
| 收藏 | 列表与播放页红心 |
| 均衡器 | 默认关闭，底栏按钮 |
| 锁屏控制 | MediaSession |
| 多语言 | 中文 / English / 跟随系统 |
| 主题 | 白底淡色多皮肤 |
| 文件操作 | 多选批量删除（系统确认）；加入播放列表 |

### 播放列表用法（简要）

1. **1 屏根目录** → 顶部「播放列表」入口；菜单可新建列表  
2. **长按文件**进入多选 →「加入列表」到已有列表或「新播放列表」；或批量删除文件  
3. **进入某个播放列表**后长按多选 →「移出列表」；多选后可用行尾手柄**拖动排序**  
4. **2 屏右上**「加入列表」图标 → 下拉选择目标列表或新建  

播放列表仅保存引用与顺序，删除列表不会删除设备上的媒体文件。

## 快速开始

### 环境

- Android SDK（API 34）
- JDK 17
- `adb` 在 PATH 中
- 真机开启 USB 调试（安装需允许「USB 安装」）

配置 `local.properties`（本机路径，已在 `.gitignore` 中忽略）：

```properties
sdk.dir=你的/Android/Sdk路径
```

### 用脚本（推荐）

在项目根目录 `music-player/`：

```powershell
# 按源码修改时间决定是否编 release，再安装并启动
node build.js install

# 强制重新编译 release 后安装
node build.js install --force

# 仅编译 release / debug
node build.js build --release
node build.js build --debug

# 编译 debug 并安装启动
node build.js run

# 列出设备
node build.js devices
```

### 用 Gradle

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\music1.0.1.apk
```

Release 使用 `keystore.properties` + `release.keystore` 签名（与 debug 共用时可覆盖安装保留数据）；密钥文件勿提交仓库，见 `keystore.properties.example`。

## 目录结构（简要）

```
music-player/
├── app/src/main/
│   ├── java/com/whj/music/
│   │   ├── data/            # 浏览、收藏、播放列表、设置等
│   │   ├── player/          # 前台服务、均衡器
│   │   ├── ui/              # 列表适配器等
│   │   └── MainActivity.kt
│   └── res/                 # 布局、主题、多语言
├── screenshots/             # 界面截图（README 展示）
├── build.js                 # 构建 / 安装脚本
├── DEVELOPMENT.md           # 详细开发笔记
├── keystore.properties.example
├── README.md                # 本文件（中文）
└── README_en.md             # English
```

## 权限说明

| 权限 | 用途 |
|------|------|
| 读媒体音频/视频 | 浏览与播放 |
| 前台服务 / 媒体播放 | 后台播与通知 |
| 通知 | 播放通知与锁屏 |
| 可选忽略电池优化 | 降低息屏被杀概率 |

Android 11+ 批量删除文件会走系统确认对话框。

## 许可证

按仓库所有者约定使用。
