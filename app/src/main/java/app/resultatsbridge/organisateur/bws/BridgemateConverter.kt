package app.resultatsbridge.organisateur.bws

/**
 * Convertit le format interne V3 (après normaliserAnnonce) vers la notation
 * Bridgemate de la table ReceivedData. Port Kotlin du prototype
 * resultatsbridge_relais_ffb/src/BridgemateConverter.java (6 cas de test
 * déjà vérifiés à la main contre le guide développeur Bridgemate, voir
 * lancer_test.bat).
 *
 * Entrée attendue (miroir de DonneDataAEnregistrer côté V3) :
 *   contrat     : "3SA", "4CX", "7TXX", "1P", "0" (passe générale), "" (non joué)
 *   declarant   : "N", "S", "E", "O" (vide si passe générale / non joué)
 *   signe       : "+", "=", "-"
 *   plis        : sur/sous-levées (0 si signe "=")
 *   carteEntame : "AsP", "10K", "" (vide si pas d'entame)
 */
object BridgemateConverter {

    data class Resultat(
        val contract: String = "",
        val result: String = "",
        val leadCard: String = "",
        val declarer: Int? = null,
        val nsEw: String = "",
        val remarks: String = ""
    )

    private val CONTRAT_PATTERN = Regex("^(\\d)(SA|P|C|K|T)(X{1,2})?$")
    private val CARTE_PATTERN = Regex("^(As|R|D|V|10|[2-9])(SA|P|C|K|T)$")

    // Suite normalisée côté appli (P/C/K/T/SA) -> lettre Bridgemate (S/H/D/C/NT)
    private fun suiteVersBridgemate(s: String): String = when (s) {
        "P" -> "S"  // Pique -> Spades
        "C" -> "H"  // Coeur -> Hearts
        "K" -> "D"  // Carreau -> Diamonds
        "T" -> "C"  // Trèfle -> Clubs
        "SA" -> "NT"
        else -> throw IllegalArgumentException("Suite inconnue : $s")
    }

    // Rang normalisé côté appli ("As","R","D","V","10".."2") -> notation Bridgemate (A,K,Q,J,10..2)
    private fun rangVersBridgemate(r: String): String = when (r) {
        "As" -> "A"
        "R" -> "K"
        "D" -> "Q"
        "V" -> "J"
        else -> r // "10".."2" déjà tels quels
    }

    fun convertir(
        contrat: String,
        declarant: String,
        signe: String,
        plis: Int,
        carteEntame: String,
        enErreurArbitre: Boolean,
        equipeNS: Int,
        equipeEO: Int
    ): Resultat {
        if (enErreurArbitre) {
            return Resultat(remarks = "Arbitral score")
        }

        if (contrat.isEmpty() || contrat == "0") {
            // Passe générale : personne n'a déclaré
            return Resultat(contract = "PASS")
        }

        val m = CONTRAT_PATTERN.matchEntire(contrat)
            ?: throw IllegalArgumentException("Contrat non reconnu : $contrat")
        val niveau = m.groupValues[1]
        val suite = suiteVersBridgemate(m.groupValues[2])
        val contre = m.groupValues[3].takeIf { it.isNotEmpty() }

        val contract = niveau + " " + suite + (contre?.let { " " + it.lowercase() } ?: "")

        // Déclarant : côté (NS/EW) + numéro de la paire correspondante
        val coteNS = declarant == "N" || declarant == "S"
        val nsEw = if (coteNS) "NS" else "EW"
        val declarer = if (coteNS) equipeNS else equipeEO

        val result = when (signe) {
            "=" -> "="
            "+" -> "+$plis"
            else -> "-$plis"
        }

        var leadCard = ""
        if (carteEntame.isNotEmpty()) {
            val mc = CARTE_PATTERN.matchEntire(carteEntame)
                ?: throw IllegalArgumentException("Carte d'entame non reconnue : $carteEntame")
            val rang = rangVersBridgemate(mc.groupValues[1])
            val suiteEntame = suiteVersBridgemate(mc.groupValues[2])
            if (suiteEntame == "NT") {
                throw IllegalArgumentException("Entame ne peut pas être Sans Atout : $carteEntame")
            }
            leadCard = suiteEntame + rang
        }

        return Resultat(
            contract = contract,
            result = result,
            leadCard = leadCard,
            declarer = declarer,
            nsEw = nsEw
        )
    }
}
