package app.resultatsbridge.organisateur.bws

import android.util.Log
import com.healthmarketscience.jackcess.DatabaseBuilder
import com.healthmarketscience.jackcess.Row
import java.io.File
import java.util.Date

/**
 * Écrit un score dans la table ReceivedData d'un fichier .bws, en localisant
 * la ligne déjà identifiée par Section/Table/Round/Board/PairNS/PairEW (déjà
 * créée par FFBClubNet en production — ou par BwsTestGenerator pour les
 * tests, voir FFB_Homologation_Tournoi.md). Port Kotlin du prototype
 * resultatsbridge_relais_ffb/src/WriteScore.java.
 */
object BwsWriter {

    fun ecrireScore(
        fichier: File,
        section: Int, table: Int, round: Int, board: Int, pairNS: Int, pairEW: Int,
        resultat: BridgemateConverter.Resultat
    ): Boolean {
        if (!fichier.exists()) {
            Log.e("BwsWriter", "❌ Fichier .bws introuvable : ${fichier.absolutePath}")
            return false
        }
        return try {
            val db = DatabaseBuilder.open(fichier)
            try {
                // javax.script n'existe pas sur Android, voir BwsTestGenerator.
                db.setEvaluateExpressions(false)
                val t = db.getTable("ReceivedData")

                var existante: Row? = null
                for (row in t) {
                    if (eq(row, "Section", section) && eq(row, "Table", table) &&
                        eq(row, "Round", round) && eq(row, "Board", board) &&
                        eq(row, "PairNS", pairNS) && eq(row, "PairEW", pairEW)
                    ) {
                        existante = row
                        break
                    }
                }

                val maintenant = Date()
                if (existante != null) {
                    existante["Declarer"] = resultat.declarer
                    existante["NS/EW"] = resultat.nsEw
                    existante["Contract"] = resultat.contract
                    existante["Result"] = resultat.result
                    existante["LeadCard"] = resultat.leadCard
                    existante["Remarks"] = resultat.remarks
                    existante["DateLog"] = maintenant
                    existante["TimeLog"] = maintenant
                    existante["Processed"] = false
                    existante["ExternalUpdate"] = true
                    t.updateRow(existante)
                    Log.i("BwsWriter", "✅ Score écrit (Section=$section Table=$table Round=$round Board=$board)")
                } else {
                    // Cas de secours, non documenté par Bridgemate : ne devrait
                    // pas arriver si la ligne a bien été pré-créée (Étape A).
                    Log.e("BwsWriter", "⚠️ Aucune ligne ReceivedData trouvée pour " +
                            "Section=$section Table=$table Round=$round Board=$board — insertion de secours")
                    t.addRowFromMap(
                        linkedMapOf(
                            "Section" to section, "Table" to table, "Round" to round, "Board" to board,
                            "PairNS" to pairNS, "PairEW" to pairEW,
                            "Declarer" to resultat.declarer, "NS/EW" to resultat.nsEw,
                            "Contract" to resultat.contract, "Result" to resultat.result,
                            "LeadCard" to resultat.leadCard, "Remarks" to resultat.remarks,
                            "DateLog" to maintenant, "TimeLog" to maintenant,
                            "Processed" to false, "ExternalUpdate" to true
                        )
                    )
                }
                true
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.e("BwsWriter", "❌ Erreur écriture .bws", e)
            false
        }
    }

    private fun eq(row: Row, colonne: String, valeur: Int): Boolean {
        val o = row[colonne]
        return (o is Number) && o.toInt() == valeur
    }
}
