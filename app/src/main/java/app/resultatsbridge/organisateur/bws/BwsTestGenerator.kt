package app.resultatsbridge.organisateur.bws

import android.content.Context
import android.util.Log
import com.healthmarketscience.jackcess.DatabaseBuilder
import java.io.File
import java.util.Date
import java.util.UUID

/**
 * Génère un fichier .bws AUTONOME (Session/Section/Tables/RoundData +
 * lignes ReceivedData vides), pour tester l'écriture des scores sans
 * dépendre d'un vrai fichier créé par FFBClubNet (pas encore disponible).
 * PAS la logique de production : dans le vrai flux, c'est FFBClubNet qui
 * crée et remplit ces tables, notre appli ne fait qu'écrire les scores
 * dans ReceivedData (voir FFB_Homologation_Tournoi.md).
 * Limité au type Mitchell pour l'instant.
 */
object BwsTestGenerator {

    private const val ASSET_TEMPLATE = "bws_template_mitchell.bws"

    fun genererPourTournoiMitchell(
        context: Context,
        idTournoi: Int,
        nbreTables: Int,
        nbreMouvements: Int,
        nbreDonnesParTable: Int
    ): File? {
        if (nbreTables <= 0 || nbreMouvements <= 0 || nbreDonnesParTable <= 0) {
            Log.e("BwsTestGenerator", "❌ Paramètres invalides : tables=$nbreTables mvnts=$nbreMouvements donnes/table=$nbreDonnesParTable")
            return null
        }

        val fichier = BwsPaths.fichier(context, idTournoi).apply { parentFile?.mkdirs() }

        return try {
            context.assets.open(ASSET_TEMPLATE).use { input ->
                fichier.outputStream().use { output -> input.copyTo(output) }
            }

            val db = DatabaseBuilder.open(fichier)
            try {
                // javax.script (utilisé par Jackcess pour évaluer les expressions
                // Access — valeurs par défaut/règles de validation) n'existe pas
                // sur Android → NoClassDefFoundError sinon dès le premier getTable().
                db.setEvaluateExpressions(false)

                val maintenant = Date()

                db.getTable("Session").addRowFromMap(
                    linkedMapOf(
                        "ID" to 1,
                        "Name" to "Tournoi $idTournoi",
                        "Date" to maintenant,
                        "Time" to maintenant,
                        "GUID" to UUID.randomUUID().toString(),
                        "Status" to 0,
                        "ShowInApp" to true,
                        "PairsMoveAcrossField" to false,
                        "EWReturnHome" to false
                    )
                )

                db.getTable("Section").addRowFromMap(
                    linkedMapOf(
                        "ID" to 1,
                        "Letter" to "A",
                        "Tables" to nbreTables,
                        "MissingPair" to 0,
                        "EWMoveBeforePlay" to 0,
                        "Session" to 1,
                        "ScoringType" to 0,
                        "Winners" to 1
                    )
                )

                val tablesTable = db.getTable("Tables")
                val roundDataTable = db.getTable("RoundData")
                val receivedDataTable = db.getTable("ReceivedData")

                for (table in 1..nbreTables) {
                    tablesTable.addRowFromMap(
                        linkedMapOf(
                            "Section" to 1,
                            "Table" to table,
                            "ComputerID" to table,
                            "Status" to 0,
                            "LogOnOff" to 0,
                            "CurrentRound" to 0,
                            "CurrentBoard" to 0,
                            "UpdateFromRound" to 0,
                            "Group" to 0
                        )
                    )

                    for (round in 1..nbreMouvements) {
                        // Même formule que DatabaseManager.getMouvementMitchell(), appliquée
                        // directement par table physique plutôt que par équipe (NS fixe par table).
                        val offset = if (nbreTables % 2 == 0 && round > nbreTables / 2) 1 else 0

                        var equipeEO = table - round + 1 - offset
                        if (equipeEO <= 0) equipeEO += nbreTables

                        var setDonnes = table + round - 1
                        if (setDonnes > nbreTables) setDonnes -= nbreTables
                        val premiereDonne = (setDonnes - 1) * nbreDonnesParTable + 1
                        val derniereDonne = setDonnes * nbreDonnesParTable

                        // NSPair = numéro de table (NS fixe), comme chez FFBClubNet —
                        // pas equipeNS interne à V3 (table + nbreTables), voir le même
                        // correctif dans DatabaseManager.enregistreDonne.
                        roundDataTable.addRowFromMap(
                            linkedMapOf(
                                "Section" to 1,
                                "Table" to table,
                                "Round" to round,
                                "NSPair" to table,
                                "EWPair" to equipeEO,
                                "LowBoard" to premiereDonne,
                                "HighBoard" to derniereDonne
                            )
                        )

                        for (board in premiereDonne..derniereDonne) {
                            // Ligne vide : mêmes clés que le seed de test du prototype
                            // resultatsbridge_relais_ffb/src/WriteScore.java, déjà validé.
                            receivedDataTable.addRowFromMap(
                                linkedMapOf(
                                    "Section" to 1,
                                    "Table" to table,
                                    "Round" to round,
                                    "Board" to board,
                                    "PairNS" to table,
                                    "PairEW" to equipeEO
                                )
                            )
                        }
                    }
                }

                Log.i("BwsTestGenerator", "✅ .bws de test généré : ${fichier.absolutePath} " +
                        "(tables=$nbreTables mvnts=$nbreMouvements donnes/table=$nbreDonnesParTable)")
            } finally {
                db.close()
            }
            fichier
        } catch (e: Exception) {
            Log.e("BwsTestGenerator", "❌ Erreur génération .bws de test", e)
            null
        }
    }
}
