package app.resultatsbridge.client

import android.util.Log

object DdsCalculator {

    init { System.loadLibrary("dds_jni") }

    // ─── JNI ──────────────────────────────────────────────────────────────────
    // cards[main*4+couleur] bitmask ; denom 0=♠1=♥2=♦3=♣ ; déclarant 0=N1=E2=S3=W
    private external fun calculerTableNatif(cards: IntArray): IntArray?

    // ─── Résultat public ──────────────────────────────────────────────────────
    data class ResultatPar(
        val scoreNS: Int,      // NS perspective : +420, -100, 0…
        val texte: String,     // "4♥ NS N = +420"  ou  "Passe = 0"
        val ddTable: IntArray  // 20 levées pour affichage détaillé éventuel
    )

    /**
     * Calcule le par d'une donne.
     * mainsParJoueur : map "N"/"E"/"S"/"O" → 13 codes carte français ("RP","10C",…)
     * vulnerable     : "P"=aucun  "NS"=NS  "EO"=EO  "T"=tous
     * Retourne null si les mains sont incomplètes ou si DDS échoue.
     */
    fun calculer(mainsParJoueur: Map<String, List<String>>, vulnerable: String): ResultatPar? {
        val cards = convertirCartes(mainsParJoueur) ?: run {
            Log.w("DDS", "Mains incomplètes — par non calculé")
            return null
        }
        val ddTable = try {
            calculerTableNatif(cards)
        } catch (e: UnsatisfiedLinkError) {
            Log.e("DDS", "Bibliothèque DDS non chargée: ${e.message}")
            return null
        } ?: return null

        val vulnInt = when (vulnerable.uppercase()) { "NS" -> 1; "EO" -> 2; "T" -> 3; else -> 0 }
        val (score, texte) = parDepuisTable(ddTable, vulnInt)
        return ResultatPar(score, texte, ddTable)
    }

    // ─── Conversion codes carte français → bitmask DDS ───────────────────────
    private fun convertirCartes(mains: Map<String, List<String>>): IntArray? {
        val valeurs  = mapOf("A" to 14,"R" to 13,"D" to 12,"V" to 11,"10" to 10,
                             "9" to 9,"8" to 8,"7" to 7,"6" to 6,"5" to 5,"4" to 4,"3" to 3,"2" to 2)
        val couleurs = mapOf("P" to 0,"C" to 1,"K" to 2,"T" to 3) // ♠♥♦♣ en DDS
        val joueurs  = mapOf("N" to 0,"E" to 1,"S" to 2,"O" to 3)
        val cards    = IntArray(16)
        for ((joueur, handIdx) in joueurs) {
            val cartes = mains[joueur] ?: return null
            if (cartes.size != 13) return null
            for (code in cartes) {
                if (code == "?" || code.length < 2) return null
                val suit   = couleurs[code.last().toString()] ?: return null
                val valInt = valeurs[code.dropLast(1)] ?: return null
                cards[handIdx * 4 + suit] = cards[handIdx * 4 + suit] or (1 shl valInt)
            }
        }
        return cards
    }

    // ─── Calcul par depuis la table DD (sans sacrifice complexe = V1) ─────────
    // ddTable[denom*4+déclarant] = levées
    // denom : 0=♠  1=♥  2=♦  3=♣  4=SA
    // déclarant : 0=N  1=E  2=S  3=W
    private fun parDepuisTable(ddTable: IntArray, vulnerable: Int): Pair<Int, String> {
        val vulnNS = vulnerable == 1 || vulnerable == 3
        val vulnEW = vulnerable == 2 || vulnerable == 3

        fun tricksNS(d: Int) = maxOf(ddTable[d * 4 + 0], ddTable[d * 4 + 2])
        fun tricksEO(d: Int) = maxOf(ddTable[d * 4 + 1], ddTable[d * 4 + 3])
        fun declNS(d: Int)   = if (ddTable[d * 4 + 0] >= ddTable[d * 4 + 2]) 'N' else 'S'
        fun declEO(d: Int)   = if (ddTable[d * 4 + 1] >= ddTable[d * 4 + 3]) 'E' else 'O'

        val sym = listOf("♠","♥","♦","♣","SA")

        fun score(level: Int, denom: Int, vul: Boolean): Int {
            val pts = when {
                denom == 4            -> 40 + maxOf(0, level - 1) * 30
                denom == 0 || denom == 1 -> level * 30
                else                  -> level * 20
            }
            val game  = if (pts >= 100) (if (vul) 500 else 300) else 50
            val slam  = when (level) { 6 -> if (vul) 750 else 500; 7 -> if (vul) 1500 else 1000; else -> 0 }
            return pts + game + slam
        }

        fun penalite(down: Int, vul: Boolean): Int {
            if (down <= 0) return 0
            return if (!vul) when {
                down == 1 -> 100; down == 2 -> 300; down == 3 -> 500
                else -> 500 + (down - 3) * 300
            } else when {
                down == 1 -> 200; down == 2 -> 500; down == 3 -> 800
                else -> 800 + (down - 3) * 300
            }
        }

        data class Best(val level: Int, val denom: Int, val side: String, val decl: Char, val score: Int)

        fun findBest(side: String): Best? {
            val vul = if (side == "NS") vulnNS else vulnEW
            var best: Best? = null
            for (denom in 4 downTo 0) {
                val level = (if (side == "NS") tricksNS(denom) else tricksEO(denom)) - 6
                if (level < 1) continue
                val s = score(level, denom, vul)
                // préférer score plus élevé, ou même score à niveau inférieur (3SA > 5♦)
                if (best == null || s > best.score || (s == best.score && level < best.level)) {
                    val d = if (side == "NS") declNS(denom) else declEO(denom)
                    best = Best(level, denom, side, d, s)
                }
            }
            return best
        }

        val bestNS = findBest("NS")
        val bestEW = findBest("EO")
        if (bestNS == null && bestEW == null) return Pair(0, "Passe = 0")

        val nsScore = bestNS?.score ?: 0
        val eoScore = bestEW?.score ?: 0

        // Paire (pénalité, denom) pour trouver le sacrifice le moins cher avec sa couleur
        data class Sac(val penalty: Int, val denom: Int)

        return if (nsScore >= eoScore) {
            val b = bestNS!!
            val sacEO = (0..4).mapNotNull { d ->
                val down = (b.level + 6 + 1) - tricksEO(d)
                if (down > 0) { val p = penalite(down, vulnEW); if (p < nsScore) Sac(p, d) else null } else null
            }.minByOrNull { it.penalty }
            if (sacEO != null)
                Pair(sacEO.penalty, "Sacrifice EO ${b.level + 1}${sym[sacEO.denom]} = -${sacEO.penalty}")
            else
                Pair(b.score, "${b.level}${sym[b.denom]} ${b.side} ${b.decl} = +${b.score}")
        } else {
            val b = bestEW!!
            val sacNS = (0..4).mapNotNull { d ->
                val down = (b.level + 6 + 1) - tricksNS(d)
                if (down > 0) { val p = penalite(down, vulnNS); if (p < eoScore) Sac(p, d) else null } else null
            }.minByOrNull { it.penalty }
            if (sacNS != null)
                Pair(-sacNS.penalty, "Sacrifice NS ${b.level + 1}${sym[sacNS.denom]} = -${sacNS.penalty}")
            else
                Pair(-b.score, "${b.level}${sym[b.denom]} ${b.side} ${b.decl} = +${b.score}")
        }
    }
}
