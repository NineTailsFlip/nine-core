package io.github.ninetailsflip.update

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** One APK of a release: for one ABI ("arm64-v8a", "armeabi-v7a"), or "universal". */
data class UpdateApk(val abi: String, val url: String, val size: Long, val sha256: String)

/** What a release's update.json says, as the release workflow writes it next to the APKs:
 *
 *  ```json
 *  {"app": "g9", "versionCode": 2, "versionName": "0.2.0", "notes": "…",
 *   "apks": [{"abi": "universal", "url": "https://github.com/…/g9-0.2.0.apk",
 *             "size": 2654000, "sha256": "…"}]}
 *  ```
 *
 *  versionCode decides what's newer, as it does for Android itself. */
data class UpdateInfo(
    val app: String,
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val apks: List<UpdateApk>,
) {
    fun isNewerThan(installedVersionCode: Long): Boolean = versionCode > installedVersionCode

    /** The APK built for the first ABI the phone supports (Build.SUPPORTED_ABIS, best first),
     *  else the universal one, else whatever there is. */
    fun apkFor(supportedAbis: List<String>): UpdateApk? =
        supportedAbis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.abi == abi } }
            ?: apks.firstOrNull { it.abi == UNIVERSAL }
            ?: apks.firstOrNull()

    fun toJson(): String = JSONObject()
        .put("app", app)
        .put("versionCode", versionCode)
        .put("versionName", versionName)
        .put("notes", notes)
        .put(
            "apks",
            JSONArray().apply {
                apks.forEach { put(JSONObject().put("abi", it.abi).put("url", it.url).put("size", it.size).put("sha256", it.sha256)) }
            },
        )
        .toString()

    companion object {
        const val UNIVERSAL = "universal"

        /** @throws org.json.JSONException when it isn't an update.json. */
        fun parse(json: String): UpdateInfo {
            val o = JSONObject(json)
            val apks = o.getJSONArray("apks")
            return UpdateInfo(
                app = o.optString("app"),
                versionCode = o.getLong("versionCode"),
                versionName = o.getString("versionName"),
                notes = o.optString("notes"),
                apks = (0 until apks.length()).map { i ->
                    val a = apks.getJSONObject(i)
                    UpdateApk(
                        abi = a.optString("abi", UNIVERSAL),
                        url = a.getString("url"),
                        size = a.optLong("size"),
                        sha256 = a.getString("sha256").lowercase(),
                    )
                },
            )
        }
    }
}

/** Whether the daily check is due: a day since the last one, or the clock went backwards. */
fun isCheckDue(lastCheckMs: Long, nowMs: Long, intervalMs: Long = Updates.CHECK_INTERVAL_MS): Boolean =
    nowMs - lastCheckMs >= intervalMs || nowMs < lastCheckMs

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Release notes as GitHub generates them are Markdown; on a 2.4″ screen plain lines read
 *  better. Drops headings' hashes, list stars, links' URLs and the "Full Changelog" footer. */
fun plainNotes(markdown: String): String = markdown
    .lines()
    .filterNot { it.startsWith("**Full Changelog**") }
    .joinToString("\n") { line ->
        line.trimEnd()
            .replace(Regex("^#+\\s*"), "")
            .replace(Regex("^[*-]\\s+"), "• ")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex(" by @\\S+ in https?://\\S+"), "")
            .replace("**", "")
    }
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
