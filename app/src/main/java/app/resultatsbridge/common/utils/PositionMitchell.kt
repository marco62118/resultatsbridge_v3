package app.resultatsbridge.common.utils

/** true si le type de tournoi appartient à la famille Mitchell (Mitchell ou
 *  MitchellGuéridon) — seule famille où le camp NS/EO est une propriété stable de
 *  l'équipe (contrairement à Howell, où le camp change à chaque mouvement). */
fun estTypeMitchellFamille(typeTournoi: String): Boolean =
    typeTournoi == "Mitchell" || typeTournoi == "MitchellGueridon"

/** Orientation + numéro dans le camp pour une équipe Mitchell/MitchellGuéridon.
 *  EO : équipes 1..nbreTables, campIndex = equipeNumero
 *  NS : équipes nbreTables+1..2*nbreTables, campIndex = equipeNumero - nbreTables
 *  Ne pas appeler pour Howell (le camp n'y est pas une propriété stable de l'équipe). */
fun positionMitchell(equipeNumero: Int, nbreTables: Int): Pair<String, Int> =
    if (equipeNumero <= nbreTables) "EO" to equipeNumero
    else "NS" to (equipeNumero - nbreTables)

/** Libellé court "NS1"/"EO3", ou null si une composante manque. */
fun libelleCampMitchell(orientation: String?, campIndex: Int?): String? =
    if (orientation != null && campIndex != null) "$orientation$campIndex" else null
