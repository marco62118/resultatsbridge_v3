package app.resultatsbridge.common.utils

import app.resultatsbridge.common.model.DonneAvantTournoi

/**
 * Pour un lot de cartes identifiées par photo et une donne cible connue à l'avance,
 * indique à quel joueur chaque carte appartient. Pas un calcul : la donne cible
 * définit déjà qui a quelle carte, il s'agit d'un simple regroupement.
 */
object RepartitionCartes {

    // Ordre interne de l'appli : voir DonneAvantTournoi.mains (Nord, Est, Sud, Ouest)
    val positions = listOf("N", "E", "S", "O")

    data class Resultat(
        val parJoueur: Map<String, List<String>>,
        // Cartes identifiées par la photo mais absentes de la donne cible :
        // erreur de reconnaissance, ou carte en double.
        val nonReconnues: List<String>
    )

    fun calculer(donneCible: DonneAvantTournoi, cartesIdentifiees: List<String>): Resultat {
        val proprietaire = mutableMapOf<String, String>()
        donneCible.mains.forEachIndexed { i, main ->
            main.forEach { carte -> proprietaire[carte] = positions[i] }
        }

        val parJoueur = positions.associateWith { mutableListOf<String>() }
        val nonReconnues = mutableListOf<String>()

        cartesIdentifiees.forEach { carte ->
            val joueur = proprietaire[carte]
            if (joueur != null) {
                parJoueur.getValue(joueur).add(carte)
            } else {
                nonReconnues.add(carte)
            }
        }

        return Resultat(parJoueur, nonReconnues)
    }
}
