package app.gamanga.desktop.ui.recommend

import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64
import java.util.prefs.Preferences

/** In-process Windows DPAPI CurrentUser; compatible with the previous encrypted snapshots. */
internal object BangumiCredential {
    fun save(token: String, prefs: Preferences) {
        check(System.getProperty("os.name").startsWith("Windows"))
        val plain = token.toByteArray(Charsets.UTF_8)
        try {
            val encrypted = Crypt32Util.cryptProtectData(plain)
            prefs.put("credential.dpapi", Base64.getEncoder().encodeToString(encrypted))
            prefs.flush()
        } finally { plain.fill(0) }
    }
    fun read(prefs: Preferences): String? {
        val encrypted = prefs.get("credential.dpapi", "").ifBlank { return null }
        check(System.getProperty("os.name").startsWith("Windows"))
        val plain = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(encrypted))
        return try { String(plain, Charsets.UTF_8) } finally { plain.fill(0) }
    }
    fun clear(prefs: Preferences) { prefs.remove("credential.dpapi"); prefs.flush() }
}
