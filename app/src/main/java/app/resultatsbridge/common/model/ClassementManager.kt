package app.resultatsbridge.common.model

import android.util.Log
import kotlin.collections.iterator

object ClassementManager {

    const val TYPE_ERREUR_MOYENNE_IND = "ERREUR_MOYENNE_IND"
    const val TYPE_DEFAILLANCE_NS = "DEFAILLANCE_NS" // équipe NS de cette ligne est la fautive
    const val TYPE_DEFAILLANCE_EO = "DEFAILLANCE_EO" // équipe EO de cette ligne est la fautive

    /**
     * Calcule le classement à partir des résultats récupérés du serveur
     * Retourne AUSSI les points Simplifiés par donne pour l'enregistrement en BD
     */
    fun calculerClassementTournoi(
        resultats: List<ResultatLigne>
    ): ResultatCalcul {
        Log.i("ClassementManager", "🏁 Début calcul classement à partir des résultats fournis")

        // ⚠️ Les lignes marquées en erreur (donne injouable, aucune équipe fautive) ou
        // en défaillance (une équipe a quitté le tournoi) sont mises de côté : elles ne
        // participent pas à la comparaison Neuberg de leur donne, elles seront
        // recalculées plus bas via la moyenne individuelle / la règle 40-60.
        val lignesNormales = resultats.filter { it.typeCorrection == null }
        val lignesErreur      = resultats.filter { it.typeCorrection == TYPE_ERREUR_MOYENNE_IND }
        val lignesDefaillance = resultats.filter {
            it.typeCorrection == TYPE_DEFAILLANCE_NS || it.typeCorrection == TYPE_DEFAILLANCE_EO
        }

        // 1️⃣ Regrouper les résultats normaux par donne
        val resultatsParDonne = mutableMapOf<Int, MutableList<ResultatLigne>>()
        for (ligne in lignesNormales) {
            val key = ligne.numeroDonne
            if (!resultatsParDonne.containsKey(key)) resultatsParDonne[key] = ArrayList()
            resultatsParDonne[key]!!.add(ligne)
        }

        Log.i("ClassementManager", "📊 ${resultatsParDonne.size} donnes regroupées (${lignesErreur.size} ligne(s) en erreur, ${lignesDefaillance.size} ligne(s) de défaillance mise(s) de côté)")

        // 2️⃣ Calcul des points par donne (barème Simplifiés simplifié)
        val pointsParEquipe  = mutableMapOf<Int, Double>() // numeroEquipe -> totalPts
        val dealsParEquipe   = mutableMapOf<Int, Int>()    // numeroEquipe -> nb de donnes jouées

        // ✅ NOUVEAU : Stocker les points Simplifiés par donne
        val pointsSimplifiesParDonne = mutableListOf<ResultatLigneAvecPointsSimplifies>()

        val nTables = lignesNormales.map { it.numeroTable }.distinct().size
        // N = référence Neuberg = nombre max de fois où une donne est jouée
        val N    = if (resultatsParDonne.isEmpty()) 0 else resultatsParDonne.values.maxOf { it.size }
        val Ntop = (N - 1) * 2  // TOP de référence commun à toutes les donnes

        Log.i("ClassementManager", "🎯 Nombre de tables: $nTables, N (Neuberg ref): $N, Ntop: $Ntop")

        for ((numeroDonne, lignes) in resultatsParDonne) {
            val nFois = lignes.size
            val top   = (nFois - 1) * 2
            Log.i("ClassementManager", "📋 Donne $numeroDonne jouée $nFois fois → TOP brut=$top, Neuberg N=$N")

            // ✅ CORRECTION CRITIQUE : Calculer le score du point de vue NS
            // Si pointsNS existe → score positif pour NS
            // Si pointsEO existe → score négatif pour NS (car c'est EO qui a gagné)

            data class LigneAvecScore(
                val ligne: ResultatLigne,
                val scoreNS: Int  // Score du point de vue NS (peut être négatif)
            )

            val lignesAvecScores = lignes.map { ligne ->
                val scoreNS = if (ligne.pointsNS != null) {
                    ligne.pointsNS        // NS a gagné (positif)
                } else {
                    -ligne.pointsEO!!     // EO a gagné → négatif pour NS
                }
                LigneAvecScore(ligne, scoreNS)
            }

            // Trier par score NS (meilleur au pire)
            val scoresNS = lignesAvecScores.map { it.scoreNS }.sortedDescending()

            Log.i("ClassementManager", "📋 Donne $numeroDonne - Scores NS triés: $scoresNS")

            val pointsAttribues = mutableMapOf<Int, Double>()

            // Calculer les points pour chaque score unique
            val scoresUniques = scoresNS.distinct()

            for (score in scoresUniques) {
                val indicesEgaux = scoresNS.withIndex()
                    .filter { it.value == score }
                    .map { it.index }

                // ✅ CORRECTION : Utiliser average() pour avoir une vraie moyenne
                val moyennePlace = indicesEgaux.average()

                // Formule Simplifiés : top - (moyennePlace × pas)
                val pas       = if (nFois > 1) top.toDouble() / (nFois - 1) else 2.0
                val scoreReel = top - (moyennePlace * pas)

                // Formule de Neuberg : ajuste le score si cette donne a été jouée moins de N fois
                val scoreAjuste = if (nFois < N) {
                    ((N.toDouble() * (scoreReel + 1.0)) / nFois.toDouble()) - 1.0
                } else {
                    scoreReel
                }

                pointsAttribues[score] = scoreAjuste

                Log.i("ClassementManager",
                    "   Score $score → places $indicesEgaux → moy=${moyennePlace} → brut=${scoreReel} → Neuberg=${"%.1f".format(scoreAjuste)}")
            }

            // Attribution des points aux équipes
            for (ligneAvecScore in lignesAvecScores) {
                val ligne = ligneAvecScore.ligne
                val scoreNS = ligneAvecScore.scoreNS

                val ptsNS = pointsAttribues[scoreNS] ?: 0.0
                val ptsEO = Ntop.toDouble() - ptsNS  // complément Neuberg

                pointsParEquipe[ligne.equipeNS] = (pointsParEquipe[ligne.equipeNS] ?: 0.0) + ptsNS
                pointsParEquipe[ligne.equipeEO] = (pointsParEquipe[ligne.equipeEO] ?: 0.0) + ptsEO
                dealsParEquipe[ligne.equipeNS]  = (dealsParEquipe[ligne.equipeNS]  ?: 0) + 1
                dealsParEquipe[ligne.equipeEO]  = (dealsParEquipe[ligne.equipeEO]  ?: 0) + 1

                pointsSimplifiesParDonne.add(
                    ResultatLigneAvecPointsSimplifies(
                        numeroDonne = ligne.numeroDonne,
                        numeroTable = ligne.numeroTable,
                        equipeNS    = ligne.equipeNS,
                        equipeEO    = ligne.equipeEO,
                        pointsNS    = ligne.pointsNS,
                        pointsEO    = ligne.pointsEO,
                        ptsNS       = ptsNS,
                        ptsEO       = ptsEO
                    )
                )

                Log.i("ClassementManager",
                    "🧮 Donne ${ligne.numeroDonne} Table ${ligne.numeroTable} : " +
                            "Score NS=$scoreNS → Neuberg NS=${"%.1f".format(ptsNS)} EO=${"%.1f".format(ptsEO)}")
            }
        }

        // 3️⃣ Lignes en erreur : moyenne individuelle de chaque équipe
        // Snapshot AVANT injection : sert de base commune à toutes les lignes en
        // erreur, pour qu'une ligne en erreur ne influence jamais la moyenne
        // calculée pour une autre ligne en erreur de la même équipe.
        val pointsBase = pointsParEquipe.toMap()
        val dealsBase  = dealsParEquipe.toMap()

        fun moyenneIndividuelle(numeroEquipe: Int): Double {
            val deals = dealsBase[numeroEquipe] ?: 0
            // Repli 50% si l'équipe n'a encore aucune donne normale (pas de base de calcul)
            return if (deals > 0) (pointsBase[numeroEquipe] ?: 0.0) / deals else Ntop / 2.0
        }

        for (ligne in lignesErreur) {
            val moyenneNS = moyenneIndividuelle(ligne.equipeNS)
            val moyenneEO = moyenneIndividuelle(ligne.equipeEO)

            pointsParEquipe[ligne.equipeNS] = (pointsParEquipe[ligne.equipeNS] ?: 0.0) + moyenneNS
            pointsParEquipe[ligne.equipeEO] = (pointsParEquipe[ligne.equipeEO] ?: 0.0) + moyenneEO
            dealsParEquipe[ligne.equipeNS]  = (dealsParEquipe[ligne.equipeNS]  ?: 0) + 1
            dealsParEquipe[ligne.equipeEO]  = (dealsParEquipe[ligne.equipeEO]  ?: 0) + 1

            pointsSimplifiesParDonne.add(
                ResultatLigneAvecPointsSimplifies(
                    numeroDonne = ligne.numeroDonne,
                    numeroTable = ligne.numeroTable,
                    equipeNS    = ligne.equipeNS,
                    equipeEO    = ligne.equipeEO,
                    pointsNS    = ligne.pointsNS,
                    pointsEO    = ligne.pointsEO,
                    ptsNS       = moyenneNS,
                    ptsEO       = moyenneEO
                )
            )

            Log.i("ClassementManager",
                "⚠️ Donne ${ligne.numeroDonne} Table ${ligne.numeroTable} EN ERREUR → " +
                        "moyenne individuelle NS=${"%.2f".format(moyenneNS)} EO=${"%.2f".format(moyenneEO)}")
        }

        // 4️⃣ Lignes de défaillance : Loi 12.C.2.a WBF/FFB — 40% équipe fautive / 60%
        // équipe adverse, sauf exception 12.C.2.c : si la moyenne réelle d'une équipe
        // est déjà au-delà du seuil dans le sens naturel (fautive déjà sous 40%, ou
        // adverse déjà au-dessus de 60%), elle reçoit sa moyenne réelle à la place du
        // taux fixe — jamais pour adoucir, seulement pour ne pas plafonner/sur-pénaliser.
        // Utilise le même snapshot pointsBase/dealsBase que les lignes en erreur.
        for (ligne in lignesDefaillance) {
            val equipeFautive = if (ligne.typeCorrection == TYPE_DEFAILLANCE_NS) ligne.equipeNS else ligne.equipeEO
            val equipeAdverse = if (ligne.typeCorrection == TYPE_DEFAILLANCE_NS) ligne.equipeEO else ligne.equipeNS

            val ptsFautive = minOf(Ntop * 0.4, moyenneIndividuelle(equipeFautive))
            val ptsAdverse = maxOf(Ntop * 0.6, moyenneIndividuelle(equipeAdverse))

            val ptsNS = if (ligne.typeCorrection == TYPE_DEFAILLANCE_NS) ptsFautive else ptsAdverse
            val ptsEO = if (ligne.typeCorrection == TYPE_DEFAILLANCE_NS) ptsAdverse else ptsFautive

            pointsParEquipe[ligne.equipeNS] = (pointsParEquipe[ligne.equipeNS] ?: 0.0) + ptsNS
            pointsParEquipe[ligne.equipeEO] = (pointsParEquipe[ligne.equipeEO] ?: 0.0) + ptsEO
            dealsParEquipe[ligne.equipeNS]  = (dealsParEquipe[ligne.equipeNS]  ?: 0) + 1
            dealsParEquipe[ligne.equipeEO]  = (dealsParEquipe[ligne.equipeEO]  ?: 0) + 1

            pointsSimplifiesParDonne.add(
                ResultatLigneAvecPointsSimplifies(
                    numeroDonne = ligne.numeroDonne,
                    numeroTable = ligne.numeroTable,
                    equipeNS    = ligne.equipeNS,
                    equipeEO    = ligne.equipeEO,
                    pointsNS    = ligne.pointsNS,
                    pointsEO    = ligne.pointsEO,
                    ptsNS       = ptsNS,
                    ptsEO       = ptsEO
                )
            )

            Log.i("ClassementManager",
                "🚫 Donne ${ligne.numeroDonne} Table ${ligne.numeroTable} DÉFAILLANCE → " +
                        "fautive=$equipeFautive (${"%.2f".format(ptsFautive)}) adverse=$equipeAdverse (${"%.2f".format(ptsAdverse)})")
        }

        // 5️⃣ Attribution des rangs
        val classement = ArrayList<ClassementEquipe>()
        val sorted = pointsParEquipe.entries.sortedByDescending { it.value }
        var rang = 1
        var precedentScore = Double.MIN_VALUE

        for ((index, entry) in sorted.withIndex()) {
            val numeroEquipe = entry.key
            val totalPts = entry.value
            if (totalPts != precedentScore) {
                rang = index + 1
                precedentScore = totalPts
            }
            val nDeals   = dealsParEquipe[numeroEquipe] ?: 0
            val scorePct = if (nDeals > 0 && Ntop > 0) (totalPts / (nDeals.toDouble() * Ntop)) * 100.0 else 0.0
            classement.add(ClassementEquipe(numeroEquipe, totalPts, rang, scorePct))
            Log.i("ClassementManager", "🥇 Équipe $numeroEquipe → ${"%.1f".format(totalPts)} pts (rang $rang)")
        }

        Log.i("ClassementManager", "✅ Classement calculé")

        // ✅ Retourner à la fois le classement ET les points par donne
        return ResultatCalcul(classement, pointsSimplifiesParDonne)
    }

    // ✅ Data class pour retourner les deux résultats
    data class ResultatCalcul(
        val classement: List<ClassementEquipe>,
        val pointsParDonne: List<ResultatLigneAvecPointsSimplifies>
    )

    // ✅ Data class avec les points Simplifiés
    data class ResultatLigneAvecPointsSimplifies(
        val numeroDonne: Int,
        val numeroTable: Int,
        val equipeNS: Int,
        val equipeEO: Int,
        val pointsNS: Int?,    // Points bruts
        val pointsEO: Int?,    // Points bruts
        val ptsNS: Double,     // Points Simplifiés Neuberg
        val ptsEO: Double      // Points Simplifiés Neuberg
    )

    data class ResultatLigne(
        val numeroDonne: Int,
        val numeroTable: Int,
        val equipeNS: Int,
        val equipeEO: Int,
        val pointsNS: Int?,
        val pointsEO: Int?,
        val typeCorrection: String? = null // null = normal, TYPE_ERREUR_MOYENNE_IND = erreur sur la donne
    )

    data class ClassementEquipe(
        val numeroEquipe: Int,
        val totalPts: Double,
        val rang: Int,
        val scorePct: Double = 0.0
    )
}