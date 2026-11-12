package dev.jaronwilson.headphones

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Last seen, all on this phone: no Apple or Google finding network, no upload, no account.
 *  - When the AirPods or the Crusher disconnect from this phone, save the time, the phone's location
 *    (if you allowed it) and the last battery levels.
 *  - When the app's BLE scans hear your AirPods (or the Crusher), save "seen nearby" at most every 5 minutes.
 *  - When a scan stops hearing them, save "stopped hearing them".
 * At most 50 events per device are kept, in the app's private storage. Clear history deletes them.
 */
object Tracker {
    const val AIRPODS = "airpods"
    const val CRUSHER = "crusher"
    val DEVICES = listOf(AIRPODS, CRUSHER)
    private const val PREFS = "track"
    private val lock = Any()

    fun label(device: String) = if (device == CRUSHER) "Crusher" else "AirPods"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun file(c: Context) = File(c.filesDir, "seen-events.tsv")

    fun enabled(c: Context) = p(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) = p(c).edit().putBoolean("on", on).apply()

    // ---- the event log ----
    fun events(c: Context): List<SeenEvent> = synchronized(lock) {
        runCatching { EventLog.decodeAll(file(c).readText()) }.getOrDefault(emptyList())
    }

    fun events(c: Context, device: String) = EventLog.forDevice(events(c), device)

    private fun add(c: Context, e: SeenEvent) = synchronized(lock) {
        val next = EventLog.append(events(c), e)
        val f = file(c)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(EventLog.encodeAll(next))
        tmp.renameTo(f)
    }

    private fun replace(c: Context, old: SeenEvent, new: SeenEvent) = synchronized(lock) {
        val list = events(c).map { if (it == old) new else it }
        file(c).writeText(EventLog.encodeAll(list))
    }

    /** Clear history: the events, the remembered battery and nearby state. The AirPods' key stays (it
     *  is what lets the finder recognise them; "Forget key" removes it). */
    fun clear(c: Context) = synchronized(lock) {
        file(c).delete()
        val keep = p(c).all.filterKeys { it == "on" || it.startsWith("id_") }
        p(c).edit().clear().apply {
            for ((k, v) in keep) when (v) {
                is Boolean -> putBoolean(k, v); is Int -> putInt(k, v); is String -> putString(k, v)
            }
        }.apply()
    }

    /** The newest event of any device (for the widget line). */
    fun latest(c: Context): SeenEvent? = events(c).maxByOrNull { it.t }

    // ---- what we know right now ----
    fun connected(c: Context, device: String) = p(c).getBoolean("conn_$device", false)

    fun rememberBattery(c: Context, device: String, battery: String, buds: String = "") {
        if (battery.isEmpty() && buds.isEmpty()) return
        p(c).edit().apply {
            if (battery.isNotEmpty()) putString("bat_$device", battery)
            if (buds.isNotEmpty()) putString("buds_$device", buds)
        }.putLong("bat_t_$device", System.currentTimeMillis()).apply()
    }

    private fun battery(c: Context, device: String) = p(c).getString("bat_$device", "") ?: ""
    private fun buds(c: Context, device: String) = p(c).getString("buds_$device", "") ?: ""

    // ---- identity of your AirPods (to recognise their rotating BLE address) ----
    fun identity(c: Context): ApIdentity {
        val q = p(c)
        val fresh = System.currentTimeMillis() - q.getLong("id_bat_t", 0) < 30 * 60_000
        return ApIdentity(
            model = q.getInt("id_model", -1).takeIf { it >= 0 },
            color = q.getInt("id_color", -1).takeIf { it >= 0 },
            irk = q.getString("id_irk", null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() },
            left = if (fresh) q.getInt("id_l", -1).takeIf { it >= 0 } else null,
            right = if (fresh) q.getInt("id_r", -1).takeIf { it >= 0 } else null,
            case = if (fresh) q.getInt("id_c", -1).takeIf { it >= 0 } else null)
    }

