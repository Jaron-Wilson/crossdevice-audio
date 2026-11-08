package dev.jaronwilson.headphones

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * The app screen: pick your AirPods from the phone's paired devices, optionally point it at a laptop
 * panel, and check whether this phone can control the AirPods directly. Nothing device-specific is
 * built into the APK; it all comes from here.
 */
class SetupActivity : Activity() {
    private lateinit var spinner: Spinner
    private lateinit var url: EditText
    private lateinit var status: TextView
    private var devices: List<BluetoothDevice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneDirect.resetStatus(this)   // opening the app retries direct control from the phone
        buildUi()
        val need = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else loadDevices()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) =
        loadDevices()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(22))
            setBackgroundColor(Color.parseColor("#0F1419"))
        }
        fun label(t: String, size: Float = 13f, color: String = "#8AA6B4") = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.parseColor(color)); setPadding(0, dp(14), 0, dp(6))
        }
        col.addView(label("Headphones", 24f, "#E8EDF2"))
        col.addView(label("Control your AirPods noise modes and see their battery from a home-screen widget. " +
            "The laptop panel is optional: it adds control while your AirPods are on a computer."))
        col.addView(label("Your AirPods (from paired devices)"))
        spinner = Spinner(this)
        col.addView(spinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(label("Laptop panel URL (optional)"))
        url = EditText(this).apply {
            hint = "https://my-laptop.my-tailnet.ts.net:8970"
            setText(Config.panelUrl(this@SetupActivity))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#55708A"))
        }
        col.addView(url, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val save = Button(this).apply { text = "Save"; setOnClickListener { save() } }
        col.addView(save, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })
        val open = Button(this).apply {
            text = "Open laptop panel"
            setOnClickListener {
                if (Config.hasPanel(this@SetupActivity))
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.panelUrl(this@SetupActivity))))
                else Toast.makeText(this@SetupActivity, "No laptop panel set", Toast.LENGTH_SHORT).show()
            }
        }
        col.addView(open, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        status = label("", 13f, "#99FFBB")
        col.addView(status)
        col.addView(label("Then long-press your home screen, open Widgets and add Headphones.", 12f))
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Color.parseColor("#0F1419")) })
    }

    @Suppress("MissingPermission")
    private fun loadDevices() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Allow \"Nearby devices\" so the app can find your AirPods."
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        devices = adapter?.bondedDevices?.sortedBy { if ((it.name ?: "").contains("AirPods", true)) 0 else 1 } ?: emptyList()
        val names = devices.map { "${it.name ?: "Unknown"}  (${it.address})" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val saved = devices.indexOfFirst { it.address.equals(Config.airpodsMac(this), true) }
        if (saved >= 0) spinner.setSelection(saved)
        status.text = if (devices.isEmpty()) "Pair your AirPods in Bluetooth settings first." else ""
        if (Config.hasAirPods(this)) syncHandoff()
    }

    private fun save() {
        val dev = devices.getOrNull(spinner.selectedItemPosition)
        if (dev == null) { status.text = "Pick your AirPods first."; return }
        Config.save(this, url.text.toString(), dev.address)
        status.text = "Saved."
        syncHandoff()
    }

    /** Who has the AirPods right now: tell the laptop (if any) and redraw the widget. */
    @Suppress("MissingPermission")
    private fun syncHandoff() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val onPhone = runCatching {
                    (proxy as BluetoothA2dp).connectedDevices.any { it.address.equals(Config.airpodsMac(this@SetupActivity), true) }
                }.getOrDefault(false)
                adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                Handoff.setLocal(this@SetupActivity, onPhone)
                val app = applicationContext
                thread { Handoff.tell(app, onPhone); HeadphonesWidget.refresh(app) }
                runOnUiThread {
                    status.text = (status.text.toString() + "\n" +
                        (if (onPhone) "Your AirPods are on this phone." else "Your AirPods aren't on this phone right now.")).trim()
                }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }
}
