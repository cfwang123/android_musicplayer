# Changelog / 更新日志

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
