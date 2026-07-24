# Changelog / 更新日志

## 1.0.3 — 2026-07-24

### English

**New**
- Volume normalize: measure average RMS (multi-segment sample), scale tracks to a shared loudness; settings toggle (on by default); first play caches by mediaId; loud tracks attenuated via `setVolume`, quiet tracks boosted via `LoudnessEnhancer`
- Settings: target average loudness as manual input (0–0.21 RMS)
- Overflow menu → Details: current track metadata, average RMS, applied gain multiplier
- Browse list: green-dot badge on folder icons that have per-folder playback history
- Folder long-press menu: “Resume last playback” when the folder has history (force-plays, may interrupt current track)
- Playlists → Playback history: virtual folder listing folders with play records (newest first); open jumps to that folder; back returns to history

**Changed**
- Folder resume: auto-resume only when tapping into a folder; going up / back does not resume; no “Resumed” toast; while already playing, only scroll + green-dot last track (no interrupt / no playing-row bg)
- Going up restores the parent list scroll position and still shows a green-dot on that folder’s last track
- Shuffle: shuffle playlist once when entering shuffle (or loading a list in shuffle); next/prev follow that order until shuffle is re-entered
- Lyrics page: current line no longer uses a larger font size (color / bold highlight unchanged)

### 中文

**新增**
- 自动统一音量：多段采样计算曲目平均 RMS，缩放到统一响度；设置开关（默认开）；按 mediaId 缓存，首次播放后复用；过响用 `setVolume` 压低，过轻用 `LoudnessEnhancer` 提升
- 设置：目标平均音量可手动输入（0～0.21 RMS）
- 右上菜单「详细信息」：当前曲目元数据、平均音量、放大倍数
- 浏览列表：有播放记录的目录图标显示绿色小点
- 有记录的文件夹长按菜单：「恢复上次播放」（强制起播，可打断当前播放）
- 播放列表 →「播放记录」虚文件夹：列出有记录的目录（按上次播放时间）；点进真实目录，后退回到播放记录

**变更**
- 打开文件夹恢复播放：仅点击进入目录时；返回上一级不恢复；不再 Toast；**正在播放时只定位+绿色小点标记上次曲目（非播放底色），不抢播**
- 返回上一级：恢复进入子目录前的列表滚动位置，并为上次曲目显示绿点
- 随机模式：进入时对列表打乱一次，上/下一首按该顺序；再次切入随机才重新打乱
- 歌词页：当前句不再放大字号（颜色 / 加粗高亮不变）

## 1.0.2 — 2026-07-21

### English

**New**
- User playlists: create/delete, batch add/remove, drag reorder
- Favorites under Playlists with reorder & unfavorite
- Long-press multi-select: add to playlist, batch delete
- Player “add to playlist” button

**Other**
- Fix startup crash `no such column` on MediaStore scan after switching phones / OEM Android (adaptive columns + fallback for `RELATIVE_PATH` / `_data`)
- Browse list: tapping a track no longer jumps the scroll position
- E-ink / mono screens: current-track row uses a slightly deeper blue-gray background (color screens keep soft theme highlight)
- List UI: hide second line when subtitle is only the generic “Audio”; folder rows no longer show “N tracks” on the right
- Settings: check for updates from GitHub Releases (`android_musicplayer`), download APK and install
- ViewPager: reduce accidental horizontal page switches while vertically scrolling the list / lyrics (higher touch slop + vertical-first intercept)
- Settings: auto-close after UI idle (default 2 hours); timer continues in background / lock-screen playback; resume, touch, keep-screen-on reset it
- Themes: +10 accent skins (rose, indigo, lime, coral, pink, cyan, forest, wine, gold, slate)

### 中文

**新增**
- 用户播放列表：新建/删除、批量加入/移出、拖动排序
- 播放列表下「收藏歌曲」：汇总收藏曲目，支持排序与取消收藏
- 1 屏长按多选：批量加入列表、批量删除文件
- 2 屏「加入列表」按钮

**其它**
- 修复换机 / 部分 OEM 上 MediaStore 扫描启动报错 `no such column`（列投影按 API 适配，并对 `RELATIVE_PATH` / `_data` 失败回退）
- 1 屏列表点歌：不再自动滚动跳变，保持当前浏览位置
- 墨水屏 / 灰度屏：当前曲高亮为比彩屏稍深的蓝灰底（彩屏仍用主题淡色高亮）
- 列表：第二行仅为「音频」时不显示；文件夹项去掉右侧「xx 首」
- 设置：从 GitHub Releases（`android_musicplayer`）检查更新，下载 APK 并安装
- 三屏滑动：列表 / 歌词竖滑时不易误触横滑进下一屏（提高 touchSlop + 竖直意图优先）
- 设置：自动关闭时间（默认 2 小时）；UI 无活动后停播并退出；后台/锁屏播放仍计时；OnResume、触摸、屏幕常亮会重置
- 主题：新增 10 套点缀色（玫瑰、靛青、青柠、珊瑚、樱粉、青空、松绿、酒红、流金、岩灰）

---

## 1.0.1

### English
- Public baseline: folder browse, playlists, background playback, lyrics, equalizer, CN/EN UI

### 中文
- 公开基线：文件夹浏览、播放列表、后台播放、歌词、均衡器、中英文界面