    fun hasKey(c: Context) = p(c).contains("id_irk")
    fun forgetKey(c: Context) = p(c).edit().remove("id_irk").remove("id_irk_try").apply()

    /** The AirPods' IRK, sent by them on this phone's own AAP link (PhoneDirect). */
    fun rememberIrk(c: Context, irk: ByteArray) =
        p(c).edit().putString("id_irk", Base64.encodeToString(irk, Base64.NO_WRAP)).apply()

    /** Ask for the key at most once an hour, and only until we have it. */
    fun wantIrk(c: Context): Boolean {
        if (hasKey(c)) return false
        val last = p(c).getLong("id_irk_try", 0)
        if (System.currentTimeMillis() - last < 3_600_000) return false
        p(c).edit().putLong("id_irk_try", System.currentTimeMillis()).apply()
        return true
    }

    /** While the AirPods are connected to this phone, a very strong proximity advert (the strongest one, right
     *  next to the phone) is almost certainly theirs: learn their model and colour from it. */
    private fun learn(c: Context, ad: ProxAdvert) {
        p(c).edit().putInt("id_model", ad.model).putInt("id_color", ad.color)
            .putInt("id_l", ad.left ?: -1).putInt("id_r", ad.right ?: -1).putInt("id_c", ad.case ?: -1)
            .putLong("id_bat_t", System.currentTimeMillis()).apply()
    }

    private fun refreshPattern(c: Context, ad: ProxAdvert) {
        p(c).edit().putInt("id_l", ad.left ?: -1).putInt("id_r", ad.right ?: -1).putInt("id_c", ad.case ?: -1)
            .putLong("id_bat_t", System.currentTimeMillis()).apply()
    }

    // ---- connect / disconnect (from BtReceiver) ----
    fun deviceOf(c: Context, dev: BluetoothDevice): String? {
        if (Config.hasAirPods(c) && dev.address.equals(Config.airpodsMac(c), true)) return AIRPODS
        val name = runCatching { dev.name }.getOrNull() ?: ""
        if (name.contains("crusher", true)) return CRUSHER
        return null
    }

    fun onConnected(c: Context, device: String) {
        p(c).edit().putBoolean("conn_$device", true).putLong("conn_t_$device", System.currentTimeMillis()).apply()
    }

    /**
     * The device just left this phone: save the event at once (time + battery + buds), then try to add
     * the phone's location. Called on a background thread with a few seconds to spare.
     */
    fun onDisconnected(c: Context, device: String) {
        p(c).edit().putBoolean("conn_$device", false).apply()
        if (!enabled(c)) return
        if (device == CRUSHER) crusherBatteryNow(c)
        val e = SeenEvent(device, "disconnected", System.currentTimeMillis(),
            battery = battery(c, device), buds = buds(c, device),
            note = "disconnected from this phone")
        add(c, e)
        if (!hasLocationPermission(c)) return
        // With "all the time" access, just ask (no notification). Otherwise a short foreground location
        // request, which Android allows with "while using the app" only when it lets the app start one now.
        if (hasBackgroundLocation(c)) { fillLocation(c, e, 8000); return }
        val viaService = runCatching {
            c.startForegroundService(Intent(c, LocateService::class.java).putExtra("t", e.t).putExtra("device", device))
        }.isSuccess
        if (!viaService) fillLocation(c, e, 8000)
    }

    /** Ask for the phone's location and put it on [e] (if Android lets us right now). */
    fun fillLocation(c: Context, e: SeenEvent, timeoutMs: Long) {
        val loc = locate(c, timeoutMs) ?: run {
            replace(c, e, e.copy(note = e.note + " · location unavailable"))
            HeadphonesWidget.redraw(c)
            return
        }
        replace(c, e, e.copy(lat = loc.latitude, lon = loc.longitude, acc = if (loc.hasAccuracy()) loc.accuracy else null))
        HeadphonesWidget.redraw(c)
    }

