package app.resultatsbridge.common.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PbnParserTest {

    // Chaque joueur a une couleur complète : facile à vérifier à la main, et couvre
    // tout le mapping rang PBN -> valeur interne (A,K,Q,J,T,9..2) et position -> couleur (P,C,K,T).
    private val dealToutesLesCouleurs =
        "N:AKQJT98765432... .AKQJT98765432.. ..AKQJT98765432. ...AKQJT98765432"

    private val nordPique = listOf("AP", "RP", "DP", "VP", "10P", "9P", "8P", "7P", "6P", "5P", "4P", "3P", "2P")
    private val estCoeur = listOf("AC", "RC", "DC", "VC", "10C", "9C", "8C", "7C", "6C", "5C", "4C", "3C", "2C")
    private val sudCarreau = listOf("AK", "RK", "DK", "VK", "10K", "9K", "8K", "7K", "6K", "5K", "4K", "3K", "2K")
    private val ouestTrefle = listOf("AT", "RT", "DT", "VT", "10T", "9T", "8T", "7T", "6T", "5T", "4T", "3T", "2T")

    // Format exactement produit par DatabaseManager::genererPBNContent (backend PHP)
    private val exemplePbn = """
        % PBN 2.1
        % EXPORT

        [Event "Tournoi Mitchell #12"]
        [Site "ResultatsBridge"]
        [Date "2026.07.14"]
        [Board "1"]
        [West ""]
        [North ""]
        [East ""]
        [South ""]
        [Dealer "N"]
        [Vulnerable "NS"]
        [Deal "$dealToutesLesCouleurs"]
        [Scoring "MP"]
        [Declarer "?"]
        [Contract "?"]
        [Result ""]

    """.trimIndent()

    @Test
    fun `parse une donne simple`() {
        val donnes = PbnParser.parse(exemplePbn)
        assertEquals(1, donnes.size)

        val donne = donnes[0]
        assertEquals(1, donne.numeroDonne)
        assertEquals("N", donne.donneur)
        assertEquals("NS", donne.vulnerable)

        // Ordre interne de l'appli : Nord, Est, Sud, Ouest
        assertEquals(nordPique, donne.mains[0])
        assertEquals(estCoeur, donne.mains[1])
        assertEquals(sudCarreau, donne.mains[2])
        assertEquals(ouestTrefle, donne.mains[3])
    }

    @Test
    fun `donneur W est mappe vers O en interne, vulnerable All vers T`() {
        val pbn = """
            [Board "2"]
            [Dealer "W"]
            [Vulnerable "All"]
            [Deal "$dealToutesLesCouleurs"]
        """.trimIndent()

        val donne = PbnParser.parse(pbn).single()
        assertEquals("O", donne.donneur)
        assertEquals("T", donne.vulnerable)
    }

    @Test
    fun `deal qui ne commence pas par N est correctement pivote`() {
        // Ici le tag commence par Est : E:mainE mainS mainO mainN
        val pbn = """
            [Board "3"]
            [Dealer "N"]
            [Vulnerable "None"]
            [Deal "E:.AKQJT98765432.. ..AKQJT98765432. ...AKQJT98765432 AKQJT98765432..."]
        """.trimIndent()

        val donne = PbnParser.parse(pbn).single()
        // Peu importe la position de départ du tag, Nord doit retomber sur la même main
        assertEquals(nordPique, donne.mains[0])
        assertEquals(estCoeur, donne.mains[1])
        assertEquals(sudCarreau, donne.mains[2])
        assertEquals(ouestTrefle, donne.mains[3])
    }

    @Test
    fun `deal avec une main incomplete leve une exception explicite`() {
        val pbn = """
            [Board "4"]
            [Deal "N:AKQJT98765432..."]
        """.trimIndent()

        assertThrows(PbnParser.PbnParseException::class.java) {
            PbnParser.parse(pbn)
        }
    }

    @Test
    fun `plusieurs donnes dans le meme fichier sont toutes lues`() {
        val pbn = exemplePbn + "\n\n" + exemplePbn.replace("[Board \"1\"]", "[Board \"2\"]")
        val donnes = PbnParser.parse(pbn)
        assertEquals(2, donnes.size)
        assertEquals(listOf(1, 2), donnes.map { it.numeroDonne })
    }
}
