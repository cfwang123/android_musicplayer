package com.whj.music

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
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
import com.whj.music.ui.AppTheme
import com.whj.music.ui.AppThemeSkin
import com.whj.music.util.AppUpdate
import com.whj.music.util.LocaleHelper
import com.whj.music.util.StoragePathUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    private val modes = arrayOf(
        PlayMode.REPEAT_ONE,
        PlayMode.REPEAT_FOLDER,
        PlayMode.NEXT_FOLDER,
        PlayMode.SHUFFLE,
    )
    private val lyricSeekMinutes = (5..30 step 5).toList()
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
        binding.switchAutoLocate.isChecked = AppSettings.autoLocateOnBrowse(this)
        binding.switchResume.isChecked = AppSettings.resumeOnStart(this)
        binding.switchResumeFolder.isChecked = AppSettings.resumeFolderOnOpen(this)

        binding.btnAddRoot.setOnClickListener { openSystemFolderPicker() }
        binding.btnBatteryOpt.setOnClickListener { openBatteryOptimizationSettings() }
        binding.tvAppVersion.text = getString(
            R.string.settings_app_version,
            AppUpdate.currentVersionName(),
        )
        binding.btnCheckUpdate.setOnClickListener { onCheckUpdateClick() }
        refreshRootFoldersUi()
        refreshBatteryOptUi()
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryOptUi()
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
        AppSettings.setAutoLocateOnBrowse(this, binding.switchAutoLocate.isChecked)
        AppSettings.setResumeOnStart(this, binding.switchResume.isChecked)
        AppSettings.setResumeFolderOnOpen(this, binding.switchResumeFolder.isChecked)

        val skin = skins.getOrElse(binding.spinnerTheme.selectedItemPosition) { AppThemeSkin.BLUE }
        val themeChanged = skin.key != initialThemeKey
        AppSettings.setThemeKey(this, skin.key)

        val lang = languageKeys.getOrElse(binding.spinnerLanguage.selectedItemPosition) { LocaleHelper.SYSTEM }
        val langChanged = lang != initialLanguageKey
        AppSettings.setLanguageKey(this, lang)
        if (langChanged) {
            initialLanguageKey = lang
            LocaleHelper.apply(lang)
        }

        if (themeChanged) {
            initialThemeKey = skin.key
            recreate()
        } else if (langChanged) {
            // 语言变更由 AppCompat 重建界面
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
