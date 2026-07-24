package com.whj.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.whj.music.data.AppSettings
import com.whj.music.data.MediaTreeCache
import com.whj.music.data.PlayModeStore
import com.whj.music.databinding.ActivitySettingsBinding
import com.whj.music.model.PlayMode
import com.whj.music.player.MusicPlayerService
import com.whj.music.ui.AppTheme
import com.whj.music.ui.AppThemeSkin
import com.whj.music.util.AppUpdate
import com.whj.music.util.IdleAutoCloser
import com.whj.music.util.LocaleHelper
import com.whj.music.util.StoragePathUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    /** 退出设置时绑定服务，把音量归一化参数应用到当前曲 */
    private var playerService: MusicPlayerService? = null
    private var playerBound = false

    private val playerConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playerService = (service as MusicPlayerService.LocalBinder).getService()
            playerBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playerService = null
            playerBound = false
        }
    }

    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    private val modes = arrayOf(
        PlayMode.REPEAT_ONE,
        PlayMode.REPEAT_FOLDER,
        PlayMode.NEXT_FOLDER,
        PlayMode.SHUFFLE,
    )
    private val lyricSeekMinutes = (5..30 step 5).toList()
    /** 自动关闭：分钟；0=不关闭；默认 120 */
    private val autoCloseMinutesOptions = listOf(0, 30, 60, 120, 180, 240, 360, 480, 720)
    private val skins = AppThemeSkin.entries.toTypedArray()
    private val languageKeys = arrayOf(LocaleHelper.SYSTEM, LocaleHelper.ZH, LocaleHelper.EN)
    private var initialThemeKey: String = "blue"
    private var initialLanguageKey: String = LocaleHelper.SYSTEM

    /** 从「未知应用安装」设置返回后继续安装 */
    private var pendingInstallApk: File? = null
    private var downloadCancel: AtomicBoolean? = null
    private var checkingUpdate = false
    private var downloadingUpdate = false

    private val openTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        handlePickedTree(uri)
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val file = pendingInstallApk
        pendingInstallApk = null
        if (file != null && file.isFile && AppUpdate.canInstallPackages(this)) {
            tryInstallApk(file)
        } else if (file != null) {
            Toast.makeText(this, R.string.update_install_permission, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initialThemeKey = AppSettings.themeKey(this)
        initialLanguageKey = AppSettings.languageKey(this)
        binding.settingsBackBtn.setOnClickListener { finish() }

        val themeLabels = skins.map { getString(it.labelRes) }
        binding.spinnerTheme.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            themeLabels,
        )
        binding.spinnerTheme.setSelection(
            skins.indexOfFirst { it.key == initialThemeKey }.coerceAtLeast(0),
        )
        // 主题：选中后立即保存并 recreate 本页（不关设置、不回到播放页抢播）
        var themeSpinnerReady = false
        binding.spinnerTheme.post { themeSpinnerReady = true }
        binding.spinnerTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (!themeSpinnerReady) return
                val skin = skins.getOrElse(position) { AppThemeSkin.BLUE }
                if (skin.key == AppSettings.themeKey(this@SettingsActivity)) return
                AppSettings.setThemeKey(this@SettingsActivity, skin.key)
                initialThemeKey = skin.key
                // 返回主界面时按新主题重建，且不自动恢复播放
                MainActivity.skipAutoResumeAfterSettings = true
                recreate()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val languageLabels = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_zh),
            getString(R.string.language_en),
        )
        binding.spinnerLanguage.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languageLabels,
        )
        binding.spinnerLanguage.setSelection(
            languageKeys.indexOf(initialLanguageKey).coerceAtLeast(0),
        )

        val modeLabels = arrayOf(
            getString(R.string.mode_repeat_one),
            getString(R.string.mode_repeat_folder),
            getString(R.string.mode_next_folder),
            getString(R.string.mode_shuffle),
        )
        binding.spinnerPlayMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modeLabels,
        )
        val curMode = AppSettings.defaultPlayMode(this)
        binding.spinnerPlayMode.setSelection(modes.indexOf(curMode).coerceAtLeast(0))

        val speedLabels = speeds.map { formatSpeed(it) + "×" }
        binding.spinnerSpeed.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            speedLabels,
        )
        val curSpeed = AppSettings.defaultPlaybackSpeed(this)
        val speedIdx = speeds.indexOfFirst { it == curSpeed }.takeIf { it >= 0 }
            ?: speeds.indexOfFirst { kotlin.math.abs(it - curSpeed) < 0.01f }.coerceAtLeast(0)
        binding.spinnerSpeed.setSelection(speedIdx)

        val seekLabels = lyricSeekMinutes.map { getString(R.string.settings_minutes_unit, it) }
        binding.spinnerLyricSeek.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            seekLabels,
        )
        val curSeek = AppSettings.lyricSentenceSeekMaxMinutes(this)
        val seekIdx = lyricSeekMinutes.indexOf(curSeek).takeIf { it >= 0 }
            ?: lyricSeekMinutes.indexOf(10).coerceAtLeast(0)
        binding.spinnerLyricSeek.setSelection(seekIdx)

        binding.switchKeepSpeed.isChecked = AppSettings.keepSpeedAcrossTracks(this)
        binding.switchVolumeNormalize.isChecked = AppSettings.volumeNormalizeEnabled(this)
        binding.editVolumeTarget.setText(
            formatVolumeTargetInput(AppSettings.volumeTargetUi(this)),
        )
        binding.switchAutoLocate.isChecked = AppSettings.autoLocateOnBrowse(this)
        binding.switchResume.isChecked = AppSettings.resumeOnStart(this)
        binding.switchResumeFolder.isChecked = AppSettings.resumeFolderOnOpen(this)

        val autoCloseLabels = autoCloseMinutesOptions.map { minutes ->
            when {
                minutes <= 0 -> getString(R.string.settings_auto_close_never)
                minutes == 30 -> getString(R.string.settings_auto_close_half_hour)
                minutes % 60 == 0 -> getString(R.string.settings_auto_close_hours, minutes / 60)
                else -> getString(R.string.settings_minutes_unit, minutes)
            }
        }
        binding.spinnerAutoClose.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            autoCloseLabels,
        )
        val curAutoClose = AppSettings.autoCloseMinutes(this)
        val autoCloseIdx = autoCloseMinutesOptions.indexOf(curAutoClose).takeIf { it >= 0 }
            ?: autoCloseMinutesOptions.indexOf(120).coerceAtLeast(0)
        binding.spinnerAutoClose.setSelection(autoCloseIdx)

        binding.btnAddRoot.setOnClickListener { openSystemFolderPicker() }
        binding.btnBatteryOpt.setOnClickListener { openBatteryOptimizationSettings() }
        binding.tvAppVersion.text = getString(
            R.string.settings_app_version,
            AppUpdate.currentVersionName(),
        )
        binding.btnCheckUpdate.setOnClickListener { onCheckUpdateClick() }
        binding.btnLicense.setOnClickListener { showLicenseDialog() }
        refreshRootFoldersUi()
        refreshBatteryOptUi()
    }

    /**
     * 退出设置时解析并保存目标平均音量（界面 0～100），非法则提示并保留原值。
     * 内部换算为 RMS 0～0.25；保存后由 [saveAll] 统一 [MusicPlayerService.reapplyVolumeNormalize]。
     */
    private fun parseAndSaveVolumeTarget() {
        val raw = binding.editVolumeTarget.text?.toString()?.trim().orEmpty()
        val parsed = raw.replace(',', '.').toFloatOrNull()
        val ui = if (parsed == null) {
            Toast.makeText(this, R.string.settings_volume_target_invalid, Toast.LENGTH_SHORT).show()
            AppSettings.volumeTargetUi(this)
        } else {
            parsed.coerceIn(
                AppSettings.MIN_VOLUME_TARGET_UI,
                AppSettings.MAX_VOLUME_TARGET_UI,
            )
        }
        AppSettings.setVolumeTargetUi(this, ui)
        binding.editVolumeTarget.setText(formatVolumeTargetInput(ui))
    }

    private fun formatVolumeTargetInput(ui: Float): String {
        // 0～100，优先整数；有小数则最多 1 位
        val rounded = kotlin.math.round(ui * 10f) / 10f
        return if (kotlin.math.abs(rounded - rounded.toInt()) < 0.05f) {
            rounded.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", rounded)
        }
    }

    private fun showLicenseDialog() {
        val text = runCatching {
            assets.open("LICENSE").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.settings_license_load_fail)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val maxH = (resources.displayMetrics.heightPixels * 0.65f).toInt()
        val scroll = android.widget.ScrollView(this).apply {
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextIsSelectable(true)
            setTextColor(
                AppTheme.resolveColor(this@SettingsActivity, R.attr.colorTextPrimary, 0xFF2C3E50.toInt()),
            )
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll.addView(
            tv,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        scroll.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            maxH,
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_license_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        // 绑定播放服务：退出设置（onPause/saveAll）时应用到当前曲
        val intent = Intent(this, MusicPlayerService::class.java)
        bindService(intent, playerConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (playerBound) {
            try {
                unbindService(playerConnection)
            } catch (_: Exception) {
                // ignore
            }
            playerBound = false
            playerService = null
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryOptUi()
        IdleAutoCloser.notifyUiActivity(this)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null &&
            (ev.actionMasked == MotionEvent.ACTION_DOWN ||
                ev.actionMasked == MotionEvent.ACTION_UP)
        ) {
            IdleAutoCloser.notifyUiActivity(this)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        downloadCancel?.set(true)
        super.onDestroy()
    }

    private fun onCheckUpdateClick() {
        if (checkingUpdate || downloadingUpdate) return
        checkingUpdate = true
        binding.btnCheckUpdate.isEnabled = false
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AppUpdate.checkLatest()
            }
            checkingUpdate = false
            binding.btnCheckUpdate.isEnabled = true
            if (isFinishing) return@launch
            when (result) {
                is AppUpdate.CheckResult.UpdateAvailable -> showUpdateDialog(result.info)
                is AppUpdate.CheckResult.UpToDate -> {
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.update_up_to_date, result.current),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                is AppUpdate.CheckResult.Error -> {
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.update_check_failed, result.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun showUpdateDialog(info: AppUpdate.ReleaseInfo) {
        val body = info.body.trim().ifBlank { "—" }
        val msg = getString(
            R.string.update_available_msg,
            AppUpdate.currentVersionName(),
            info.versionName,
            body.take(800),
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title, info.versionName))
            .setMessage(msg)
            .setPositiveButton(R.string.update_download) { _, _ ->
                startDownloadAndInstall(info)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startDownloadAndInstall(info: AppUpdate.ReleaseInfo) {
        if (downloadingUpdate) return
        downloadingUpdate = true
        binding.btnCheckUpdate.isEnabled = false
        val cancel = AtomicBoolean(false)
        downloadCancel = cancel
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_check_update)
            .setMessage(getString(R.string.update_downloading, 0))
            .setNegativeButton(R.string.cancel) { _, _ -> cancel.set(true) }
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AppUpdate.downloadApk(
                    info = info,
                    destDir = AppUpdate.updateCacheDir(this@SettingsActivity),
                    cancel = cancel,
                    onProgress = { pct ->
                        runOnUiThread {
                            if (progressDialog.isShowing) {
                                progressDialog.setMessage(
                                    getString(R.string.update_downloading, pct),
                                )
                            }
                        }
                    },
                )
            }
            downloadingUpdate = false
            downloadCancel = null
            binding.btnCheckUpdate.isEnabled = true
            if (progressDialog.isShowing) progressDialog.dismiss()
            if (isFinishing) return@launch
            result.fold(
                onSuccess = { file -> ensureInstallPermissionThenInstall(file) },
                onFailure = { e ->
                    if (e is InterruptedException || cancel.get()) {
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.update_cancelled,
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(
                                R.string.update_download_failed,
                                e.message ?: e.javaClass.simpleName,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            )
        }
    }

    private fun ensureInstallPermissionThenInstall(apkFile: File) {
        if (AppUpdate.canInstallPackages(this)) {
            tryInstallApk(apkFile)
            return
        }
        pendingInstallApk = apkFile
        AlertDialog.Builder(this)
            .setMessage(R.string.update_install_permission)
            .setPositiveButton(R.string.update_install_permission_btn) { _, _ ->
                installPermissionLauncher.launch(
                    AppUpdate.installPermissionSettingsIntent(this),
                )
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                pendingInstallApk = null
            }
            .setOnCancelListener { pendingInstallApk = null }
            .show()
    }

    private fun tryInstallApk(apkFile: File) {
        try {
            Toast.makeText(this, R.string.update_installing, Toast.LENGTH_SHORT).show()
            AppUpdate.installApk(this, apkFile)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.update_download_failed, e.message ?: e.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onPause() {
        saveAll()
        super.onPause()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refreshBatteryOptUi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            binding.batteryOptStatus.text = getString(R.string.settings_battery_opt_ok)
            binding.btnBatteryOpt.visibility = View.GONE
            return
        }
        val ignored = isIgnoringBatteryOptimizations()
        binding.batteryOptStatus.text = if (ignored) {
            getString(R.string.settings_battery_opt_ignored)
        } else {
            getString(R.string.settings_battery_opt_restricted)
        }
        binding.btnBatteryOpt.visibility = if (ignored) View.GONE else View.VISIBLE
        binding.btnBatteryOpt.text = getString(R.string.settings_battery_opt_open)
    }

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, R.string.settings_battery_opt_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // 直接请求「忽略电池优化」
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                // 回退：打开应用详情或电池优化列表
                startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            } catch (_: Exception) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        },
                    )
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        R.string.settings_battery_opt_unavailable,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun openSystemFolderPicker() {
        openTreeLauncher.launch(null)
    }

    private fun handlePickedTree(uri: Uri) {
        // 尽量申请持久权限（便于后续扩展直接读该树）；失败不影响路径解析
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val path = StoragePathUtils.treeUriToRelativePath(uri)
        if (path.isNullOrBlank()) {
            Toast.makeText(this, R.string.settings_root_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val existing = AppSettings.rootFolders(this)
        if (existing.any { it.equals(path, ignoreCase = true) }) {
            // 已存在：仍允许后台刷新索引（对齐 reader 重复绑定）
            Toast.makeText(this, R.string.settings_root_exists, Toast.LENGTH_SHORT).show()
            scanMediaTreeInBackground(showStartToast = true)
            return
        }
        AppSettings.addRootFolder(this, path)
        refreshRootFoldersUi()
        val label = path.trimEnd('/')
        Toast.makeText(
            this,
            getString(R.string.settings_root_added, label),
            Toast.LENGTH_SHORT,
        ).show()
        // 对齐 reader：添加后立即后台整库索引，不阻塞设置页
        Toast.makeText(this, R.string.rescan_media_background, Toast.LENGTH_SHORT).show()
        scanMediaTreeInBackground(showStartToast = false)
    }

    /**
     * 后台全量扫描 MediaStore 目录树缓存（添加主目录 / 重复选择时调用）。
     * @param showStartToast true 时提示「正在扫描…」（重复添加路径）
     */
    private fun scanMediaTreeInBackground(showStartToast: Boolean) {
        if (showStartToast) {
            Toast.makeText(this, R.string.rescan_media_start, Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { MediaTreeCache.fullScan(this@SettingsActivity) }
            }
            if (isFinishing) return@launch
            result.onSuccess { snap ->
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.rescan_media_done, snap.totalFiles),
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure { e ->
                Toast.makeText(
                    this@SettingsActivity,
                    getString(
                        R.string.rescan_media_failed,
                        e.message ?: e.javaClass.simpleName,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun refreshRootFoldersUi() {
        val roots = AppSettings.rootFolders(this)
        binding.rootFoldersContainer.removeAllViews()
        binding.rootFoldersEmpty.visibility = if (roots.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        for (path in roots) {
            val row = inflater.inflate(android.R.layout.simple_list_item_2, binding.rootFoldersContainer, false)
            val text1 = row.findViewById<TextView>(android.R.id.text1)
            val text2 = row.findViewById<TextView>(android.R.id.text2)
            text1.text = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
            text1.setTextColor(AppTheme.resolveColor(this, R.attr.colorTextPrimary, 0xFF2C3E50.toInt()))
            text2.text = path.trimEnd('/')
            text2.setTextColor(AppTheme.resolveColor(this, R.attr.colorTextSecondary, 0xFF7A8B9A.toInt()))
            row.setPadding(0, 12, 0, 12)
            row.setOnClickListener { confirmRemoveRoot(path) }
            binding.rootFoldersContainer.addView(row)
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1,
                )
                setBackgroundColor(0xFFE8F0F6.toInt())
            }
            binding.rootFoldersContainer.addView(divider)
        }
    }

    private fun confirmRemoveRoot(path: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_remove_root)
            .setMessage(path.trimEnd('/'))
            .setPositiveButton(R.string.confirm) { _, _ ->
                AppSettings.removeRootFolder(this, path)
                refreshRootFoldersUi()
                Toast.makeText(this, R.string.settings_root_removed, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveAll() {
        val mode = modes.getOrElse(binding.spinnerPlayMode.selectedItemPosition) { PlayMode.REPEAT_FOLDER }
        AppSettings.setDefaultPlayMode(this, mode)
        PlayModeStore.save(this, mode)

        val speed = speeds.getOrElse(binding.spinnerSpeed.selectedItemPosition) { 1.0f }
        AppSettings.setDefaultPlaybackSpeed(this, speed)

        val seekMin = lyricSeekMinutes.getOrElse(binding.spinnerLyricSeek.selectedItemPosition) { 10 }
        AppSettings.setLyricSentenceSeekMaxMinutes(this, seekMin)

        AppSettings.setKeepSpeedAcrossTracks(this, binding.switchKeepSpeed.isChecked)
        AppSettings.setVolumeNormalizeEnabled(this, binding.switchVolumeNormalize.isChecked)
        parseAndSaveVolumeTarget()
        // 退出设置界面时再应用到当前播放（输入过程中不改音量）
        playerService?.reapplyVolumeNormalize()
        AppSettings.setAutoLocateOnBrowse(this, binding.switchAutoLocate.isChecked)
        AppSettings.setResumeOnStart(this, binding.switchResume.isChecked)
        AppSettings.setResumeFolderOnOpen(this, binding.switchResumeFolder.isChecked)

        val autoCloseMin = autoCloseMinutesOptions.getOrElse(
            binding.spinnerAutoClose.selectedItemPosition,
        ) { 120 }
        AppSettings.setAutoCloseMinutes(this, autoCloseMin)
        // 修改参数后重置空闲计时，避免立刻触发旧阈值
        IdleAutoCloser.notifyUiActivity(this)

        // 主题已在 Spinner 选中时立即应用；此处再写一次以防只 onPause 保存
        val skin = skins.getOrElse(binding.spinnerTheme.selectedItemPosition) { AppThemeSkin.BLUE }
        AppSettings.setThemeKey(this, skin.key)
        initialThemeKey = skin.key

        val lang = languageKeys.getOrElse(binding.spinnerLanguage.selectedItemPosition) { LocaleHelper.SYSTEM }
        val langChanged = lang != initialLanguageKey
        AppSettings.setLanguageKey(this, lang)
        if (langChanged) {
            initialLanguageKey = lang
            LocaleHelper.apply(lang)
            // 语言变更重建设置页；返回主界面时也不要自动开播
            MainActivity.skipAutoResumeAfterSettings = true
            recreate()
        }
    }

    private fun formatSpeed(speed: Float): String {
        return if (speed == speed.toLong().toFloat()) {
            speed.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
        }
    }

}
