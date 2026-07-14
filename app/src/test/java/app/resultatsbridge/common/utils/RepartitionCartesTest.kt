package app.resultatsbridge.common.utils

import app.resultatsbridge.common.model.DonneAvantTournoi
import org.junit.Assert.assertEquals
import org.junit.Test

class RepartitionCartesTest {

    // Nord = toutes les Piques, Est = tous les Coeurs, Sud = tous les Carreaux, Ouest = tous les Trèfles
    private val donneCible = DonneAvantTournoi(
        numeroDonne = 1,
        donneur = "N",
        vulnerable = "P",
        mains = listOf(
            listOf("AP", "RP", "DP", "VP", "10P", "9P", "8P", "7P", "6P", "5P", "4P", "3P", "2P"),
            listOf("AC", "RC", "DC", "VC", "10C", "9C", "8C", "7C", "6C", "5C", "4C", "3C", "2C"),
            listOf("AK", "RK", "DK", "VK", "10K", "9K", "8K", "7K", "6K", "5K", "4K", "3K", "2K"),
            listOf("AT", "RT", "DT", "VT", "10T", "9T", "8T", "7T", "6T", "5T", "4T", "3T", "2T")
        )
    )

    @Test
    fun `un lot melange est correctement reparti par joueur`() {
        // Un lot quelconque de 13 cartes, sans lien avec l'ordre des piles physiques
        val lot = listOf("AP", "RC", "DK", "VT", "9P", "8C", "7K", "6T", "5P", "4C", "3K", "2T", "10P")

        val resultat = RepartitionCartes.calculer(donneCible, lot)

        assertEquals(listOf("AP", "9P", "5P", "10P"), resultat.parJoueur["N"])
        assertEquals(listOf("RC", "8C", "4C"), resultat.parJoueur["E"])
        assertEquals(listOf("DK", "7K", "3K"), resultat.parJoueur["S"])
        assertEquals(listOf("VT", "6T", "2T"), resultat.parJoueur["O"])
        assertEquals(emptyList<String>(), resultat.nonReconnues)
    }

    @Test
    fun `une carte absente de la donne cible est signalee, pas perdue`() {
        // "XX" n'existe dans aucune des 4 mains cibles (erreur de reconnaissance photo)
        val lot = listOf("AP", "XX")

        val resultat = RepartitionCartes.calculer(donneCible, lot)

        assertEquals(listOf("AP"), resultat.parJoueur["N"])
        assertEquals(listOf("XX"), resultat.nonReconnues)
    }

    @Test
    fun `un lot vide donne un resultat vide sans erreur`() {
        val resultat = RepartitionCartes.calculer(donneCible, emptyList())
        RepartitionCartes.positions.forEach { assertEquals(emptyList<String>(), resultat.parJoueur[it]) }
        assertEquals(emptyList<String>(), resultat.nonReconnues)
    }
}
