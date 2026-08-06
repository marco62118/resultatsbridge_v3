package app.resultatsbridge.organisateur.server

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.collections.iterator

object NetworkUtils {
    fun getLocalIpAddress(): String {
        try {
            var repli: String? = null
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addresses = intf.inetAddresses
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        // Les interfaces de données mobiles sont toujours nommées "rmnet..."
                        // sur Android — on les écarte en priorité pour ne jamais afficher l'IP
                        // 4G à la place de l'IP Wi-Fi/point d'accès quand les deux sont actifs
                        // en même temps (ex. données mobiles laissées allumées par erreur).
                        // Porté depuis resultatsbridge_v2 le 05/08/2026 (corrigé sur V2 le
                        // 15/07/2026, jamais porté sur V3 jusqu'ici).
                        if (!intf.name.startsWith("rmnet")) {
                            return ip
                        }
                        if (repli == null) repli = ip
                    }
                }
            }
            if (repli != null) return repli
        } catch (e: Exception) {
            Log.e("NetworkUtils", "Erreur IP : ${e.message}")
        }
        return "IP non détectée"
    }
}