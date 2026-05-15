package toys.compy.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.ComponentName
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.core.graphics.toColorInt

class KioskControlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Root container: Horizontal for two columns
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            weightSum = 2f
        }

        // LEFT COLUMN: Maintenance Controls
        val leftScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.8f)
            isVerticalScrollBarEnabled = false
        }
        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        leftScroll.addView(leftColumn)

        val titleView = TextView(this).apply {
            text = getString(R.string.maintenance_title)
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
            gravity = Gravity.CENTER
        }
        leftColumn.addView(titleView)

        val statusView = TextView(this).apply {
            setTextColor(Color.YELLOW)
            textSize = 14f
            setPadding(0, 0, 0, 24)
            gravity = Gravity.CENTER
        }
        updateStatus(statusView)
        leftColumn.addView(statusView)

        addButton(leftColumn, getString(R.string.btn_resume_kiosk)) {
            KioskState.disableMaintenance(this)
            launchApp(KioskConfig.TARGET_PACKAGE)
            finish()
        }

        addButton(leftColumn, getString(R.string.btn_open_target)) {
            launchApp(KioskConfig.TARGET_PACKAGE)
        }

        addButton(leftColumn, getString(R.string.btn_open_settings)) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        addButton(leftColumn, getString(R.string.btn_open_app_settings)) {
            startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
        }

        addButton(leftColumn, getString(R.string.btn_open_files)) {
            val intents = listOf(
                // 1. Try "DocumentsUI" Go Edition (common on low-end devices)
                Intent().setComponent(ComponentName("com.google.android.go.documentsui", "com.android.documentsui.files.FilesActivity")),
                // 2. Try standard AOSP DocumentsUI
                Intent().setComponent(ComponentName("com.android.documentsui", "com.android.documentsui.files.FilesActivity")),
                // 2b. Alternative AOSP DocumentsUI launcher
                Intent().setComponent(ComponentName("com.android.documentsui", "com.android.documentsui.LauncherActivity")),
                // 3. Try Downloads UI
                Intent().setComponent(ComponentName("com.android.providers.downloads.ui", "com.android.providers.downloads.ui.DownloadList")),
                // 4. Try standard category selector (Android 10+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES)
                } else null,
                // 5. Try "Files by Google"
                packageManager.getLaunchIntentForPackage("com.google.android.apps.nbu.files"),
                // 6. Try Manufacturer specific (Allwinner)
                packageManager.getLaunchIntentForPackage("com.softwinner.awmanager")
            )

            var launched = false
            for (intent in intents) {
                if (intent == null) continue
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(intent)
                    launched = true
                    break
                } catch (_: Exception) {
                    continue
                }
            }

            if (!launched) {
                // Last ditch effort: Open a picker-style view
                try {
                    val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(fallback)
                } catch (_: Exception) {
                    Toast.makeText(this, "No File Manager found", Toast.LENGTH_SHORT).show()
                }
            }
        }

        root.addView(leftScroll)

        // RIGHT COLUMN: App List
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 48, 48)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f)
            setBackgroundColor("#1A1A1A".toColorInt())
        }

        val appListHeader = TextView(this).apply {
            text = getString(R.string.app_list_header)
            textSize = 20f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 24)
        }
        rightColumn.addView(appListHeader)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isVerticalScrollBarEnabled = true
        }
        val appListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(appListContainer)
        rightColumn.addView(scrollView)

        populateAppList(appListContainer)

        root.addView(rightColumn)

        setContentView(root)
    }

    private fun updateStatus(view: TextView) {
        val until = KioskState.getMaintenanceUntil(this)
        if (System.currentTimeMillis() < until) {
            val date = Date(until)
            val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            view.text = getString(R.string.maintenance_active_until, format.format(date))
        } else {
            view.text = getString(R.string.maintenance_expired)
        }
    }

    private fun addButton(parent: ViewGroup, label: String, onClick: () -> Unit) {
        val button = Button(this).apply {
            text = label
            setPadding(32, 16, 32, 16)
            setOnClickListener { onClick() }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 12)
            layoutParams = params
        }
        parent.addView(button)
    }

    private fun populateAppList(container: LinearLayout) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(intent, 0)

        apps.asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .forEach { info ->
                val btn = Button(this).apply {
                    text = info.loadLabel(pm)
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(32, 16, 32, 16)
                    val params = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    params.setMargins(0, 0, 0, 8)
                    layoutParams = params
                    setOnClickListener {
                        KioskState.enableMaintenance(this@KioskControlActivity, KioskConfig.MAINTENANCE_DURATION_MS)
                        launchApp(info.packageName)
                    }
                }
                container.addView(btn)
            }
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
        } else {
            Toast.makeText(this, "Could not launch $packageName", Toast.LENGTH_SHORT).show()
        }
    }
}
