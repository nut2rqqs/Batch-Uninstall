package com.nut2rqqs.batchuninstall

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.os.Process
import android.provider.Settings
import android.os.storage.StorageManager
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayDeque
import java.util.Locale
import java.text.DateFormat
import java.util.Date

private data class InstalledApp(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val hasLaunchActivity: Boolean,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val apkBytes: Long,
    var cacheBytes: Long? = null
)

class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var summary: TextView
    private lateinit var searchInput: EditText
    private lateinit var includeSystemCheck: CheckBox
    private lateinit var appList: LinearLayout
    private lateinit var sortSpinner: Spinner
    private var sortMode = 0
    private var hasUsageAccess = false

    private val apps = mutableListOf<InstalledApp>()
    private val selectedPackages = linkedSetOf<String>()
    private val uninstallQueue = ArrayDeque<String>()
    private var waitingForUninstallResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = "Batch Uninstall"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(title)

        summary = TextView(this).apply {
            text = "กำลังเตรียมรายการแอป..."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(summary)

        searchInput = EditText(this).apply {
            hint = "ค้นหาชื่อแอปหรือ package"
            setSingleLine(true)
        }
        root.addView(searchInput)

        sortSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf(
                "ชื่อ: A–Z",
                "ขนาดไฟล์: ใหญ่ → เล็ก", "ขนาดไฟล์: เล็ก → ใหญ่",
                "ขนาดแคช: มาก → น้อย", "ขนาดแคช: น้อย → มาก",
                "วันที่ติดตั้ง: ใหม่ → เก่า", "วันที่ติดตั้ง: เก่า → ใหม่",
                "วันที่อัปเดต: ใหม่ → เก่า", "วันที่อัปเดต: เก่า → ใหม่"
            ))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    sortMode = position
                    renderApps()
                }
            }
        }
        root.addView(sortSpinner, fullWidthParams())
        root.addView(actionButton("ตั้งค่าสิทธิ์เพื่ออ่านขนาดแคช") {
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            catch (_: Exception) { Toast.makeText(this, "เปิดหน้าสิทธิ์ไม่ได้", Toast.LENGTH_SHORT).show() }
        }, fullWidthParams())

        includeSystemCheck = CheckBox(this).apply {
            text = "รวมแอประบบ (ดูรายการได้ แต่โหมด Root จะไม่ถอน)"
            setOnCheckedChangeListener { _, _ -> renderApps() }
        }
        root.addView(includeSystemCheck)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(actionButton("สแกนใหม่") { scanApps() }, weightedParams())
        actions.addView(actionButton("เลือกแอปที่เปิดได้") {
            selectedPackages.clear()
            apps.filter { visibleByFilter(it) && it.hasLaunchActivity }
                .forEach { selectedPackages.add(it.packageName) }
            renderApps()
        }, weightedParams())
        actions.addView(actionButton("ล้างเลือก") {
            selectedPackages.clear()
            renderApps()
        }, weightedParams())
        root.addView(actions)

        val uninstallButton = actionButton("ถอนผ่าน Android (ยืนยันทีละแอป)") {
            beginBatchUninstall()
        }.apply {
            textSize = 15f
            isAllCaps = false
        }
        root.addView(uninstallButton, fullWidthParams())

        val rootUninstallButton = actionButton("ถอนแอปที่เลือกด้วย Root / KernelSU") {
            beginRootBatchUninstall()
        }.apply {
            textSize = 15f
            isAllCaps = false
        }
        root.addView(rootUninstallButton, fullWidthParams())

        val note = TextView(this).apply {
            text = "โหมด Root ใช้คำสั่ง pm uninstall --user 0 จึงถอนแอปสำหรับผู้ใช้ปัจจุบัน ไม่ลบไฟล์ APK จากพาร์ทิชันระบบ และข้ามแอประบบเพื่อความปลอดภัย"
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(note)

        val scrollView = ScrollView(this)
        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(appList)
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        searchInput.addTextChangedListener(SimpleTextWatcher { renderApps() })
        scanApps()
    }

    private fun scanApps() {
        apps.clear()
        selectedPackages.clear()

        val pm = packageManager
        val installed = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            summary.text = "อ่านรายการแอปไม่สำเร็จ: ${e.localizedMessage ?: "unknown error"}"
            return
        }

        installed.forEach { info ->
            val packageName = info.packageName ?: return@forEach
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val label = try {
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                packageName
            }
            val launchIntent = try {
                pm.getLaunchIntentForPackage(packageName)
            } catch (_: Exception) {
                null
            }

            val packageInfo = try { pm.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
            val apkBytes = try {
                val base = java.io.File(info.sourceDir).length()
                val splits = info.splitSourceDirs?.sumOf { java.io.File(it).length() } ?: 0L
                base + splits
            } catch (_: Exception) { 0L }
            apps.add(InstalledApp(label, packageName, isSystem, launchIntent != null,
                packageInfo?.firstInstallTime ?: 0L, packageInfo?.lastUpdateTime ?: 0L, apkBytes))
        }

        val snapshot = apps.toList()
        Thread {
            val cacheMap = loadCacheSizes(snapshot)
            runOnUiThread {
                apps.forEach { it.cacheBytes = cacheMap[it.packageName] }
                renderApps()
            }
        }.start()
        renderApps()
    }

    private fun visibleByFilter(app: InstalledApp): Boolean {
        if (!includeSystemCheck.isChecked && app.isSystem) return false
        val query = searchInput.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        return query.isEmpty() ||
            app.label.lowercase(Locale.getDefault()).contains(query) ||
            app.packageName.lowercase(Locale.getDefault()).contains(query)
    }

    private fun renderApps() {
        if (!::appList.isInitialized) return
        appList.removeAllViews()

        val visible = apps.filter(::visibleByFilter).sortedWith(when (sortMode) {
            1 -> compareByDescending<InstalledApp> { it.apkBytes }.thenBy { it.label.lowercase(Locale.getDefault()) }
            2 -> compareBy<InstalledApp> { it.apkBytes }.thenBy { it.label.lowercase(Locale.getDefault()) }
            3 -> compareByDescending<InstalledApp> { it.cacheBytes ?: -1L }.thenBy { it.label.lowercase(Locale.getDefault()) }
            4 -> compareBy<InstalledApp> { it.cacheBytes ?: Long.MAX_VALUE }.thenBy { it.label.lowercase(Locale.getDefault()) }
            5 -> compareByDescending<InstalledApp> { it.firstInstallTime }
            6 -> compareBy<InstalledApp> { it.firstInstallTime }
            7 -> compareByDescending<InstalledApp> { it.lastUpdateTime }
            8 -> compareBy<InstalledApp> { it.lastUpdateTime }
            else -> compareBy<InstalledApp> { it.label.lowercase(Locale.getDefault()) }.thenBy { it.packageName }
        })
        val launchableCount = visible.count { it.hasLaunchActivity }
        summary.text = "พบ ${apps.size} แอป • แสดง ${visible.size} • เปิดได้ ${launchableCount} • เลือก ${selectedPackages.size}"

        if (visible.isEmpty()) {
            appList.addView(TextView(this).apply {
                text = "ไม่พบแอปที่ตรงกับการค้นหา"
                setPadding(0, dp(16), 0, dp(16))
            })
            return
        }

        visible.forEach { app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val checkBox = CheckBox(this).apply {
                text = app.label
                textSize = 16f
                isChecked = selectedPackages.contains(app.packageName)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedPackages.add(app.packageName)
                    else selectedPackages.remove(app.packageName)
                    summary.text = "พบ ${apps.size} แอป • แสดง ${visible.size} • เปิดได้ ${launchableCount} • เลือก ${selectedPackages.size}"
                }
            }
            row.addView(checkBox)
            row.addView(TextView(this).apply {
                text = app.packageName
                textSize = 12f
            })
            row.addView(TextView(this).apply {
                text = if (app.isSystem) "ประเภท: แอประบบ" else "ประเภท: แอปผู้ใช้"
                textSize = 12f
            })
            row.addView(TextView(this).apply {
                text = "ขนาดไฟล์ APK: ${formatBytes(app.apkBytes)}"
                textSize = 12f
            })
            row.addView(TextView(this).apply {
                text = "ขนาดแคช: ${app.cacheBytes?.let { formatBytes(it) } ?: if (hasUsageAccess) "อ่านไม่ได้" else "ต้องอนุญาต Usage access"}"
                textSize = 12f
            })
            row.addView(TextView(this).apply {
                text = "วันที่ติดตั้ง: ${if (app.firstInstallTime > 0) DateFormat.getDateTimeInstance().format(Date(app.firstInstallTime)) else "ไม่ทราบ"}"
                textSize = 12f
            })
            row.addView(TextView(this).apply {
                text = "วันที่อัปเดต: ${if (app.lastUpdateTime > 0) DateFormat.getDateTimeInstance().format(Date(app.lastUpdateTime)) else "ไม่ทราบ"}"
                textSize = 12f
            })
            row.addView(TextView(this).apply {
                text = if (app.hasLaunchActivity) "สถานะ: พบหน้าสำหรับเปิดแอป" else "สถานะ: ไม่พบหน้าสำหรับเปิดแอป"
                textSize = 12f
            })
            appList.addView(row)
            appList.addView(View(this).apply {
                setBackgroundColor(0xFFE0E0E0.toInt())
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ))
        }
    }


    private fun loadCacheSizes(targetApps: List<InstalledApp>): Map<String, Long> {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        hasUsageAccess = try {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
        if (!hasUsageAccess) return emptyMap()
        val storageStats = getSystemService(STORAGE_STATS_SERVICE) as StorageStatsManager
        val result = mutableMapOf<String, Long>()
        targetApps.forEach { app ->
            try {
                result[app.packageName] = storageStats.queryStatsForPackage(
                    StorageManager.UUID_DEFAULT, app.packageName, Process.myUserHandle()
                ).cacheBytes
            } catch (_: Exception) { }
        }
        return result
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do { value /= 1024.0; unit++ } while (value >= 1024 && unit < units.lastIndex)
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
    }

    override fun onResume() {
        super.onResume()
        if (::appList.isInitialized && apps.isNotEmpty()) {
            Thread {
                val cacheMap = loadCacheSizes(apps.toList())
                runOnUiThread {
                    apps.forEach { it.cacheBytes = cacheMap[it.packageName] }
                    renderApps()
                }
            }.start()
        }
    }

    private fun beginBatchUninstall() {
        if (selectedPackages.isEmpty()) {
            Toast.makeText(this, "กรุณาเลือกแอปก่อน", Toast.LENGTH_SHORT).show()
            return
        }

        uninstallQueue.clear()
        uninstallQueue.addAll(selectedPackages)
        selectedPackages.clear()
        Toast.makeText(
            this,
            "เตรียมส่งคำขอถอนติดตั้ง ${uninstallQueue.size} แอป ระบบจะให้ยืนยันทีละแอป",
            Toast.LENGTH_LONG
        ).show()
        launchNextUninstall()
    }

    private fun beginRootBatchUninstall() {
        if (selectedPackages.isEmpty()) {
            Toast.makeText(this, "กรุณาเลือกแอปก่อน", Toast.LENGTH_SHORT).show()
            return
        }

        val selected = selectedPackages.toList()
        val userApps = selected.filter { pkg -> apps.any { it.packageName == pkg && !it.isSystem } }
        val skippedSystemCount = selected.size - userApps.size
        if (userApps.isEmpty()) {
            Toast.makeText(this, "ไม่มีแอปผู้ใช้ที่เลือกไว้ (โหมด Root ข้ามแอประบบ)", Toast.LENGTH_LONG).show()
            return
        }

        selectedPackages.clear()
        summary.text = "กำลังตรวจสอบ Root / KernelSU..."
        Toast.makeText(
            this,
            "จะถอนแอปผู้ใช้ ${userApps.size} รายการ และข้ามแอประบบ ${skippedSystemCount} รายการ",
            Toast.LENGTH_LONG
        ).show()

        Thread {
            val rootCheck = RootUninstaller.hasRootAccess()
            if (!rootCheck.success) {
                runOnUiThread {
                    summary.text = "ไม่พบสิทธิ์ Root หรือไม่ได้อนุญาตสิทธิ์ su"
                    Toast.makeText(this, "Root ไม่พร้อม: ${rootCheck.output}", Toast.LENGTH_LONG).show()
                    renderApps()
                }
                return@Thread
            }

            var successCount = 0
            val failures = mutableListOf<String>()
            userApps.forEach { packageName ->
                val result = RootUninstaller.uninstallForUser(packageName)
                if (result.success) successCount++ else failures.add("$packageName: ${result.output}")
            }

            runOnUiThread {
                scanApps()
                val report = "Root ถอนสำเร็จ $successCount/${userApps.size} รายการ" +
                    if (failures.isEmpty()) "" else "\nไม่สำเร็จ:\n" + failures.take(8).joinToString("\n")
                summary.text = report
                Toast.makeText(this, report, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun launchNextUninstall() {
        if (uninstallQueue.isEmpty()) {
            waitingForUninstallResult = false
            scanApps()
            Toast.makeText(this, "จบคิวถอนติดตั้งแล้ว", Toast.LENGTH_LONG).show()
            return
        }

        val packageName = uninstallQueue.removeFirst()
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
        try {
            waitingForUninstallResult = true
            startActivityForResult(intent, REQUEST_UNINSTALL)
        } catch (e: Exception) {
            waitingForUninstallResult = false
            Toast.makeText(this, "เปิดหน้าถอนติดตั้งไม่ได้: $packageName", Toast.LENGTH_LONG).show()
            launchNextUninstall()
        }
    }

    @Deprecated("Deprecated by Android; retained for broad platform compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_UNINSTALL && waitingForUninstallResult) {
            waitingForUninstallResult = false
            launchNextUninstall()
        }
    }

    private fun actionButton(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun fullWidthParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(4); bottomMargin = dp(4) }

    private fun weightedParams() = LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
    ).apply { marginEnd = dp(4) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_UNINSTALL = 4102
    }
}
