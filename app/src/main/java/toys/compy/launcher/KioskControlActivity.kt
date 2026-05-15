package toys.compy.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KioskControlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.DKGRAY)
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.maintenance_title)
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        root.addView(titleView)

        val statusView = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 32)
        }
        updateStatus(statusView)
        root.addView(statusView)

        addButton(root, getString(R.string.btn_resume_kiosk)) {
            KioskState.disableMaintenance(this)
            launchApp(KioskConfig.TARGET_PACKAGE)
            finish()
        }

        addButton(root, getString(R.string.btn_open_target)) {
            launchApp(KioskConfig.TARGET_PACKAGE)
        }

        addButton(root, getString(R.string.btn_open_settings)) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        addButton(root, getString(R.string.btn_open_app_settings)) {
            startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
        }

        val appListHeader = TextView(this).apply {
            text = getString(R.string.app_list_header)
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 32, 0, 16)
        }
        root.addView(appListHeader)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val appListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(appListContainer)
        root.addView(scrollView)

        populateAppList(appListContainer)

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
            setOnClickListener { onClick() }
        }
        parent.addView(button)
    }

    private fun populateAppList(container: LinearLayout) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(intent, 0)

        apps.map { it.activityInfo }
            .filter { it.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .forEach { info ->
                val btn = Button(this).apply {
                    text = info.loadLabel(pm)
                    isAllCaps = false
                    setOnClickListener {
                        // Keep maintenance active or extend it
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
