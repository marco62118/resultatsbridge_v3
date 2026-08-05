package app.resultatsbridge.organisateur.bws

import android.content.ContentValues
import android.content.Context
import android.util.Log
import app.resultatsbridge.organisateur.data.DatabaseHelper
import com.healthmarketscience.jackcess.DatabaseBuilder
import java.io.File

/**
 * Importe les mains (HandRecord) d'un vrai fichier .bws FFBClubNet dans les
 * tables locales "mains"/"donnes" de V3, pour que l'écran de saisie résultat
 * les reconnaisse comme "déjà saisies" (donneEnCours.mains non nul, voir
 * MouvementScreen.kt) et affiche directement "🔍 Vérification des mains" au
 * lieu de proposer une saisie manuelle ou photo — les mains viennent alors
 * du fichier officiel, pas d'une ressaisie par les joueurs.
 */
object BwsHandImporter {

    // Rang Bridgemate (A,K,Q,J,T,9..2) -> rang V3 (A,R,D,V,10,9..2), voir Carte.kt.
    private fun rangDepuisBridgemate(r: Char): String = when (r) {
        'A' -> "A"
        'K' -> "R"
        'Q' -> "D"
        'J' -> "V"
        'T' -> "10"
        else -> r.toString()
    }

    // Une colonne HandRecord ("54", "J9742"...) -> liste de codes carte V3 ("5P","4P"...).
    private fun suiteVersCartes(suite: String, couleurV3: String): List<String> =
        suite.map { rangDepuisBridgemate(it) + couleurV3 }

    fun importerMainsDepuisBws(context: Context, idTournoi: Int, fichierBws: File): Int {
        if (!fichierBws.exists()) return 0
        var nbImportees = 0
        val db = DatabaseHelper.getDatabase(context)
        try {
            val accessDb = DatabaseBuilder.open(fichierBws)
            try {
                accessDb.setEvaluateExpressions(false)
                val table = accessDb.getTable("HandRecord")
                for (row in table) {
                    val numeroDonne = (row["Board"] as? Number)?.toInt() ?: continue

                    val dejaImportee = db.rawQuery(
                        "SELECT ID FROM donnes WHERE id_tournoi=? AND numero_donne=? LIMIT 1",
                        arrayOf(idTournoi.toString(), numeroDonne.toString())
                    ).use { c -> c.moveToFirst() }
                    if (dejaImportee) continue

                    fun cartesSiege(prefixe: String): List<String> {
                        val pique  = suiteVersCartes((row["${prefixe}Spades"]   as? String).orEmpty(), "P")
                        val coeur  = suiteVersCartes((row["${prefixe}Hearts"]   as? String).orEmpty(), "C")
                        val carreau = suiteVersCartes((row["${prefixe}Diamonds"] as? String).orEmpty(), "K")
                        val trefle = suiteVersCartes((row["${prefixe}Clubs"]    as? String).orEmpty(), "T")
                        return pique + coeur + carreau + trefle
                    }

                    val mainsQuatreSieges = listOf(
                        cartesSiege("North"), cartesSiege("East"), cartesSiege("South"), cartesSiege("West")
                    )
                    if (mainsQuatreSieges.any { it.size != 13 }) {
                        Log.e("BwsHandImporter", "❌ Donne $numeroDonne : mains incomplètes dans le .bws, ignorée")
                        continue
                    }

                    fun insertMain(cartes: List<String>): Long {
                        val v = ContentValues()
                        cartes.forEachIndexed { idx, carte -> v.put("carte${idx + 1}", carte) }
                        return db.insert("mains", null, v)
                    }
                    val idsMains = mainsQuatreSieges.map { insertMain(it) }

                    var donneur = ""; var vulnerable = ""
                    db.rawQuery(
                        "SELECT donneur, vulnerable FROM donnes_d_v WHERE ID_donnes_d_v=? LIMIT 1",
                        arrayOf(numeroDonne.toString())
                    ).use { c -> if (c.moveToFirst()) { donneur = c.getString(0); vulnerable = c.getString(1) } }

                    db.insert("donnes", null, ContentValues().apply {
                        put("id_tournoi", idTournoi); put("numero_donne", numeroDonne)
                        put("donneur", donneur); put("vulnerable", vulnerable)
                        put("main_N", idsMains[0]); put("main_E", idsMains[1])
                        put("main_S", idsMains[2]); put("main_O", idsMains[3])
                    })
                    nbImportees++
                }
                Log.i("BwsHandImporter", "✅ $nbImportees donne(s) importée(s) depuis le .bws")
            } finally {
                accessDb.close()
            }
        } catch (e: Exception) {
            Log.e("BwsHandImporter", "❌ Erreur import mains .bws (non bloquant)", e)
        } finally {
            DatabaseHelper.closeDatabaseAndWait()
        }
        return nbImportees
    }
}
