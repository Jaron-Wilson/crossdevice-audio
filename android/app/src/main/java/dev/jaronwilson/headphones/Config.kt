package dev.jaronwilson.headphones

import android.content.Context

/** What the user picked in the Headphones app. Nothing about any device is built into the APK. */
object Config {
    private fun p(c: Context) = c.getSharedPreferences("config", Context.MODE_PRIVATE)

    /** The laptop panel (optional): e.g. https://my-laptop.my-tailnet.ts.net:8970 */
    fun panelUrl(c: Context): String = (p(c).getString("panel_url", "") ?: "").trim().trimEnd('/')
    fun hasPanel(c: Context) = panelUrl(c).isNotEmpty()

    /** Bluetooth address of the AirPods, chosen from the phone's paired devices. */
    fun airpodsMac(c: Context): String = p(c).getString("airpods_mac", "") ?: ""
    fun hasAirPods(c: Context) = airpodsMac(c).isNotEmpty()

    fun save(c: Context, panelUrl: String, airpodsMac: String) =
        p(c).edit().putString("panel_url", panelUrl.trim()).putString("airpods_mac", airpodsMac).apply()
}
