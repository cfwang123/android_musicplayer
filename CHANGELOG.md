# Changelog / 更新日志

## 1.0.2 — 2026-07-21

### English
- Fix startup crash `no such column` on MediaStore scan after switching phones / OEM Android (adaptive columns + fallback for `RELATIVE_PATH` / `_data`)
- Browse list: tapping a track no longer jumps the scroll position
- E-ink / mono screens: current-track row uses a slightly deeper blue-gray background (color screens keep soft theme highlight)
- List UI: hide second line when subtitle is only the generic “Audio”; folder rows no longer show “N tracks” on the right
- Settings: check for updates from GitHub Releases (`android_musicplayer`), download APK and install
- ViewPager: reduce accidental horizontal page switches while vertically scrolling the list / lyrics (higher touch slop + vertical-first intercept)

### 中文
- 修复换机 / 部分 OEM 上 MediaStore 扫描启动报错 `no such column`（列投影按 API 适配，并对 `RELATIVE_PATH` / `_data` 失败回退）
- 1 屏列表点歌：不再自动滚动跳变，保持当前浏览位置
- 墨水屏 / 灰度屏：当前曲高亮为比彩屏稍深的蓝灰底（彩屏仍用主题淡色高亮）
- 列表：第二行仅为「音频」时不显示；文件夹项去掉右侧「xx 首」
- 设置：从 GitHub Releases（`android_musicplayer`）检查更新，下载 APK 并安装
- 三屏滑动：列表 / 歌词竖滑时不易误触横滑进下一屏（提高 touchSlop + 竖直意图优先）

---

## 1.0.1

### English
- Public baseline: folder browse, playlists, background playback, lyrics, equalizer, CN/EN UI

### 中文
- 公开基线：文件夹浏览、播放列表、后台播放、歌词、均衡器、中英文界面
