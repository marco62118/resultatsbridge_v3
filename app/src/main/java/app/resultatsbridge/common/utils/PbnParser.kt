package app.resultatsbridge.common.utils

import app.resultatsbridge.common.model.DonneAvantTournoi

/**
 * Lit un fichier PBN (format standard bridge) et produit une liste de [DonneAvantTournoi].
 * Contrepartie de la génération PBN déjà existante côté serveur PHP
 * (`DatabaseManager::genererPBNContent` / `mainToPBN`) — mêmes conventions de format.
 */
object PbnParser {

    private val rangPbnVersValeur = mapOf(
        'A' to "A", 'K' to "R", 'Q' to "D", 'J' to "V", 'T' to "10",
        '9' to "9", '8' to "8", '7' to "7", '6' to "6", '5' to "5", '4' to "4", '3' to "3", '2' to "2"
    )

    // Ordre standard PBN dans le tag [Deal] : Pique.Coeur.Carreau.Trèfle
    private val couleursParPosition = listOf("P", "C", "K", "T")

    private val ordreBoussole = listOf("N", "E", "S", "W")

    private val vulnerablePbnVersInterne = mapOf(
        "NONE" to "P", "LOVE" to "P", "-" to "P",
        "NS" to "NS",
        "EW" to "EO",
        "ALL" to "T", "BOTH" to "T"
    )

    class PbnParseException(message: String) : Exception(message)

    fun parse(texte: String): List<DonneAvantTournoi> {
        val blocs = texte.split(Regex("\\r?\\n\\s*\\r?\\n"))
        val tagRegex = Regex("\\[(\\S+)\\s+\"([^\"]*)\"]")

        var compteurAutoNumero = 1
        val resultat = mutableListOf<DonneAvantTournoi>()

        for (bloc in blocs) {
            val tags = tagRegex.findAll(bloc).associate { it.groupValues[1] to it.groupValues[2] }
            val dealTag = tags["Deal"] ?: continue // bloc sans donne (commentaires, en-tête "% PBN 2.1"...)

            val numeroDonne = tags["Board"]?.toIntOrNull() ?: compteurAutoNumero
            compteurAutoNumero = numeroDonne + 1

            val donneurPbn = (tags["Dealer"] ?: "N").uppercase()
            val donneur = if (donneurPbn == "W") "O" else donneurPbn

            val vulnerable = vulnerablePbnVersInterne[(tags["Vulnerable"] ?: "None").uppercase()] ?: "P"

            resultat.add(DonneAvantTournoi(numeroDonne, donneur, vulnerable, parseDeal(dealTag, numeroDonne)))
        }

        return resultat
    }

    private fun parseDeal(dealTag: String, numeroDonne: Int): List<List<String>> {
        val sepIdx = dealTag.indexOf(':')
        if (sepIdx == -1) throw PbnParseException("Donne $numeroDonne : tag [Deal] invalide (\"$dealTag\")")

        val premierePosition = dealTag.substring(0, sepIdx).uppercase()
        val mainsBrutes = dealTag.substring(sepIdx + 1).trim().split(Regex("\\s+"))
        if (mainsBrutes.size != 4) {
            throw PbnParseException("Donne $numeroDonne : [Deal] doit contenir 4 mains, ${mainsBrutes.size} trouvée(s)")
        }

        val depart = ordreBoussole.indexOf(premierePosition)
        if (depart == -1) throw PbnParseException("Donne $numeroDonne : position de départ \"$premierePosition\" inconnue dans [Deal]")

        // mainParPosition["N"], ["E"], ["S"], ["W"] à partir de la rotation qui commence à $premierePosition
        val mainParPosition = mutableMapOf<String, String>()
        for (i in 0..3) {
            mainParPosition[ordreBoussole[(depart + i) % 4]] = mainsBrutes[i]
        }

        // Ordre interne de l'appli : Nord, Est, Sud, Ouest (voir DatabaseManager.getMainsAvantTournoi)
        return listOf("N", "E", "S", "W").map { position ->
            parseMain(mainParPosition[position] ?: "", numeroDonne, position)
        }
    }

    private fun parseMain(main: String, numeroDonne: Int, position: String): List<String> {
        val couleurs = main.split(".")
        if (couleurs.size != 4) {
            throw PbnParseException("Donne $numeroDonne, main $position : 4 couleurs attendues séparées par '.', trouvé \"$main\"")
        }

        val cartes = mutableListOf<String>()
        couleurs.forEachIndexed { index, rangs ->
            val couleurCode = couleursParPosition[index]
            for (rangPbn in rangs) {
                val valeur = rangPbnVersValeur[rangPbn.uppercaseChar()]
                    ?: throw PbnParseException("Donne $numeroDonne, main $position : rang \"$rangPbn\" inconnu")
                cartes.add(valeur + couleurCode)
            }
        }

        if (cartes.size != 13) {
            throw PbnParseException("Donne $numeroDonne, main $position : 13 cartes attendues, ${cartes.size} trouvée(s)")
        }
        return cartes
    }
}
