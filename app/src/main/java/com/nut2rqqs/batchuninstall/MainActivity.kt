package com.nut2rqqs.batchuninstall

import android.app.Activity
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

private data class InstalledApp(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val hasLaunchActivity: Boolean
)

class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var summary: TextView
    private lateinit var searchInput: EditText
    private lateinit var includeSystemCheck: CheckBox
    private lateinit var appList: LinearLayout

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

            apps.add(InstalledApp(label, packageName, isSystem, launchIntent != null))
        }

        apps.sortWith(compareBy<InstalledApp> { it.label.lowercase(Locale.getDefault()) }
            .thenBy { it.packageName })
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

        val visible = apps.filter(::visibleByFilter)
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