    // ---- location (only with your permission) ----
    fun hasLocationPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun playServices(c: Context) = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    /** One current fix (fused if Play services is there, else the platform), or a recent last-known one. */
    @Suppress("MissingPermission")
    fun locate(c: Context, timeoutMs: Long): Location? {
        if (!hasLocationPermission(c)) return null
        if (playServices(c)) runCatching {
            val client = LocationServices.getFusedLocationProviderClient(c)
            val cts = CancellationTokenSource()
            val fix = runCatching {
                Tasks.await(client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token), timeoutMs, TimeUnit.MILLISECONDS)
            }.getOrNull()
            cts.cancel()
            if (fix != null) return fix
            Tasks.await(client.lastLocation, 2, TimeUnit.SECONDS)?.let { if (fresh(it)) return it }
        }
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        for (prov in providers) {
            val latch = CountDownLatch(1)
            var got: Location? = null
            runCatching {
                lm.getCurrentLocation(prov, null, c.mainExecutor) { got = it; latch.countDown() }
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            }
            if (got != null) return got
        }
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { fresh(it) }.maxByOrNull { it.time }
    }

    private fun fresh(l: Location) = System.currentTimeMillis() - l.time < 10 * 60_000

    /** Cheap: a recent last-known fix only, no new request (used for "seen nearby"). */
    @Suppress("MissingPermission")
    private fun lastKnown(c: Context): Location? {
        if (!hasLocationPermission(c)) return null
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { fresh(it) }.maxByOrNull { it.time }
    }

    // ---- BLE adverts ----
    /**
     * An Apple proximity advert from any scan in the app. Returns how sure we are it is yours, and records
     * a "nearby" sighting (throttled) when it is.
     */
    fun onAdvert(c: Context, address: String, rssi: Int, ad: ProxAdvert, learnIt: Boolean = false): MatchLevel {
        if (learnIt) learn(c, ad)
        val m = AdvertMatch.match(address, ad, identity(c))
        if (m == MatchLevel.EXACT || m == MatchLevel.LIKELY) {
            refreshPattern(c, ad)
            rememberBattery(c, AIRPODS, ad.batteryText(), ad.budsText())
            sawNearby(c, AIRPODS, rssi, ad.batteryText(), ad.budsText(), if (m == MatchLevel.EXACT) "matched by key" else "likely yours (model + battery)")
        }
        return m
    }

    fun sawNearby(c: Context, device: String, rssi: Int, battery: String, buds: String, how: String) {
        val now = System.currentTimeMillis()
        p(c).edit().putLong("near_t_$device", now).putInt("near_rssi_$device", rssi).apply()
        if (!enabled(c) || !EventLog.nearbyDue(p(c).getLong("near_w_$device", 0), now)) return
        p(c).edit().putLong("near_w_$device", now).apply()
        val loc = lastKnown(c)
        add(c, SeenEvent(device, "nearby", now, loc?.latitude, loc?.longitude, loc?.accuracy,
            battery = battery.ifEmpty { battery(c, device) }, buds = buds, rssi = rssi, note = "heard nearby, $how"))
    }

    /**
     * A short, low-power scan from the widget's 5-minute refresh (only while the phone is awake). Records
     * sightings, and "stopped hearing them" once a device that was heard goes quiet.
     */
    @Suppress("MissingPermission")
    fun periodicScan(c: Context) {
        if (!enabled(c)) return
        if (c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        val now = System.currentTimeMillis()
        if (now - p(c).getLong("scan_t", 0) < 4 * 60_000) return
        p(c).edit().putLong("scan_t", now).apply()
        val scanner = c.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        val crusher = CrusherDirect.device(c)
        val heard = mutableSetOf<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val ad = ProxAdvert.parse(r.scanRecord?.getManufacturerSpecificData(0x004C))
                if (ad != null) {
                    val m = onAdvert(c, r.device.address, r.rssi, ad)
                    if (m == MatchLevel.EXACT || m == MatchLevel.LIKELY) heard += AIRPODS
                } else if (isCrusher(r, crusher)) {
                    heard += CRUSHER
                    sawNearby(c, CRUSHER, r.rssi, "", "", "its own Bluetooth LE broadcast")
                }
            }
        }
        val filters = mutableListOf(ScanFilter.Builder().setManufacturerData(0x004C, byteArrayOf(0x07), byteArrayOf(0xFF.toByte())).build())
        crusher?.let { filters += ScanFilter.Builder().setDeviceAddress(it.address).build() }
        filters += ScanFilter.Builder().setDeviceName(crusher?.name ?: "Crusher ANC 2").build()
        try {
            scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), cb)
            Thread.sleep(5000)
        } catch (_: Throwable) {
            return
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
        for (d in DEVICES) {
            val was = p(c).getBoolean("heard_$d", false)
            val nowHeard = d in heard || connected(c, d)
            if (was && !nowHeard) {
                val lastT = p(c).getLong("near_t_$d", now)
                // where the phone was when they were last heard (the latest "nearby" entry), else now
                val near = events(c, d).firstOrNull { it.kind == "nearby" && it.hasLocation }
                val loc = if (near == null) lastKnown(c) else null
                add(c, SeenEvent(d, "lost", lastT, near?.lat ?: loc?.latitude, near?.lon ?: loc?.longitude, near?.acc ?: loc?.accuracy,
                    battery = battery(c, d), buds = buds(c, d), rssi = p(c).getInt("near_rssi_$d", 0).takeIf { it != 0 },
                    note = "stopped hearing them (out of range, or in a closed case)"))
            }
            p(c).edit().putBoolean("heard_$d", nowHeard).apply()
        }
    }

    fun isCrusher(r: ScanResult, bonded: BluetoothDevice?): Boolean {
        if (bonded != null && r.device.address.equals(bonded.address, true)) return true
        val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: return false
        return name.contains("crusher", true)
    }

    /** The Crusher's battery as Android's Bluetooth stack knows it (hidden BluetoothDevice.getBatteryLevel). */
    fun crusherBatteryNow(c: Context) {
        val dev = CrusherDirect.device(c) ?: return
        val lvl = runCatching {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/")
            HiddenApiBypass.invoke(BluetoothDevice::class.java, dev, "getBatteryLevel") as Int
        }.getOrDefault(-1)
        if (lvl in 0..100) rememberBattery(c, CRUSHER, "$lvl%")
    }

    // ---- text ----
    fun timeText(t: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = t }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val clock = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))
        return when {
            sameDay -> clock
            now.timeInMillis - t < 6 * 86_400_000L -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t)) + " " + clock
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(t)) + " " + clock
        }
    }

    fun kindText(e: SeenEvent) = when (e.kind) {
        "disconnected" -> "Disconnected here"
        "nearby" -> "Seen nearby"
        "lost" -> "Last heard here"
        else -> e.kind
    }
}

/**
 * A few seconds of foreground location right after a disconnect: Android only gives a location to an
 * app in the background if it holds "all the time" access, but a short location foreground service
 * works with "while using the app". It stops itself as soon as it has a fix (or after ~10 s).
 */
class LocateService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val t = intent?.getLongExtra("t", 0) ?: 0
        val device = intent?.getStringExtra("device") ?: Tracker.AIRPODS
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Last seen", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, TrackActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("Saving where your ${Tracker.label(device)} disconnected")
            .setContentText("Stored on this phone only")
            .setContentIntent(open)
            .build()
        // Location type needs "while using the app" access that Android may withhold from a service started
        // in the background; then fall back to a plain short service (it still has to call startForeground).
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(2, n)
        }.isSuccess
        if (!started && Build.VERSION.SDK_INT >= 34)
            runCatching { startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
        val app = applicationContext
        thread {
            try {
                val e = Tracker.events(app).firstOrNull { it.t == t && it.device == device }
                if (e != null) Tracker.fillLocation(app, e, if (started) 10_000 else 6000)
            } finally {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private companion object { const val CH = "lastseen" }
}
