package app.resultatsbridge.organisateur.data
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.resultatsbridge.common.model.ClassementManager
import app.resultatsbridge.common.model.ClassementManager4Equ2T21D
import app.resultatsbridge.common.model.Equipe
import app.resultatsbridge.common.model.Joueur
import app.resultatsbridge.common.model.Mouvement
import app.resultatsbridge.common.model.MouvementResult
import app.resultatsbridge.common.model.DonneDetail
import app.resultatsbridge.common.model.TournoiConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import app.resultatsbridge.common.model.Carte
import app.resultatsbridge.common.model.DonneResultatDetail
import app.resultatsbridge.common.model.AnnonceJoueur
import app.resultatsbridge.common.model.ChangementDeMouvement
import app.resultatsbridge.common.model.ChangementDeMouvementEntry
import app.resultatsbridge.common.model.DonneComplete
import app.resultatsbridge.common.model.DonneAvantTournoi
import app.resultatsbridge.common.model.EquipeDonneInfo

import app.resultatsbridge.common.model.ErreurLogItem
import app.resultatsbridge.organisateur.bws.BwsTestGenerator
import app.resultatsbridge.organisateur.bws.BridgemateConverter
import app.resultatsbridge.organisateur.bws.BwsPaths
import app.resultatsbridge.organisateur.bws.BwsWriter
import app.resultatsbridge.organisateur.bws.BwsHandImporter
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


object DatabaseManager {

    // ─────────────────────────────────────────────────────────────────────────
    // Identifiant du type de tournoi à calcul croisé EBL
    // ─────────────────────────────────────────────────────────────────────────
    private const val TYPE_PAR4EQU2T21D = "par4equ2t21d"

    fun getListeTypesTournoi(context: Context): List<Triple<String, Int, Int>> {
        val liste = mutableListOf<Triple<String, Int, Int>>()
        val db = DatabaseHelper.getDatabase(context)
        return try {
            db.rawQuery("SELECT type, nombre_table, nombre_donne FROM type", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        val nom = cursor.getString(0)
                        val nombre_tables = cursor.getInt(1)
                        val nombreDonnes = cursor.getInt(2)
                        liste.add(Triple(nom, nombre_tables, nombreDonnes))
                    } while (cursor.moveToNext())
                }
            }
            liste
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur getListeTypesTournoi", e)
            emptyList()
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    fun getTournoiOuvert(context: Context): Pair<Int, String>? {
        Log.i("DatabaseManager", "🔍 getTournoiOuvert appelé")
        val db = DatabaseHelper.getDatabase(context)
        return try {
            db.rawQuery(
                "SELECT ID, type, nbre_equipe, nbre_mouvements, nbre_donnes_par_table, nbre_tables FROM tournois WHERE ouvert = 1 LIMIT 1",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val id                = cursor.getInt(cursor.getColumnIndexOrThrow("ID"))
                    val type              = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                    cursor.getInt(cursor.getColumnIndexOrThrow("nbre_equipe"))
                    val nbreMouvements    = cursor.getInt(cursor.getColumnIndexOrThrow("nbre_mouvements"))
                    val nbreDonnesParTable = cursor.getInt(cursor.getColumnIndexOrThrow("nbre_donnes_par_table"))
                    val nbreTables        = cursor.getInt(cursor.getColumnIndexOrThrow("nbre_tables"))

                    TournoiConfig.NBRE_MOUVEMENTS       = nbreMouvements
                    TournoiConfig.NBRE_DONNES_PAR_TABLE = nbreDonnesParTable
                    TournoiConfig.NBRE_TABLES           = nbreTables

                    TournoiConfig.EQUIPE_RELAIS = db.rawQuery(
                        """SELECT e.equipe_numero FROM equipes e
                   JOIN joueurs j1 ON j1.ID = e.id_joueur1
                   JOIN joueurs j2 ON j2.ID = e.id_joueur2
                   WHERE e.id_tournoi = ?
                   AND (LOWER(j1.nom) = 'relais' OR LOWER(j2.nom) = 'relais')
                   LIMIT 1""",
                        arrayOf(id.toString())
                    ).use { cursorRelais ->
                        if (cursorRelais.moveToFirst()) {
                            val num = cursorRelais.getInt(0)
                            Log.i("DatabaseManager", "🏖️ Équipe relais détectée : $num")
                            num
                        } else {
                            Log.i("DatabaseManager", "✅ Pas d'équipe relais")
                            null
                        }
                    }

                    Log.i("DatabaseManager", "✅ Tournoi ouvert : ID=$id, type=$type, " +
                            "mvnts=${TournoiConfig.NBRE_MOUVEMENTS}, donnes/table=${TournoiConfig.NBRE_DONNES_PAR_TABLE}, " +
                            "relais=${TournoiConfig.EQUIPE_RELAIS ?: "aucun"}")
                    id to type
                } else {
                    Log.i("DatabaseManager", "❌ Aucun tournoi ouvert")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur getTournoiOuvert", e)
            null
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    fun creerTournoi(
        context: Context,
        type: String,
        nbreEquipes: Int,
        nbreDonnes: Int,
        nbreEnregistrement: Int,
        codeArbitre: String = ""
    ): Long {
        val nbreMouvements     = nbreEquipes - 1
        val nbreDonnesParTable = if (nbreMouvements > 0) nbreDonnes / nbreMouvements else 0
        TournoiConfig.NBRE_MOUVEMENTS       = nbreMouvements
        TournoiConfig.NBRE_DONNES_PAR_TABLE = nbreDonnesParTable
        TournoiConfig.NBRE_TABLES           = 0
        Log.i("DatabaseManager", "✅ TournoiConfig mis à jour : mvnts=$nbreMouvements, donnes/table=$nbreDonnesParTable")
        val db = DatabaseHelper.getDatabase(context)
        return try {
            val values = ContentValues().apply {
                put("date", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
                put("ouvert", 0)
                put("mouvement", "")
                put("nbre_enregistrement", nbreEnregistrement)
                put("nbre_donne_total", nbreDonnes)
                put("nbre_equipe", nbreEquipes)
                put("id_premiere_donne", 0)
                put("type", type)
                put("nbre_mouvements",       nbreMouvements)
                put("nbre_donnes_par_table", nbreDonnesParTable)
                put("nbre_tables",           0)
                put("code_arbitre",         codeArbitre)
            }
            val id = db.insert("tournois", null, values)
            Log.i("DatabaseManager", "✅ Tournoi créé avec ID : $id")
            id
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur creerTournoi", e)
            -1L
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // MITCHELL : création du tournoi avec valeurs provisoires.
    // nbreEquipes et nbreEnregistrement sont à 0 car inconnus à ce stade.
    // Ils seront mis à jour dans miseAJourTournoiMitchell() une fois que
    // l'organisateur aura fini de constituer ses équipes.
    // ─────────────────────────────────────────────────────────────────────
    fun creerTournoiMitchell(
        context: Context,
        nbreDonnesParTable: Int,
        codeArbitre: String = ""
    ): Long {
        // TournoiConfig sera mis à jour dans miseAJourTournoiMitchell()
        TournoiConfig.NBRE_MOUVEMENTS       = 0
        TournoiConfig.NBRE_DONNES_PAR_TABLE = nbreDonnesParTable

        val db = DatabaseHelper.getDatabase(context)
        return try {
            val values = ContentValues().apply {
                put("date", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
                put("ouvert", 0)
                put("mouvement", "")
                put("nbre_enregistrement", 0)
                put("nbre_donne_total", 0)                   // total inconnu à ce stade
                put("nbre_equipe", 0)
                put("id_premiere_donne", 0)
                put("type", "Mitchell")
                put("nbre_donnes_par_table", nbreDonnesParTable)
                put("nbre_mouvements", 0)
                put("nbre_tables", 0)
                put("code_arbitre", codeArbitre)
            }
            val id = db.insert("tournois", null, values)
            Log.i("DatabaseManager", "✅ Tournoi Mitchell créé (provisoire) ID=$id | donnes/table=$nbreDonnesParTable")
            id
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur creerTournoiMitchell", e)
            -1L
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // MITCHELL : mise à jour du tournoi après constitution des équipes.
    // Appelée dans ConstitutionEquipesScreen au "Enregistrer et Démarrer".
    // On connaît maintenant le vrai nombre d'équipes → on calcule tout.
    // ─────────────────────────────────────────────────────────────────────

    fun miseAJourTournoiMitchell(
        context: Context,
        idTournoi: Int,
        nbreEquipes: Int,
        bwsExterneUri: android.net.Uri? = null
    ) {
        val nbreTables          = nbreEquipes / 2
        val nbreMouvements      = if (nbreTables % 2 == 0) nbreTables - 1 else nbreTables
        val nbreDonnesParTable  = TournoiConfig.NBRE_DONNES_PAR_TABLE
        val nbreDonnesTotal     = nbreDonnesParTable * nbreTables
        val nbreEnregistrement  = nbreDonnesTotal * nbreMouvements

        TournoiConfig.NBRE_MOUVEMENTS = nbreMouvements
        TournoiConfig.NBRE_TABLES     = nbreTables

        val db = DatabaseHelper.getDatabase(context)
        try {
            val values = ContentValues().apply {
                put("nbre_equipe",          nbreEquipes)
                put("nbre_enregistrement",  nbreEnregistrement)
                put("nbre_donne_total",      nbreDonnesTotal)
                put("nbre_mouvements",      nbreMouvements)
                put("nbre_donnes_par_table", nbreDonnesParTable)
                put("nbre_tables",          nbreTables)
            }
            db.update("tournois", values, "ID = ?", arrayOf(idTournoi.toString()))
            Log.i("DatabaseManager", "✅ Mitchell tournoiN° $idTournoi : $nbreEquipes équipes, $nbreTables tables," +
                    "$nbreMouvements mouvements, $nbreDonnesParTable donnes par table, " +
                    "$nbreEnregistrement Enregistrements")
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur miseAJourTournoiMitchell", e)
        } finally {
            DatabaseHelper.closeDatabase()
        }

        // 🔨 FFBClubNet/.bws : si l'organisateur a fourni un vrai fichier reçu de
        // FFBClubNet (bwsExterneUri), on l'utilise tel quel — sinon on génère un
        // gabarit de test (BwsTestGenerator, pas la vraie cible finale). Ne doit
        // jamais faire échouer la création du tournoi si ça plante.
        try {
            if (bwsExterneUri != null) {
                val importe = BwsPaths.importerBwsExterne(context, idTournoi, bwsExterneUri)
                if (importe == null) {
                    Log.e("DatabaseManager", "❌ Import .bws externe échoué, repli sur le générateur de test")
                    BwsTestGenerator.genererPourTournoiMitchell(
                        context, idTournoi, nbreTables, nbreMouvements, nbreDonnesParTable
                    )
                } else {
                    // Mains (HandRecord) du vrai fichier -> tables locales "mains"/
                    // "donnes", pour que l'écran de saisie résultat les reconnaisse
                    // comme déjà saisies (voir BwsHandImporter).
                    try {
                        BwsHandImporter.importerMainsDepuisBws(context, idTournoi, importe)
                    } catch (e: Exception) {
                        Log.e("DatabaseManager", "❌ Erreur import mains .bws (non bloquant)", e)
                    }
                }
            } else {
                BwsTestGenerator.genererPourTournoiMitchell(
                    context, idTournoi, nbreTables, nbreMouvements, nbreDonnesParTable
                )
            }
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur génération/import .bws (non bloquant)", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // MITCHELL GUÉRIDON : création du tournoi avec valeurs provisoires.
    // Même principe que Mitchell : nbreEquipes inconnu à ce stade.
    // ─────────────────────────────────────────────────────────────────────
    fun creerTournoiMitchellGueridon(context: Context, nbreDonnesParTable: Int, codeArbitre: String = ""): Long {
        TournoiConfig.NBRE_MOUVEMENTS       = 0
        TournoiConfig.NBRE_DONNES_PAR_TABLE = nbreDonnesParTable
        val db = DatabaseHelper.getDatabase(context)
        return try {
            val values = ContentValues().apply {
                put("date", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
                put("ouvert", 0)
                put("mouvement", "")
                put("nbre_enregistrement", 0)
                put("nbre_donne_total", 0)
                put("nbre_equipe", 0)
                put("id_premiere_donne", 0)
                put("type", "MitchellGueridon")
                put("nbre_donnes_par_table", nbreDonnesParTable)
                put("nbre_mouvements", 0)
                put("nbre_tables", 0)
                put("code_arbitre", codeArbitre)
            }
            val id = db.insert("tournois", null, values)
            Log.i("DatabaseManager", "✅ Tournoi MitchellGuéridon créé (provisoire) ID=$id | donnes/table=$nbreDonnesParTable")
            id
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur creerTournoiMitchellGueridon", e)
            -1L
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // MITCHELL GUÉRIDON : mise à jour après constitution des équipes.
    // Différence clé vs Mitchell : nbreMouvements = nbreTables (pas de skip).
    // ─────────────────────────────────────────────────────────────────────
    fun miseAJourTournoiMitchellGueridon(context: Context, idTournoi: Int, nbreEquipes: Int) {
        val nbreTables         = nbreEquipes / 2
        val nbreMouvements     = nbreTables   // pas de skip en Guéridon
        val nbreDonnesParTable = TournoiConfig.NBRE_DONNES_PAR_TABLE
        val nbreDonnesTotal    = nbreDonnesParTable * nbreTables
        val nbreEnregistrement = nbreDonnesTotal * nbreMouvements

        TournoiConfig.NBRE_MOUVEMENTS = nbreMouvements
        TournoiConfig.NBRE_TABLES     = nbreTables

        val db = DatabaseHelper.getDatabase(context)
        try {
            val values = ContentValues().apply {
                put("nbre_equipe",           nbreEquipes)
                put("nbre_enregistrement",   nbreEnregistrement)
                put("nbre_donne_total",       nbreDonnesTotal)
                put("nbre_mouvements",       nbreMouvements)
                put("nbre_donnes_par_table", nbreDonnesParTable)
                put("nbre_tables",           nbreTables)
            }
            db.update("tournois", values, "ID = ?", arrayOf(idTournoi.toString()))
            Log.i("DatabaseManager", "✅ MitchellGuéridon N°$idTournoi : $nbreEquipes équipes, $nbreTables tables, $nbreMouvements mouvements")
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur miseAJourTournoiMitchellGueridon", e)
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // MITCHELL GUÉRIDON : calcul dynamique du mouvement à la volée.
    //
    // Règle des sets de donnes (guéridon) :
    //   Chaque table a un "offset" initial basé sur sa position physique
    //   dans la disposition T1, TN, T(N-1), ..., [Guéridon], ..., T2.
    //   À chaque ronde, l'offset décroît de 1 (modulo nbreTables).
    //   T1 et TN partagent toujours le même set (offset = 0).
    //
    //   offset(T=1)  = 0
    //   offset(T=N)  = 0      (même série que T1)
    //   offset(T)    = N-T    si T > N/2  (côté "avant guéridon")
    //   offset(T)    = N-T+1  si T <= N/2 et T > 1  (côté "après guéridon")
    //
    //   set = ((offset - mvntNumero + 1) mod N) + 1
    //
    // Convention équipes (identique à Mitchell) :
    //   EO = 1..N  (rotation +1 table/ronde, sans skip)
    //   NS = N+1..2N  (fixes, NS à table T = équipe T+N)
    // ─────────────────────────────────────────────────────────────────────
    // Corrige le 05/08/2026, apres verification contre une source primaire (le guéridon
    // n'est PAS une simple astuce de calcul, c'est une vraie regle FFB — voir
    // https://scjc-bridge.fr/le-mouvement-mitchell et le document d'Olivier Labbe,
    // "Mouvements de tournois de Bridge" : "Si le nombre de tables n est pair,
    // l'Arbitre peut placer un guéridon relais entre les tables n/2 et (n/2)+1 (...)
    // [la derniere table] partage a chaque position les etuis qui arrivent a la table 1."
    //
    // Consequence concrete : le Guéridon EXIGE un nombre de tables PAIR (verifie avec
    // Marco : le premier test, 3 tables, n'etait pas une configuration valide pour ce
    // mouvement). Le guéridon n'est pas entre la derniere table et la table 1 (ancienne
    // hypothese, fausse) mais planté AU MILIEU de la chaine de circulation des donnes.
    //
    // Modele retenu (chaine de N emplacements pour N jeux de donnes, qui "descendent"
    // d'un cran a chaque mouvement) : table 1, table 2, ..., table N/2, [guéridon],
    // table (N/2)+1, ..., table N-1. La derniere table (N) n'a pas d'emplacement propre
    // dans la chaine : elle rejoue a CHAQUE mouvement exactement le meme jeu que la
    // table 1 (simultane, meme mouvement), donc on lui donne la position de la table 1.
    // Verifie a la main, mouvement complet 4 tables/8 equipes : les 4 tables ET les 4
    // equipes EO (qui tournent) voient chacune les 4 jeux, sans doublon ni jeu manque.
    private fun positionChaineGueridon(tableNumero: Int, nbreTables: Int): Int {
        val tablePourChaine = if (tableNumero == nbreTables) 1 else tableNumero
        return if (tablePourChaine <= nbreTables / 2) tablePourChaine else tablePourChaine + 1
    }

    private fun getMouvementMitchellGueridon(
        context: Context,
        idTournoi: Int,
        equipeNumero: Int,
        mvntNumero: Int,
        indexDonneAJouer: Int,
        nbreDonnesParTable: Int,
        nbreEquipes: Int,
        nbreTables: Int,
        tousTermines: Boolean
    ): MouvementResult {
        Log.i("DatabaseManager", "🎯 getMouvementMitchellGuéridon : équipe=$equipeNumero mvnt=$mvntNumero")
        val db = DatabaseHelper.getDatabase(context)

        if (nbreEquipes < 2) return MouvementResult.Erreur("Nombre d'équipes MitchellGuéridon invalide : $nbreEquipes")

        // Équipe NS (equipeNumero > nbreTables) : fixe à sa table, ne dépend jamais
        // du mouvement — contrairement à EO qui tourne (rotation sans skip :
        // tableNumero = (equipe + mvnt - 1) mod N). Même correctif que celui du
        // 25/07/2026 dans getMouvementMitchell, jamais porté ici jusqu'au 05/08/2026
        // (repere sur un test MitchellGuéridon 6 équipes/3 tables : eq1 et eq5
        // censees etre a la meme table au mvnt 2 se calculaient chacune une table
        // differente, et eq6 debordait sur une "table 4" inexistante).
        var tableNumero = if (equipeNumero > nbreTables) {
            equipeNumero - nbreTables
        } else {
            var t = equipeNumero + mvntNumero - 1
            if (t > nbreTables) t -= nbreTables
            t
        }
        Log.i("DatabaseManager", "   → équipe=$equipeNumero mvnt=$mvntNumero table=$tableNumero")

        val equipeNS = tableNumero + nbreTables
        var equipeEO = tableNumero - mvntNumero + 1
        if (equipeEO <= 0) equipeEO += nbreTables
        Log.i("DatabaseManager", "   → equipeNS=$equipeNS | equipeEO=$equipeEO")

        // Set de donnes selon la position dans la chaine de circulation (voir
        // positionChaineGueridon ci-dessus) : chaque mouvement, le jeu "descend" d'un cran.
        val positionChaine = positionChaineGueridon(tableNumero, nbreTables)
        val setDonnes      = ((positionChaine + mvntNumero - 2) % nbreTables) + 1
        val premiereDonne = (setDonnes - 1) * nbreDonnesParTable + 1
        val derniereDonne = setDonnes * nbreDonnesParTable
        Log.i("DatabaseManager", "   → positionChaine=$positionChaine setDonnes=$setDonnes | donnes=$premiereDonne..$derniereDonne")

        data class InfosJoueurs(val j1Nom: String, val j1Prenom: String, val j2Nom: String, val j2Prenom: String, val defaillante: Boolean)
        fun getInfosJoueurs(numEquipe: Int): InfosJoueurs {
            var j1Nom = ""; var j1Prenom = ""; var j2Nom = ""; var j2Prenom = ""; var defaillante = false
            db.rawQuery(
                """SELECT j1.nom, j1.prenom, j2.nom, j2.prenom, e.defaillante
                   FROM equipes e
                   JOIN joueurs j1 ON j1.ID = e.id_joueur1
                   JOIN joueurs j2 ON j2.ID = e.id_joueur2
                   WHERE e.id_tournoi = ? AND e.equipe_numero = ?""",
                arrayOf(idTournoi.toString(), numEquipe.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    j1Nom = c.getString(0); j1Prenom = c.getString(1)
                    j2Nom = c.getString(2); j2Prenom = c.getString(3)
                    defaillante = c.getInt(4) != 0
                }
            }
            return InfosJoueurs(j1Nom, j1Prenom, j2Nom, j2Prenom, defaillante)
        }
        val joueursNS = getInfosJoueurs(equipeNS)
        val joueursEO = getInfosJoueurs(equipeEO)

        // Table relais : NS ou EO nommé "Relais" → aucune donne à saisir
        val estRelais = joueursNS.j1Nom.trim().lowercase() == "relais" ||
                joueursNS.j2Nom.trim().lowercase() == "relais" ||
                joueursEO.j1Nom.trim().lowercase() == "relais" ||
                joueursEO.j2Nom.trim().lowercase() == "relais"

        val listeDonnes = mutableListOf<DonneDetail>()
        if (!estRelais) {
            for (numDonne in premiereDonne..derniereDonne) {
                var donneur = "N"; var vulnerable = "Aucune"
                db.rawQuery(
                    "SELECT donneur, vulnerable FROM donnes_d_v WHERE ID_donnes_d_v = ? LIMIT 1",
                    arrayOf(numDonne.toString())
                ).use { c ->
                    if (c.moveToFirst()) { donneur = c.getString(0); vulnerable = c.getString(1) }
                }
                val mainsMap   = loadMainsForDonne(db, idTournoi, numDonne)
                val mainsListe = mainsMap?.let { map -> listOf("N", "E", "S", "O").map { seat -> map[seat] ?: emptyList() } }
                listeDonnes.add(
                    DonneDetail(
                        numero = numDonne,
                        donneur = donneur,
                        vulnerable = vulnerable,
                        mains = mainsListe
                    )
                )
            }
            // TN sans relais : joue la 2ème moitié des donnes d'abord
            if (tableNumero == nbreTables && nbreEquipes % 2 == 0 && listeDonnes.size >= 2) {
                val mid = listeDonnes.size / 2
                val reordonnees = listeDonnes.subList(mid, listeDonnes.size).toList() +
                                  listeDonnes.subList(0, mid).toList()
                listeDonnes.clear()
                listeDonnes.addAll(reordonnees)
            }
        }

        val mouvement = Mouvement(
            mvntNumero = mvntNumero,
            tableNumero = tableNumero,
            equipeNS = equipeNS,
            joueur1NSNom = joueursNS.j1Nom,
            joueur1NSPrenom = joueursNS.j1Prenom,
            joueur2NSNom = joueursNS.j2Nom,
            joueur2NSPrenom = joueursNS.j2Prenom,
            equipeEO = equipeEO,
            joueur1EONom = joueursEO.j1Nom,
            joueur1EOPrenom = joueursEO.j1Prenom,
            joueur2EONom = joueursEO.j2Nom,
            joueur2EOPrenom = joueursEO.j2Prenom,
            donnes = listeDonnes,
            indexDonneAJouer = indexDonneAJouer,
            equipeNSDefaillante = joueursNS.defaillante,
            equipeEODefaillante = joueursEO.defaillante
        )
        if (estRelais) Log.i("DatabaseManager", "🏖️ MitchellGuéridon table relais → aucune donne")
        else Log.i("DatabaseManager", "✅ MitchellGuéridon → mvnt=$mvntNumero table=$tableNumero NS=$equipeNS EO=$equipeEO set=$setDonnes donnes=$premiereDonne..$derniereDonne")
        return MouvementResult.Complet(mouvement, tousTermines)
    }

    // ─────────────────────────────────────────────────────────────────────
    // MITCHELL : calcul dynamique du mouvement à la volée.
    // Pas de table SQL : on calcule selon les règles de rotation Mitchell.
    //
    // Convention de numérotation des équipes :
    //   Équipes 1..T   → EO, départ à la table correspondant à leur numéro
    //   Équipes T+1..N → NS, fixes à leur table (eq T+1 = table 1, etc.)
    //
    // Formule table au mouvement M :
    //   t = equipeNumero + mvntNumero - 1 + offset
    //   si t > nbreTables → t = t - nbreTables
    //
    // Skip Mitchell (nbreTables pair) :
    //   Entre R(N/2) et R(N/2+1) les EO sautent une table supplémentaire
    //   offset = 1 si mvntNumero > nbreTables/2, sinon 0
    //   nbreMouvements = nbreTables - 1 (pas nbreTables)
    //
    // Équipe NS à la table T : equipeNumero = T + nbreTables
    // Équipe EO à la table T au mouvement M :
    //   equipeEO = tableNumero - mvntNumero + 1 - offset
    //   si equipeEO <= 0 → equipeEO += nbreTables
    //
    // Set de donnes (régulier, sans saut) :
    //   setDonnes = tableNumero + mvntNumero - 1
    //   si setDonnes > nbreTables → setDonnes -= nbreTables
    //   donnes = (setDonnes-1)*nbreDonnesParTable+1 .. setDonnes*nbreDonnesParTable
    // ─────────────────────────────────────────────────────────────────────
    private fun getMouvementMitchell(
        context: Context,
        idTournoi: Int,
        equipeNumero: Int,
        mvntNumero: Int,
        indexDonneAJouer: Int,
        nbreDonnesParTable: Int,
        nbreEquipes: Int,
        nbreTables: Int,
        tousTermines: Boolean
    ): MouvementResult {
        Log.i("DatabaseManager", "🎯 getMouvementMitchell : équipe=$equipeNumero mvnt=$mvntNumero")
        val db = DatabaseHelper.getDatabase(context)

        if (nbreEquipes < 2) return MouvementResult.Erreur("Nombre d'équipes Mitchell invalide : $nbreEquipes")

        // 2️⃣ Skip Mitchell : offset +1 après la moitié des mouvements si nbreTables pair
        // Évite les collisions de sets sans matériel supplémentaire
        val offset = if (nbreTables % 2 == 0 && mvntNumero > nbreTables / 2) 1 else 0
        Log.i("DatabaseManager", "   → nbreTables=$nbreTables offset=$offset")

        // 3️⃣ Table de l'équipe au mouvement M
        // Équipe NS (equipeNumero > nbreTables) : fixe à sa table, ne dépend
        // jamais du mouvement — contrairement à EO qui tourne. Avant ce
        // correctif, la formule de rotation EO était appliquée aussi aux NS,
        // donnant une table fausse (silencieusement, si encore dans la plage
        // 1..nbreTables) ou carrément invalide pour l'équipe NS la plus haute
        // numérotée à partir du mouvement 2 (repéré le 25/07/2026, tournoi
        // 7 tables/14 équipes — équipe 14 envoyée à la "table 8" inexistante).
        var tableNumero = if (equipeNumero > nbreTables) {
            equipeNumero - nbreTables
        } else {
            var t = equipeNumero + mvntNumero - 1 + offset
            if (t > nbreTables) t -= nbreTables
            t
        }
        Log.i("DatabaseManager", "   → équipe=$equipeNumero mvnt=$mvntNumero table=$tableNumero")

        // 4️⃣ Équipe NS à cette table (NS fixe : eq = table + nbreTables)
        val equipeNS = tableNumero + nbreTables

        // 5️⃣ Équipe EO à cette table au mouvement M
        var equipeEO = tableNumero - mvntNumero + 1 - offset
        if (equipeEO <= 0) equipeEO += nbreTables
        Log.i("DatabaseManager", "   → equipeNS=$equipeNS | equipeEO=$equipeEO")

        // 6️⃣ Set de donnes (régulier, sans saut)
        var setDonnes = tableNumero + mvntNumero - 1
        if (setDonnes > nbreTables) setDonnes -= nbreTables
        val premiereDonne = (setDonnes - 1) * nbreDonnesParTable + 1
        val derniereDonne = setDonnes * nbreDonnesParTable
        Log.i("DatabaseManager", "   → setDonnes=$setDonnes | donnes=$premiereDonne..$derniereDonne")

        // 7️⃣ Récupérer les infos joueurs des deux équipes
        data class InfosJoueurs(val j1Nom: String, val j1Prenom: String, val j2Nom: String, val j2Prenom: String, val defaillante: Boolean)
        fun getInfosJoueurs(numEquipe: Int): InfosJoueurs {
            var j1Nom = ""; var j1Prenom = ""; var j2Nom = ""; var j2Prenom = ""; var defaillante = false
            db.rawQuery(
                """SELECT j1.nom, j1.prenom, j2.nom, j2.prenom, e.defaillante
                   FROM equipes e
                   JOIN joueurs j1 ON j1.ID = e.id_joueur1
                   JOIN joueurs j2 ON j2.ID = e.id_joueur2
                   WHERE e.id_tournoi = ? AND e.equipe_numero = ?""",
                arrayOf(idTournoi.toString(), numEquipe.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    j1Nom = c.getString(0); j1Prenom = c.getString(1)
                    j2Nom = c.getString(2); j2Prenom = c.getString(3)
                    defaillante = c.getInt(4) != 0
                }
            }
            return InfosJoueurs(j1Nom, j1Prenom, j2Nom, j2Prenom, defaillante)
        }
        val joueursNS = getInfosJoueurs(equipeNS)
        val joueursEO = getInfosJoueurs(equipeEO)

        // 8️⃣ Construire la liste des donnes avec métadonnées
        val listeDonnes = mutableListOf<DonneDetail>()
        for (numDonne in premiereDonne..derniereDonne) {
            var donneur = "N"; var vulnerable = "Aucune"
            db.rawQuery(
                "SELECT donneur, vulnerable FROM donnes_d_v WHERE ID_donnes_d_v = ? LIMIT 1",
                arrayOf(numDonne.toString())
            ).use { c ->
                if (c.moveToFirst()) { donneur = c.getString(0); vulnerable = c.getString(1) }
            }
            val mainsMap   = loadMainsForDonne(db, idTournoi, numDonne)
            val mainsListe = mainsMap?.let { map -> listOf("N", "E", "S", "O").map { seat -> map[seat] ?: emptyList() } }
            Log.i("DatabaseManager", "🃏 Mitchell donne $numDonne | donneur=$donneur")
            listeDonnes.add(
                DonneDetail(
                    numero = numDonne,
                    donneur = donneur,
                    vulnerable = vulnerable,
                    mains = mainsListe
                )
            )
        }

        // 9️⃣ Construire et retourner le Mouvement
        val mouvement = Mouvement(
            mvntNumero = mvntNumero,
            tableNumero = tableNumero,
            equipeNS = equipeNS,
            joueur1NSNom = joueursNS.j1Nom,
            joueur1NSPrenom = joueursNS.j1Prenom,
            joueur2NSNom = joueursNS.j2Nom,
            joueur2NSPrenom = joueursNS.j2Prenom,
            equipeEO = equipeEO,
            joueur1EONom = joueursEO.j1Nom,
            joueur1EOPrenom = joueursEO.j1Prenom,
            joueur2EONom = joueursEO.j2Nom,
            joueur2EOPrenom = joueursEO.j2Prenom,
            donnes = listeDonnes,
            indexDonneAJouer = indexDonneAJouer,
            equipeNSDefaillante = joueursNS.defaillante,
            equipeEODefaillante = joueursEO.defaillante
        )
        Log.i("DatabaseManager", "✅ Mitchell → mvnt=$mvntNumero table=$tableNumero NS=$equipeNS EO=$equipeEO set=$setDonnes donnes=$premiereDonne..$derniereDonne")
        return MouvementResult.Complet(mouvement, tousTermines)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Retourne pour chaque équipe sa table et orientation au mouvement 1 (Howell uniquement).
    // Appelée depuis ConstitutionEquipesScreen après constitution des équipes.
    // Le tournoi est déjà créé en BD à ce stade : le type est lu depuis la table
    // tournois plutôt que reçu en paramètre, pour éviter toute interpolation SQL
    // depuis une source externe.
    // Retourne Map<equipeNumero, Pair<orientation, table>>
    // ─────────────────────────────────────────────────────────────────────
    fun getPositionsMouvement1(context: Context, idTournoi: Int): Map<Int, Pair<String, Int>> {
        val positions = mutableMapOf<Int, Pair<String, Int>>()
        val db = DatabaseHelper.getDatabase(context)
        try {
            // Lecture du type depuis la BD locale — jamais depuis le caller
            val typeTournoi = db.rawQuery(
                "SELECT type FROM tournois WHERE ID = ?",
                arrayOf(idTournoi.toString())
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: return emptyMap()

            // Le type vient de notre propre BD — pas d'injection possible
            db.rawQuery(
                "SELECT equipe_NS, equipe_EO, table_numero FROM $typeTournoi WHERE mvnt_numero = 1",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val ns    = c.getInt(0)
                    val eo    = c.getInt(1)
                    val table = c.getInt(2)
                    positions[ns] = Pair("NS", table)
                    positions[eo] = Pair("EO", table)
                }
            }
            Log.i("DatabaseManager", "✅ getPositionsMouvement1 : ${positions.size} équipes trouvées")
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur getPositionsMouvement1 : ${e.message}")
        } finally {
            DatabaseHelper.closeDatabase()
        }
        return positions
    }
    fun ouvrirTournoi(context: Context, idTournoi: Int): Boolean {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            val values = ContentValues().apply { put("ouvert", 1) }
            val rows = db.update("tournois", values, "ID = ?", arrayOf(idTournoi.toString()))
            Log.i("DatabaseManager", "🗃️ ouvrirTournoi rows=$rows")
            rows > 0
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur ouvrirTournoi", e)
            false
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    fun fermerTournoiOuvert(context: Context): Boolean {
        return try {
            val db = DatabaseHelper.getDatabase(context)
            val values = ContentValues().apply { put("ouvert", 0) }
            val rows = db.update("tournois", values, "ouvert = 1", null)
            DatabaseHelper.closeDatabase()
            Log.i("DatabaseManager", "✅ Fermeture des tournois ouverts ($rows lignes modifiées)")
            rows > 0
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur fermeture tournoi", e)
            false
        }
    }

    fun getDernierTournoiFerme(context: Context): Int? {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            db.rawQuery("SELECT ID FROM tournois WHERE ouvert = 0 ORDER BY ID DESC LIMIT 1", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else null
            }
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ getDernierTournoiFerme : ${e.message}")
            null
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    fun ajouterNouveauJoueur(context: Context, nom: String, prenom: String): Joueur? {
        val db = DatabaseHelper.getDatabase(context)
        // ID negatif dedie aux joueurs ajoutes localement (ex. touristes) : les ID du site
        // sont toujours positifs (auto-increment MySQL), donc un ID negatif ne peut jamais
        // entrer en collision avec un futur vrai membre - et importerJoueursLocaux() ne
        // touche plus qu'aux ID positifs, ces joueurs ne sont donc plus jamais perdus/ecrases
        // par un import ulterieur.
        val idLocal = db.rawQuery("SELECT MIN(ID) FROM joueurs", null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) minOf(cursor.getLong(0), 0L) - 1 else -1L
        }
        val values = ContentValues().apply {
            put("ID", idLocal)
            put("nom", nom)
            put("prenom", prenom)
        }
        val newId = db.insert("joueurs", null, values)
        return if (newId != -1L) Joueur(idJoueur = idLocal.toInt(), nom = nom, prenom = prenom) else null
    }

    fun importerJoueursLocaux(context: Context, joueurs: List<Joueur>): Int {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            db.beginTransaction()
            // Fusion, pas remplacement total : l'ancienne version videait TOUTE la table
            // avant de la remplir depuis le site, ce qui effacait au passage les joueurs
            // ajoutes localement a la volee (touristes). Desormais on met seulement a
            // jour/ajoute les joueurs du site (ID positifs) - les joueurs locaux (ID
            // negatifs, voir ajouterNouveauJoueur) ne sont jamais references ici, donc
            // jamais touches ni perdus.
            val stmt = db.compileStatement("INSERT OR REPLACE INTO joueurs (ID, nom, prenom) VALUES (?, ?, ?)")
            for (j in joueurs) {
                stmt.bindLong(1, j.idJoueur.toLong())
                stmt.bindString(2, j.nom)
                stmt.bindString(3, j.prenom)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
            Log.i("DatabaseManager", "✅ ${joueurs.size} joueurs importés en local (fusion, joueurs locaux conservés)")
            joueurs.size
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ importerJoueursLocaux : ${e.message}")
            -1
        } finally {
            db.endTransaction()
            DatabaseHelper.closeDatabaseAndWait()
        }
    }

    fun getTousLesJoueurs(context: Context): List<Joueur> {
        val joueurs = mutableListOf<Joueur>()
        val db = DatabaseHelper.getDatabase(context)
        return try {
            db.rawQuery("SELECT ID, nom, prenom FROM joueurs ORDER BY ID ASC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    joueurs.add(
                        Joueur(
                            idJoueur = cursor.getInt(0),
                            nom = cursor.getString(1),
                            prenom = cursor.getString(2)
                        )
                    )
                }
            }
            Log.i("DatabaseManager", "✅ ${joueurs.size} joueurs récupérés")
            joueurs
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur getTousLesJoueurs", e)
            emptyList()
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    fun getEquipesDuTournoi(context: Context, idTournoi: Int): List<Equipe> {
        val equipes = mutableListOf<Equipe>()
        val db = DatabaseHelper.getDatabase(context)
        return try {
            db.rawQuery(
                """SELECT e.equipe_numero, e.id_joueur1, j1.nom AS joueur1_nom, j1.prenom AS joueur1_prenom,
                          e.id_joueur2, j2.nom AS joueur2_nom, j2.prenom AS joueur2_prenom, e.defaillante
                   FROM equipes e
                   JOIN joueurs j1 ON j1.ID = e.id_joueur1
                   JOIN joueurs j2 ON j2.ID = e.id_joueur2
                   WHERE e.id_tournoi = ?""".trimIndent(),
                arrayOf(idTournoi.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val numero = cursor.getInt(cursor.getColumnIndexOrThrow("equipe_numero"))
                    val joueur1 = Joueur(
                        cursor.getInt(cursor.getColumnIndexOrThrow("id_joueur1")),
                        cursor.getString(cursor.getColumnIndexOrThrow("joueur1_nom")),
                        cursor.getString(cursor.getColumnIndexOrThrow("joueur1_prenom"))
                    )
                    val joueur2 = Joueur(
                        cursor.getInt(cursor.getColumnIndexOrThrow("id_joueur2")),
                        cursor.getString(cursor.getColumnIndexOrThrow("joueur2_nom")),
                        cursor.getString(cursor.getColumnIndexOrThrow("joueur2_prenom"))
                    )
                    val defaillante = cursor.getInt(cursor.getColumnIndexOrThrow("defaillante")) != 0
                    equipes.add(Equipe(numero, joueur1, joueur2, idTournoi, defaillante))
                }
            }
            Log.i("DatabaseManager", "✅ ${equipes.size} équipes pour tournoi $idTournoi")
            equipes
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur getEquipesDuTournoi", e)
            emptyList()
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    fun enregistrerEquipes(context: Context, idTournoi: Int, equipes: List<Equipe>): Boolean {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            db.beginTransaction()
            val sql = """INSERT INTO equipes (id_tournoi, equipe_numero, id_joueur1, id_joueur2,
                mvnt_numero, numero_donne, index_donne_jouee, pts, rang) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent()
            val stmt = db.compileStatement(sql)
            equipes.forEach { equipe ->
                stmt.clearBindings()
                stmt.bindLong(1, idTournoi.toLong()); stmt.bindLong(2, equipe.equipeNumero.toLong())
                stmt.bindLong(3, equipe.joueur1.idJoueur.toLong()); stmt.bindLong(4, equipe.joueur2.idJoueur.toLong())
                stmt.bindLong(5, 0); stmt.bindLong(6, 0); stmt.bindLong(7, -1); stmt.bindLong(8, 0); stmt.bindLong(9, 0)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
            true
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur enregistrerEquipes", e)
            false
        } finally {
            try { db.endTransaction() } catch (_: Exception) {}
            DatabaseHelper.closeDatabase()
        }
    }

    fun loadMainsForDonne(db: SQLiteDatabase, idTournoi: Int, numeroDonne: Int): Map<String, List<String>>? {
        try {
            val ids = db.rawQuery(
                """SELECT main_N, main_E, main_S, main_O
               FROM donnes
               WHERE id_tournoi=? AND numero_donne=?
               LIMIT 1""".trimIndent(),
                arrayOf(idTournoi.toString(), numeroDonne.toString())
            ).use { cur ->
                if (!cur.moveToFirst()) return null
                listOf(cur.getLong(0), cur.getLong(1), cur.getLong(2), cur.getLong(3))
            }

            fun loadMain(id: Long): List<String> {
                return db.rawQuery("SELECT * FROM mains WHERE ID=?", arrayOf(id.toString())).use { c ->
                    val cartes = mutableListOf<String>()
                    if (c.moveToFirst()) { for (i in 1..13) cartes.add(c.getString(i)) }
                    cartes
                }
            }
            return mapOf("N" to loadMain(ids[0]), "E" to loadMain(ids[1]), "S" to loadMain(ids[2]), "O" to loadMain(ids[3]))
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ loadMainsForDonne erreur : $e")
            return null
        }
    }

    fun incrementerMouvementEquipe(context: Context, idTournoi: Int, numeroEquipe: Int) {
        val db = DatabaseHelper.getDatabase(context)
        var shouldFinalize = false
        try {
            db.beginTransaction()
            var nbreDonnesParTable = 0
            db.rawQuery("SELECT nbre_donnes_par_table FROM tournois WHERE ID = ?", arrayOf(idTournoi.toString())).use { c ->
                if (c.moveToFirst()) nbreDonnesParTable = c.getInt(0)
            }

            var mvntActuel = 0
            db.rawQuery(
                "SELECT mvnt_numero FROM equipes WHERE id_tournoi = ? AND equipe_numero = ?",
                arrayOf(idTournoi.toString(), numeroEquipe.toString())
            ).use { c -> if (c.moveToFirst()) mvntActuel = c.getInt(0) }

            val nouveauMvnt = if (mvntActuel == 0) 1 else mvntActuel + 1
            val nouvelIndex = nbreDonnesParTable - 1

            db.execSQL(
                "UPDATE equipes SET index_donne_jouee = ?, mvnt_numero = ? WHERE id_tournoi = ? AND equipe_numero = ?",
                arrayOf(nouvelIndex, nouveauMvnt, idTournoi, numeroEquipe)
            )

            val equipeRelais = TournoiConfig.EQUIPE_RELAIS
            if (equipeRelais != null && equipeRelais != numeroEquipe) {
                db.execSQL(
                    "UPDATE equipes SET index_donne_jouee = ?, mvnt_numero = ? WHERE id_tournoi = ? AND equipe_numero = ?",
                    arrayOf(nouvelIndex, nouveauMvnt, idTournoi, equipeRelais)
                )
                Log.i("DatabaseManager", "🏖️ Équipe relais $equipeRelais aussi incrémentée → mvnt=$nouveauMvnt")
            }

            db.execSQL(
                "UPDATE tournois SET nbre_enregistrement = nbre_enregistrement - ? WHERE ID = ? AND ouvert = 1",
                arrayOf(nbreDonnesParTable, idTournoi)
            )
            Log.i("DatabaseManager", "✅ Relais : équipe $numeroEquipe → mvntActuel=$mvntActuel nouveauMvnt=$nouveauMvnt, index=$nouvelIndex, nbre_enreg décrémenté de $nbreDonnesParTable")

            db.rawQuery("SELECT nbre_enregistrement FROM tournois WHERE ID = ?", arrayOf(idTournoi.toString())).use { cursor2 ->
                if (cursor2.moveToFirst()) {
                    val restants = cursor2.getInt(0)
                    Log.i("DatabaseManager", "🏖️ Après relais : nbre_enregistrement=$restants")
                    if (restants == 0) { shouldFinalize = true; Log.i("DatabaseManager", "🏁 nbre_enregistrement=0 → finalisation à déclencher") }
                }
            }

            db.setTransactionSuccessful()
        } finally {
            try { db.endTransaction() } catch (_: Exception) {}
            DatabaseHelper.closeDatabase()
            if (shouldFinalize) {
                Thread.sleep(100)
                Log.i("DatabaseManager", "🏁 Finalisation classement déclenchée depuis incrementerMouvementEquipe")
                finaliserClassementTournoi(context, idTournoi)
            }
        }
    }

    fun toutesEquipesOntTermineMouvement(context: Context, idTournoi: Int, mvntNumero: Int): Boolean {
        val db = DatabaseHelper.getDatabase(context)
        return try { toutesEquipesOntTermineMouvement(db, idTournoi, mvntNumero) }
        finally { DatabaseHelper.closeDatabase() }
    }

    fun toutesEquipesOntTermineMouvement(db: SQLiteDatabase, idTournoi: Int, mvntNumero: Int): Boolean {
        return try {
            var nbreDonnesParTable = 0
            db.rawQuery("SELECT nbre_donnes_par_table FROM tournois WHERE ID = ?", arrayOf(idTournoi.toString())).use { c ->
                if (c.moveToFirst()) nbreDonnesParTable = c.getInt(0)
            }
            var enAttente = 0
            val equipeRelais = TournoiConfig.EQUIPE_RELAIS
            val sql = buildString {
                append("""SELECT COUNT(*) FROM equipes WHERE id_tournoi = ?
                  AND (mvnt_numero < ? OR (mvnt_numero = ? AND index_donne_jouee < ?))""")
                if (equipeRelais != null) append(" AND equipe_numero != $equipeRelais")
            }
            db.rawQuery(sql, arrayOf(idTournoi.toString(), mvntNumero.toString(), mvntNumero.toString(), (nbreDonnesParTable - 1).toString()))
                .use { c -> if (c.moveToFirst()) enAttente = c.getInt(0) }
            Log.i("DatabaseManager", "⏳ Équipes pas encore prêtes pour mvnt $mvntNumero : $enAttente")
            enAttente == 0
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ toutesEquipesOntTermineMouvement", e)
            false
        }
    }



    fun getMouvementPourEquipe(context: Context, idTournoi: Int, equipeNumero: Int): MouvementResult {
        val db = DatabaseHelper.getDatabase(context)
        try {
            Log.i("DatabaseManager", "▶️ getMouvementPourEquipe idTournoi=$idTournoi équipe=$equipeNumero")

            // 1️⃣ Type et config du tournoi (lecture directe des colonnes pré-calculées)
            var typeDeTournoi      = ""
            var nbreDonnesParTable = 0
            var nbreMouvements     = 0
            var nbreEquipes        = 0
            var nbreTables         = 0
            db.rawQuery("SELECT type, nbre_mouvements, nbre_donnes_par_table, nbre_equipe, nbre_tables FROM tournois WHERE ID = ?", arrayOf(idTournoi.toString())).use { c ->
                if (c.moveToFirst()) {
                    typeDeTournoi      = c.getString(0)
                    nbreMouvements     = c.getInt(1)
                    nbreDonnesParTable = c.getInt(2)
                    nbreEquipes        = c.getInt(3)
                    nbreTables         = c.getInt(4)
                } else return MouvementResult.Erreur("Tournoi introuvable")
            }

            // 2️⃣ Progression de l'équipe
            db.rawQuery(
                "SELECT mvnt_numero, index_donne_jouee FROM equipes WHERE id_tournoi=? AND equipe_numero=? LIMIT 1",
                arrayOf(idTournoi.toString(), equipeNumero.toString())
            ).use { c ->
                if (!c.moveToFirst()) return MouvementResult.Erreur("Équipe introuvable")

                var mvntNumero      = c.getInt(0).takeIf { it > 0 } ?: 1
                val indexDonneJouee = c.getInt(1)
                var indexDonneAJouer = indexDonneJouee + 1

                // 3️⃣ L'équipe a fini toutes les donnes de ce mouvement
                val aFiniLeMouvement = (indexDonneAJouer == nbreDonnesParTable)
                var tousTermines = true
                if (aFiniLeMouvement) {
                    val estDernierMouvement = (mvntNumero == nbreMouvements)
                    if (estDernierMouvement) {
                        var nbreEnreg = 0
                        db.rawQuery("SELECT nbre_enregistrement FROM tournois WHERE ID = ?", arrayOf(idTournoi.toString())).use { r ->
                            if (r.moveToFirst()) nbreEnreg = r.getInt(0)
                        }
                        return MouvementResult.ClassementEnAttente(nbreEnreg)
                    }
                    tousTermines = toutesEquipesOntTermineMouvement(db, idTournoi, mvntNumero)
                    mvntNumero++
                    indexDonneAJouer = 0
                }

                // 4️⃣ Branchement selon le type de tournoi
                return when (typeDeTournoi) {
                    "Mitchell" -> {
                        Log.i("DatabaseManager", "🎯 Branchement Mitchell → getMouvementMitchell()")
                        getMouvementMitchell(
                            context            = context,
                            idTournoi          = idTournoi,
                            equipeNumero       = equipeNumero,
                            mvntNumero         = mvntNumero,
                            indexDonneAJouer   = indexDonneAJouer,
                            nbreDonnesParTable = nbreDonnesParTable,
                            nbreEquipes        = nbreEquipes,
                            nbreTables         = nbreTables,
                            tousTermines       = tousTermines
                        )
                    }
                    "MitchellGueridon" -> {
                        Log.i("DatabaseManager", "🎯 Branchement MitchellGuéridon → getMouvementMitchellGueridon()")
                        getMouvementMitchellGueridon(
                            context            = context,
                            idTournoi          = idTournoi,
                            equipeNumero       = equipeNumero,
                            mvntNumero         = mvntNumero,
                            indexDonneAJouer   = indexDonneAJouer,
                            nbreDonnesParTable = nbreDonnesParTable,
                            nbreEquipes        = nbreEquipes,
                            nbreTables         = nbreTables,
                            tousTermines       = tousTermines
                        )
                    }
                    else -> {
                    Log.i("DatabaseManager", "🎯 Branchement Howell → requête SQL sur $typeDeTournoi")
                    val sqlMvnt = """
                    SELECT m.*, jns1.nom AS ns_j1_nom, jns1.prenom AS ns_j1_prenom,
                        jns2.nom AS ns_j2_nom, jns2.prenom AS ns_j2_prenom,
                        jeo1.nom AS eo_j1_nom, jeo1.prenom AS eo_j1_prenom,
                        jeo2.nom AS eo_j2_nom, jeo2.prenom AS eo_j2_prenom,
                        ns.defaillante AS ns_defaillante, eo.defaillante AS eo_defaillante
                    FROM $typeDeTournoi m
                    JOIN equipes ns ON ns.equipe_numero = m.equipe_NS AND ns.id_tournoi = ?
                    JOIN joueurs jns1 ON jns1.ID = ns.id_joueur1
                    JOIN joueurs jns2 ON jns2.ID = ns.id_joueur2
                    JOIN equipes eo ON eo.equipe_numero = m.equipe_EO AND eo.id_tournoi = ?
                    JOIN joueurs jeo1 ON jeo1.ID = eo.id_joueur1
                    JOIN joueurs jeo2 ON jeo2.ID = eo.id_joueur2
                    WHERE (m.equipe_NS = ? OR m.equipe_EO = ?) AND m.mvnt_numero = ?
                    LIMIT 1""".trimIndent()
                    db.rawQuery(sqlMvnt, arrayOf(idTournoi.toString(), idTournoi.toString(), equipeNumero.toString(), equipeNumero.toString(), mvntNumero.toString())).use { cursorMv ->
                        if (!cursorMv.moveToFirst()) {
                            Log.e("DatabaseManager", "❌ Aucun mouvement trouvé en BD")
                            return MouvementResult.Erreur("Aucun mouvement trouvé")
                        }
                        val listeDonnes = mutableListOf<DonneDetail>()
                        for (i in 1..nbreDonnesParTable) {
                            val donneIdInDV = cursorMv.getInt(cursorMv.getColumnIndexOrThrow("numero_d$i"))
                            if (donneIdInDV == 0) continue
                            val metaData = db.rawQuery("SELECT donneur, vulnerable FROM donnes_d_v WHERE ID_donnes_d_v=? LIMIT 1", arrayOf(donneIdInDV.toString())).use { m ->
                                if (m.moveToFirst()) Pair(m.getString(0), m.getString(1)) else Pair("N", "Aucune")
                            }
                            val mainsMap   = loadMainsForDonne(db, idTournoi, donneIdInDV)
                            val mainsListe = mainsMap?.let { map -> listOf("N", "E", "S", "O").map { seat -> map[seat] ?: emptyList() } }
                            listeDonnes.add(
                                DonneDetail(
                                    numero = donneIdInDV,
                                    donneur = metaData.first,
                                    vulnerable = metaData.second,
                                    mains = mainsListe
                                )
                            )
                        }
                        val mvnt = Mouvement(
                            mvntNumero = cursorMv.getInt(cursorMv.getColumnIndexOrThrow("mvnt_numero")),
                            tableNumero = cursorMv.getInt(cursorMv.getColumnIndexOrThrow("table_numero")),
                            equipeNS = cursorMv.getInt(cursorMv.getColumnIndexOrThrow("equipe_NS")),
                            joueur1NSNom = cursorMv.getString(cursorMv.getColumnIndexOrThrow("ns_j1_nom")),
                            joueur1NSPrenom = cursorMv.getString(cursorMv.getColumnIndexOrThrow("ns_j1_prenom")),
                            joueur2NSNom = cursorMv.getString(cursorMv.getColumnIndexOrThrow("ns_j2_nom")),
                            joueur2NSPrenom = cursorMv.getString(cursorMv.getColumnIndexOrThrow("ns_j2_prenom")),
                            equipeEO = cursorMv.getInt(cursorMv.getColumnIndexOrThrow("equipe_EO")),
                            joueur1EONom = cursorMv.getString(cursorMv.getColumnIndexOrThrow("eo_j1_nom")),
                            joueur1EOPrenom = cursorMv.getString(cursorMv.getColumnIndexOrThrow("eo_j1_prenom")),
                            joueur2EONom = cursorMv.getString(cursorMv.getColumnIndexOrThrow("eo_j2_nom")),
                            joueur2EOPrenom = cursorMv.getString(cursorMv.getColumnIndexOrThrow("eo_j2_prenom")),
                            donnes = listeDonnes,
                            indexDonneAJouer = indexDonneAJouer,
                            equipeNSDefaillante = cursorMv.getInt(cursorMv.getColumnIndexOrThrow("ns_defaillante")) != 0,
                            equipeEODefaillante = cursorMv.getInt(cursorMv.getColumnIndexOrThrow("eo_defaillante")) != 0
                        )
                        Log.i("DatabaseManager", "🎯 Mouvement Howell reconstruit → OK")
                        MouvementResult.Complet(mvnt, tousTermines)
                    }
                }
                }  // ferme else -> {
            }      // ferme when (+ use { c -> })
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Exception getMouvementPourEquipe : ${e.message}", e)
            return MouvementResult.Erreur("Erreur BD : ${e.message}")
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    private fun getNbreEnregistrement(context: Context, idTournoi: Int): Int {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            var n = 0
            db.rawQuery("SELECT nbre_enregistrement FROM tournois WHERE ID = ?", arrayOf(idTournoi.toString())).use { c -> if (c.moveToFirst()) n = c.getInt(0) }
            n
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ getNbreEnregistrement", e); -1 }
        finally { DatabaseHelper.closeDatabase() }
    }

    fun getResultatsTournoi(context: Context, idTournoi: Int): List<ClassementManager.ResultatLigne> {
        val resultats = mutableListOf<ClassementManager.ResultatLigne>()
        val db = DatabaseHelper.getDatabase(context)
        try {
            db.rawQuery("SELECT numero_donne, numero_table, equipeNS, equipeEO, pointsNS, pointsEO, type_correction FROM resultats WHERE id_tournoi = ? ORDER BY numero_donne ASC, numero_table ASC", arrayOf(idTournoi.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    resultats.add(
                        ClassementManager.ResultatLigne(
                        numeroDonne = cursor.getInt(0), numeroTable = cursor.getInt(1),
                        equipeNS = cursor.getInt(2), equipeEO = cursor.getInt(3),
                        pointsNS = if (cursor.isNull(4)) null else cursor.getInt(4),
                        pointsEO = if (cursor.isNull(5)) null else cursor.getInt(5),
                        typeCorrection = if (cursor.isNull(6)) null else cursor.getString(6)
                    ))
                }
            }
            Log.i("DatabaseManager", "📊 ${resultats.size} lignes récupérées")
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ Erreur getResultatsTournoi", e) }
        finally { DatabaseHelper.closeDatabase() }
        return resultats
    }

    fun majPointsDonne(context: Context, idTournoi: Int, numeroDonne: Int, numeroTable: Int, ptsNS: Double, ptsEO: Double): Boolean {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            val cv = ContentValues().apply { put("ptsNS", ptsNS); put("ptsEO", ptsEO) }
            db.update("resultats", cv, "id_tournoi=? AND numero_donne=? AND numero_table=?", arrayOf(idTournoi.toString(), numeroDonne.toString(), numeroTable.toString()))
            Log.i("DatabaseManager", "🟢 majPointsDonne OK"); true
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ Erreur majPointsDonne", e); false }
        finally { DatabaseHelper.closeDatabase() }
    }

    fun majClassementEquipe(context: Context, idTournoi: Int, numeroEquipe: Int, totalPts: Double, rang: Int): Boolean {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            val cv = ContentValues().apply { put("pts", totalPts); put("rang", rang) }
            db.update("equipes", cv, "id_tournoi=? AND equipe_numero=?", arrayOf(idTournoi.toString(), numeroEquipe.toString()))
            Log.i("DatabaseManager", "🏆 majClassementEquipe OK"); true
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ Erreur majClassementEquipe", e); false }
        finally { DatabaseHelper.closeDatabase() }
    }

    fun verifierEtatTournoi(context: Context, idTournoi: Int): String {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            var etat = "ERREUR"
            db.rawQuery("SELECT nbre_enregistrement FROM tournois WHERE ID = ?", arrayOf(idTournoi.toString())).use { cursor ->
                if (cursor.moveToFirst()) etat = if (cursor.getInt(0) == 0) "TERMINE" else "NON_TERMINE"
            }
            etat
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ verifierEtatTournoi", e); "ERREUR" }
        finally { DatabaseHelper.closeDatabase() }
    }

    // =========================================================================
    // finaliserClassementTournoi
    // =========================================================================
    // Point d'entrée unique pour le calcul du classement final.
    // Branche sur le bon algorithme selon le type de tournoi :
    //   • "par4equ2t21d" → calcul croisé + barème EBL (ClassementManager4Equ2T21D)
    //   • tous les autres → calcul Simplifiés existant (ClassementManager)
    // =========================================================================
    fun finaliserClassementTournoi(context: Context, idTournoi: Int) {
        Log.i("DatabaseManager", "⚙️ finaliserClassementTournoi → tournoi $idTournoi")

        // Récupérer le type du tournoi pour choisir l'algorithme de calcul
        val typeTournoi = recupererTypeTournoi(context, idTournoi)
        Log.i("DatabaseManager", "📋 Type de tournoi : '$typeTournoi'")

        when (typeTournoi) {
            TYPE_PAR4EQU2T21D -> {
                // ── Nouveau calcul : croisement de tables + barème EBL ────────
                Log.i("DatabaseManager", "🔀 Algorithme : calcul croisé EBL (par4equ2t21d)")
                finaliserClassementPar4Equ2T21D(context, idTournoi)
            }
            else -> {
                // ── Calcul historique : points Simplifiés ─────────────────────
                Log.i("DatabaseManager", "📊 Algorithme : calcul Simplifiés standard")
                val resultatsList = getResultatsTournoi(context, idTournoi)
                val resultatCalcul = ClassementManager.calculerClassementTournoi(resultatsList)
                val classement     = resultatCalcul.classement
                val pointsParDonne = resultatCalcul.pointsParDonne
                val db = DatabaseHelper.getDatabase(context)
                try {
                    // Mise à jour des points Simplifiés par donne dans resultats
                    for (ligne in pointsParDonne) {
                        val cv = ContentValues().apply { put("ptsNS", ligne.ptsNS.toFloat()); put("ptsEO", ligne.ptsEO.toFloat()) }
                        val nbLignes = db.update(
                            "resultats", cv,
                            "id_tournoi=? AND numero_donne=? AND equipeNS=? AND equipeEO=?",
                            arrayOf(idTournoi.toString(), ligne.numeroDonne.toString(), ligne.equipeNS.toString(), ligne.equipeEO.toString())
                        )
                        Log.i("DatabaseManager", "🟢 Donne ${ligne.numeroDonne} NS=${ligne.ptsNS} EO=${ligne.ptsEO} ($nbLignes ligne)")
                    }
                    // Mise à jour du rang et total de points de chaque équipe
                    for (c in classement) {
                        val cv = ContentValues().apply { put("pts", c.totalPts.toFloat()); put("rang", c.rang) }
                        db.update("equipes", cv, "id_tournoi=? AND equipe_numero=?",
                            arrayOf(idTournoi.toString(), c.numeroEquipe.toString()))
                        Log.i("DatabaseManager", "🏆 Équipe ${c.numeroEquipe} → ${"%.1f".format(c.totalPts)} pts (rang ${c.rang})")
                    }
                    Log.i("DatabaseManager", "✅ Classement Simplifiés calculé et stocké.")
                } catch (e: Exception) {
                    Log.e("DatabaseManager", "❌ Erreur finalisation classement Simplifiés", e)
                } finally {
                    DatabaseHelper.closeDatabase()
                }
            }
        }
    }

    // =========================================================================
    // finaliserClassementPar4Equ2T21D  (privée)
    // =========================================================================
    // Calcul spécifique au tournoi "par4equ2t21d" :
    //   1. Lit tous les résultats bruts (pointsNS / pointsEO) depuis la table resultats
    //   2. Délègue le calcul croisé + barème EBL à ClassementManager4Equ2T21D
    //   3. Stocke les points EBL (ptsNS / ptsEO) dans resultats
    //   4. Met à jour pts et rang dans equipes
    //
    // Convention de stockage dans resultats.ptsNS / ptsEO :
    //   • Ligne table 1 : ptsNS = EBL(recapNS),  ptsEO = EBL(recapEO)
    //   • Ligne table 2 : ptsNS = EBL(recapEO),  ptsEO = EBL(recapNS)  ← croisé
    //   Ainsi, chaque ligne reflète les points EBL du camp qui a joué cette table.
    // =========================================================================
    private fun finaliserClassementPar4Equ2T21D(context: Context, idTournoi: Int) {
        Log.i("DatabaseManager", "🔀 finaliserClassementPar4Equ2T21D → tournoi $idTournoi")

        val db = DatabaseHelper.getDatabase(context)
        try {
            // ── Étape 1 : lire tous les résultats bruts ───────────────────────
            val resultats = mutableListOf<ClassementManager4Equ2T21D.ResultatLigne>()
            db.rawQuery(
                """SELECT numero_donne, numero_table, equipeNS, equipeEO, pointsNS, pointsEO
                   FROM resultats
                   WHERE id_tournoi = ?
                   ORDER BY numero_donne ASC, numero_table ASC""",
                arrayOf(idTournoi.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    resultats.add(
                        ClassementManager4Equ2T21D.ResultatLigne(
                            numeroDonne = cursor.getInt(0),
                            numeroTable = cursor.getInt(1),
                            equipeNS    = cursor.getInt(2),
                            equipeEO    = cursor.getInt(3),
                            pointsNS    = if (cursor.isNull(4)) null else cursor.getInt(4),
                            pointsEO    = if (cursor.isNull(5)) null else cursor.getInt(5)
                        )
                    )
                }
            }
            Log.i("DatabaseManager", "📋 ${resultats.size} lignes de résultats lues pour calcul EBL")

            // ── Étape 2 : calcul croisé + barème EBL ─────────────────────────
            val resultatCalcul = ClassementManager4Equ2T21D.calculerClassement(resultats)

            // ── Étape 3 : stocker les points EBL dans resultats ───────────────
            for (dp in resultatCalcul.pointsParDonne) {
                // Table 1 : NS récap → ptsNS, EO récap → ptsEO
                db.execSQL(
                    """UPDATE resultats SET ptsNS = ?, ptsEO = ?
                       WHERE id_tournoi = ? AND numero_donne = ? AND numero_table = 1""",
                    arrayOf(dp.ptsEblNS, dp.ptsEblEO, idTournoi, dp.numeroDonne)
                )
                // Table 2 : inversé car croisement (ce qui était EO récap devient NS local)
                db.execSQL(
                    """UPDATE resultats SET ptsNS = ?, ptsEO = ?
                       WHERE id_tournoi = ? AND numero_donne = ? AND numero_table = 2""",
                    arrayOf(dp.ptsEblEO, dp.ptsEblNS, idTournoi, dp.numeroDonne)
                )
                Log.i("DatabaseManager",
                    "🟢 Donne ${dp.numeroDonne} | " +
                            "RecapNS=${dp.recapNS}→EBL=${dp.ptsEblNS} | " +
                            "RecapEO=${dp.recapEO}→EBL=${dp.ptsEblEO}"
                )
            }

            // ── Étape 4 : mettre à jour pts et rang dans equipes ──────────────
            for (c in resultatCalcul.classement) {
                val cv = ContentValues().apply {
                    put("pts", c.totalPtsEbl)
                    put("rang", c.rang)
                }
                db.update(
                    "equipes", cv,
                    "id_tournoi = ? AND equipe_numero = ?",
                    arrayOf(idTournoi.toString(), c.numeroEquipe.toString())
                )
                Log.i("DatabaseManager",
                    "🏆 Équipe ${c.numeroEquipe} → ${c.totalPtsEbl} pts EBL (rang ${c.rang})")
            }

            Log.i("DatabaseManager", "✅ Classement par4equ2t21d finalisé avec succès.")

        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur finaliserClassementPar4Equ2T21D : ${e.message}", e)
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    // =========================================================================
    // recupererTypeTournoi  (privée)
    // =========================================================================
    // Lit le type du tournoi depuis la table tournois.
    // Retourne une chaîne vide en cas d'erreur (jamais null).
    // =========================================================================
    private fun recupererTypeTournoi(context: Context, idTournoi: Int): String {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            var type = ""
            db.rawQuery(
                "SELECT type FROM tournois WHERE ID = ?",
                arrayOf(idTournoi.toString())
            ).use { c -> if (c.moveToFirst()) type = c.getString(0) ?: "" }
            Log.i("DatabaseManager", "🔍 recupererTypeTournoi → '$type'")
            type
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ recupererTypeTournoi : ${e.message}", e)
            ""
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    fun getClassementStocke(context: Context, idTournoi: Int): List<ClassementManager.ClassementEquipe> {
        val classement = mutableListOf<ClassementManager.ClassementEquipe>()
        val db = DatabaseHelper.getDatabase(context)
        try {
            // N = nombre max de fois qu'une donne a été jouée (référence Neuberg)
            var N = 0
            db.rawQuery(
                "SELECT MAX(cnt) FROM (SELECT COUNT(*) as cnt FROM resultats WHERE id_tournoi=? GROUP BY numero_donne)",
                arrayOf(idTournoi.toString())
            ).use { c -> if (c.moveToFirst()) N = c.getInt(0) }
            val Ntop = maxOf(0, (N - 1) * 2)

            // Nombre de donnes jouées par équipe (NS + EO confondus)
            val dealsParEquipe = mutableMapOf<Int, Int>()
            if (Ntop > 0) {
                db.rawQuery("""
                    SELECT eq, SUM(cnt) FROM (
                        SELECT equipeNS as eq, COUNT(*) as cnt FROM resultats WHERE id_tournoi=? GROUP BY equipeNS
                        UNION ALL
                        SELECT equipeEO as eq, COUNT(*) as cnt FROM resultats WHERE id_tournoi=? GROUP BY equipeEO
                    ) GROUP BY eq
                """.trimIndent(), arrayOf(idTournoi.toString(), idTournoi.toString())
                ).use { c -> while (c.moveToNext()) dealsParEquipe[c.getInt(0)] = c.getInt(1) }
            }

            db.rawQuery("SELECT equipe_numero, pts, rang FROM equipes WHERE id_tournoi = ? AND rang > 0 ORDER BY rang ASC", arrayOf(idTournoi.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    val eq      = cursor.getInt(0)
                    val pts     = cursor.getDouble(1)
                    val rang    = cursor.getInt(2)
                    val nDeals  = dealsParEquipe[eq] ?: 0
                    val pct     = if (nDeals > 0 && Ntop > 0) (pts / (nDeals.toDouble() * Ntop)) * 100.0 else 0.0
                    classement.add(ClassementManager.ClassementEquipe(numeroEquipe = eq, totalPts = pts, rang = rang, scorePct = pct))
                }
            }
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ Erreur getClassementStocke", e) }
        finally { DatabaseHelper.closeDatabase() }
        return classement
    }

    fun getDonneResultatDetails(context: Context, idTournoi: Int): List<DonneResultatDetail> {
        val db = DatabaseHelper.getDatabase(context)
        val list = mutableListOf<DonneResultatDetail>()
        val query = """SELECT r.numero_donne, r.equipeNS, r.equipeEO, r.contrat, r.declarant,
               r.resultat_contrat, r.nombre_pli, r.carteEntame,
               r.pointsNS, r.pointsEO, r.ptsNS, r.ptsEO, dv.vulnerable, r.type_correction
            FROM resultats r
            JOIN donnes_d_v dv ON dv.ID_donnes_d_v = r.numero_donne
            WHERE r.id_tournoi = ? ORDER BY r.numero_donne ASC""".trimIndent()
        return try {
            db.rawQuery(query, arrayOf(idTournoi.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        list.add(
                            DonneResultatDetail(
                                numeroDonne = cursor.getInt(0),
                                equipeNS = cursor.getInt(1),
                                equipeEO = cursor.getInt(2),
                                contrat = cursor.getString(3),
                                declarant = cursor.getString(4),
                                resultatContrat = cursor.getString(5),
                                nombrePli = cursor.getInt(6),
                                carteEntame = cursor.getString(7),
                                pointsNS = cursor.getInt(8),
                                pointsEO = cursor.getInt(9),
                                ptsNS = cursor.getDouble(10),
                                ptsEO = cursor.getDouble(11),
                                vulnerable = cursor.getString(12) ?: "P",
                                typeCorrection = if (cursor.isNull(13)) null else cursor.getString(13)
                            )
                        )
                    } while (cursor.moveToNext())
                }
            }
            list
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur getDonneResultatDetails", e)
            emptyList()
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    fun getFuturMouvement(context: Context, idTournoi: Int, mvntActuel: Int, equipeNS: Int, equipeEO: Int): ChangementDeMouvement? {
        val db = DatabaseHelper.getDatabase(context)
        try {
            val mvntSuivant = mvntActuel + 1
            Log.i("DatabaseManager", "📞 getFuturMouvement - mvntActuel=$mvntActuel, mvntSuivant=$mvntSuivant")

            val typeTournoi = db.rawQuery("SELECT type FROM tournois WHERE ID = ?", arrayOf(idTournoi.toString())).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else { Log.e("DatabaseManager", "❌ Tournoi $idTournoi introuvable"); return null }
            }

            // ── Branchement Mitchell : calcul dynamique sans table SQL ──────────
            if (typeTournoi == "Mitchell") {
                var nbreEquipes = 0
                db.rawQuery("SELECT nbre_equipe FROM tournois WHERE ID = ?", arrayOf(idTournoi.toString())).use { c ->
                    if (c.moveToFirst()) nbreEquipes = c.getInt(0)
                }
                val nbreTables = nbreEquipes / 2

                val entries = mutableListOf<ChangementDeMouvementEntry>()


                for (tableNumero in 1..nbreTables) {
                    // NS à la table T = tableNumero + nbreTables
                    val nsNum = tableNumero + nbreTables

                    // EO au mouvement suivant (avec offset skip Mitchell si tables paires)
                    val offset = if (nbreTables % 2 == 0 && mvntSuivant > nbreTables / 2) 1 else 0
                    var eoNum = tableNumero - mvntSuivant + 1 - offset
                    if (eoNum <= 0) eoNum += nbreTables
                /*// Pour chaque table, on calcule les équipes NS et EO au mouvement suivant
                for (tableNumero in 1..nbreTables) {
                    // NS à la table T = équipe numéro nbreEquipes - T + 1
                    val nsNum = nbreEquipes - tableNumero + 1

                    // EO au mouvement suivant :
                    // tableDepartEO = ((T - mvntSuivant + N*100) % N) + 1
                    // equipeEO = nbreTables + 1 - tableDepartEO
                    val tableDepartEO = ((tableNumero - mvntSuivant + nbreTables * 100) % nbreTables) + 1
                    val eoNum = nbreTables + 1 - tableDepartEO*/

                    // Récupérer les joueurs NS
                    var ns = Equipe(
                        equipeNumero = nsNum,
                        joueur1 = Joueur(0, "", ""),
                        joueur2 = Joueur(0, "", ""),
                        idTournoi = idTournoi
                    )
                    db.rawQuery(
                        """SELECT j1.ID, j1.nom, j1.prenom, j2.ID, j2.nom, j2.prenom, e.defaillante
                       FROM equipes e
                       JOIN joueurs j1 ON j1.ID = e.id_joueur1
                       JOIN joueurs j2 ON j2.ID = e.id_joueur2
                       WHERE e.id_tournoi = ? AND e.equipe_numero = ?""",
                        arrayOf(idTournoi.toString(), nsNum.toString())
                    ).use { c ->
                        if (c.moveToFirst()) ns = Equipe(
                            equipeNumero = nsNum,
                            joueur1 = Joueur(c.getInt(0), c.getString(1), c.getString(2)),
                            joueur2 = Joueur(c.getInt(3), c.getString(4), c.getString(5)),
                            idTournoi = idTournoi,
                            defaillante = c.getInt(6) != 0
                        )
                    }

                    // Récupérer les joueurs EO
                    var eo = Equipe(
                        equipeNumero = eoNum,
                        joueur1 = Joueur(0, "", ""),
                        joueur2 = Joueur(0, "", ""),
                        idTournoi = idTournoi
                    )
                    db.rawQuery(
                        """SELECT j1.ID, j1.nom, j1.prenom, j2.ID, j2.nom, j2.prenom, e.defaillante
                       FROM equipes e
                       JOIN joueurs j1 ON j1.ID = e.id_joueur1
                       JOIN joueurs j2 ON j2.ID = e.id_joueur2
                       WHERE e.id_tournoi = ? AND e.equipe_numero = ?""",
                        arrayOf(idTournoi.toString(), eoNum.toString())
                    ).use { c ->
                        if (c.moveToFirst()) eo = Equipe(
                            equipeNumero = eoNum,
                            joueur1 = Joueur(c.getInt(0), c.getString(1), c.getString(2)),
                            joueur2 = Joueur(c.getInt(3), c.getString(4), c.getString(5)),
                            idTournoi = idTournoi,
                            defaillante = c.getInt(6) != 0
                        )
                    }

                    entries.add(
                        ChangementDeMouvementEntry(
                            tableNumero = tableNumero,
                            equipe = ns,
                            adversaire = eo
                        )
                    )
                    Log.i("DatabaseManager", "   → Table $tableNumero : NS=$nsNum EO=$eoNum")
                }

                Log.i("DatabaseManager", "✅ getFuturMouvement Mitchell - ${entries.size} table(s) pour mouvement $mvntSuivant")
                return if (entries.isNotEmpty()) ChangementDeMouvement(
                    mvntSuivant = mvntSuivant,
                    entries = entries
                )
                else { Log.w("DatabaseManager", "⚠️ Aucune table Mitchell trouvée pour mouvement $mvntSuivant"); null }
            }

            // ── Branchement MitchellGuéridon : même logique sans skip ────────────
            if (typeTournoi == "MitchellGueridon") {
                var nbreEquipes = 0
                db.rawQuery("SELECT nbre_equipe FROM tournois WHERE ID = ?", arrayOf(idTournoi.toString())).use { c ->
                    if (c.moveToFirst()) nbreEquipes = c.getInt(0)
                }
                val nbreTables = nbreEquipes / 2

                val entries = mutableListOf<ChangementDeMouvementEntry>()
                for (tableNumero in 1..nbreTables) {
                    val nsNum = tableNumero + nbreTables
                    // Pas de skip en Guéridon
                    var eoNum = tableNumero - mvntSuivant + 1
                    if (eoNum <= 0) eoNum += nbreTables

                    var ns = Equipe(
                        equipeNumero = nsNum,
                        joueur1 = Joueur(0, "", ""),
                        joueur2 = Joueur(0, "", ""),
                        idTournoi = idTournoi
                    )
                    db.rawQuery(
                        """SELECT j1.ID, j1.nom, j1.prenom, j2.ID, j2.nom, j2.prenom, e.defaillante
                           FROM equipes e
                           JOIN joueurs j1 ON j1.ID = e.id_joueur1
                           JOIN joueurs j2 ON j2.ID = e.id_joueur2
                           WHERE e.id_tournoi = ? AND e.equipe_numero = ?""",
                        arrayOf(idTournoi.toString(), nsNum.toString())
                    ).use { c ->
                        if (c.moveToFirst()) ns = Equipe(
                            equipeNumero = nsNum,
                            joueur1 = Joueur(c.getInt(0), c.getString(1), c.getString(2)),
                            joueur2 = Joueur(c.getInt(3), c.getString(4), c.getString(5)),
                            idTournoi = idTournoi,
                            defaillante = c.getInt(6) != 0
                        )
                    }

                    var eo = Equipe(
                        equipeNumero = eoNum,
                        joueur1 = Joueur(0, "", ""),
                        joueur2 = Joueur(0, "", ""),
                        idTournoi = idTournoi
                    )
                    db.rawQuery(
                        """SELECT j1.ID, j1.nom, j1.prenom, j2.ID, j2.nom, j2.prenom, e.defaillante
                           FROM equipes e
                           JOIN joueurs j1 ON j1.ID = e.id_joueur1
                           JOIN joueurs j2 ON j2.ID = e.id_joueur2
                           WHERE e.id_tournoi = ? AND e.equipe_numero = ?""",
                        arrayOf(idTournoi.toString(), eoNum.toString())
                    ).use { c ->
                        if (c.moveToFirst()) eo = Equipe(
                            equipeNumero = eoNum,
                            joueur1 = Joueur(c.getInt(0), c.getString(1), c.getString(2)),
                            joueur2 = Joueur(c.getInt(3), c.getString(4), c.getString(5)),
                            idTournoi = idTournoi,
                            defaillante = c.getInt(6) != 0
                        )
                    }

                    entries.add(
                        ChangementDeMouvementEntry(
                            tableNumero = tableNumero,
                            equipe = ns,
                            adversaire = eo
                        )
                    )
                    Log.i("DatabaseManager", "   → Table $tableNumero : NS=$nsNum EO=$eoNum")
                }
                Log.i("DatabaseManager", "✅ getFuturMouvement MitchellGuéridon - ${entries.size} table(s) pour mouvement $mvntSuivant")
                return if (entries.isNotEmpty()) ChangementDeMouvement(
                    mvntSuivant = mvntSuivant,
                    entries = entries
                )
                else { Log.w("DatabaseManager", "⚠️ Aucune table MitchellGuéridon trouvée pour mouvement $mvntSuivant"); null }
            }

            // ── Howell : comportement existant inchangé ──────────────────────────
            val sql = """
        SELECT t.table_numero AS table_numero, t.equipe_NS AS equipe_NS, t.equipe_EO AS equipe_EO,
            jNS1.ID AS jns1_id, jNS1.nom AS jns1_nom, jNS1.prenom AS jns1_prenom,
            jNS2.ID AS jns2_id, jNS2.nom AS jns2_nom, jNS2.prenom AS jns2_prenom,
            jEO1.ID AS jeo1_id, jEO1.nom AS jeo1_nom, jEO1.prenom AS jeo1_prenom,
            jEO2.ID AS jeo2_id, jEO2.nom AS jeo2_nom, jEO2.prenom AS jeo2_prenom,
            eNS.defaillante AS ns_defaillante, eEO.defaillante AS eo_defaillante
        FROM $typeTournoi t
        INNER JOIN equipes eNS ON eNS.equipe_numero = t.equipe_NS
        INNER JOIN equipes eEO ON eEO.equipe_numero = t.equipe_EO
        INNER JOIN joueurs jNS1 ON jNS1.ID = eNS.id_joueur1
        INNER JOIN joueurs jNS2 ON jNS2.ID = eNS.id_joueur2
        INNER JOIN joueurs jEO1 ON jEO1.ID = eEO.id_joueur1
        INNER JOIN joueurs jEO2 ON jEO2.ID = eEO.id_joueur2
        WHERE eNS.id_tournoi = ? AND eEO.id_tournoi = ? AND t.mvnt_numero = ?
          AND ((t.equipe_NS IN (?, ?)) OR (t.equipe_EO IN (?, ?)))""".trimIndent()
            val entries = mutableListOf<ChangementDeMouvementEntry>()
            db.rawQuery(sql, arrayOf(idTournoi.toString(), idTournoi.toString(), mvntSuivant.toString(), equipeNS.toString(), equipeEO.toString(), equipeNS.toString(), equipeEO.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    val equipeNSObj = Equipe(
                        equipeNumero = cursor.getInt(cursor.getColumnIndexOrThrow("equipe_NS")),
                        joueur1 = Joueur(
                            cursor.getInt(cursor.getColumnIndexOrThrow("jns1_id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("jns1_nom")),
                            cursor.getString(cursor.getColumnIndexOrThrow("jns1_prenom"))
                        ),
                        joueur2 = Joueur(
                            cursor.getInt(cursor.getColumnIndexOrThrow("jns2_id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("jns2_nom")),
                            cursor.getString(cursor.getColumnIndexOrThrow("jns2_prenom"))
                        ),
                        idTournoi = idTournoi,
                        defaillante = cursor.getInt(cursor.getColumnIndexOrThrow("ns_defaillante")) != 0
                    )
                    val equipeEOObj = Equipe(
                        equipeNumero = cursor.getInt(cursor.getColumnIndexOrThrow("equipe_EO")),
                        joueur1 = Joueur(
                            cursor.getInt(cursor.getColumnIndexOrThrow("jeo1_id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("jeo1_nom")),
                            cursor.getString(cursor.getColumnIndexOrThrow("jeo1_prenom"))
                        ),
                        joueur2 = Joueur(
                            cursor.getInt(cursor.getColumnIndexOrThrow("jeo2_id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("jeo2_nom")),
                            cursor.getString(cursor.getColumnIndexOrThrow("jeo2_prenom"))
                        ),
                        idTournoi = idTournoi,
                        defaillante = cursor.getInt(cursor.getColumnIndexOrThrow("eo_defaillante")) != 0
                    )
                    entries.add(
                        ChangementDeMouvementEntry(
                            tableNumero = cursor.getInt(
                                cursor.getColumnIndexOrThrow(
                                    "table_numero"
                                )
                            ), equipe = equipeNSObj, adversaire = equipeEOObj
                        )
                    )
                }
            }
            Log.i("DatabaseManager", "✅ getFuturMouvement - ${entries.size} table(s) pour mouvement $mvntSuivant")
            return if (entries.isNotEmpty()) ChangementDeMouvement(
                mvntSuivant = mvntSuivant,
                entries = entries
            )
            else { Log.w("DatabaseManager", "⚠️ Aucune table trouvée pour mouvement $mvntSuivant"); null }

        } catch (e: Exception) { Log.e("DatabaseManager", "❌ Erreur getFuturMouvement : ${e.message}", e); return null }
        finally { DatabaseHelper.closeDatabase() }
    }
    fun enregistreDonne(
        context: Context, idTournoi: Int, mvntNumero: Int, equipeNS: Int, equipeEO: Int,
        numeroTable: Int, numeroDonne: Int, indexDonneJouee: Int, contrat: String, declarant: String,
        resultatContrat: String, points: Int, nombrePlis: Int, carteEntame: String,
        historiqueJson: String, mainsJson: String
    ): Int {
        val declarantEstNS = (declarant == "Nord" || declarant == "Sud")
        val pointsNS: Int?; val pointsEO: Int?
        when (resultatContrat) {
            "=", "+" -> { if (declarantEstNS) { pointsNS = points; pointsEO = null } else { pointsNS = null; pointsEO = points } }
            "-"      -> { if (declarantEstNS) { pointsNS = null; pointsEO = points } else { pointsNS = points; pointsEO = null } }
            else     -> { pointsNS = null; pointsEO = null }
        }
        Log.i("DatabaseManager", "💾 Points : déclarant=$declarant résultat=$resultatContrat points=$points → NS=$pointsNS EO=$pointsEO")
        val db = DatabaseHelper.getDatabase(context)
        val gson = Gson()
        var shouldFinalizeClassement = false
        return try {
            db.beginTransaction()

            // Protection anti-double-saisie : NS et EO d'une même table peuvent
            // chacun déclencher enregistreDonne pour LE MÊME plateau (mauvaise
            // manip, double-tap, ou test manuel en solo sur un seul téléphone où
            // on rejoue l'équipe adverse). Sans ce garde-fou, une 2e soumission
            // insère une ligne "resultats" en double ET décrémente
            // nbre_enregistrement une 2e fois, ce qui le fait sauter par-dessus 0
            // et bloque le tournoi indéfiniment sur l'écran d'attente classement
            // (repéré le 25/07/2026, tournoi test Mitchell 3 tables/6 équipes).
            val resultatDejaEnregistre = db.rawQuery(
                "SELECT ID FROM resultats WHERE id_tournoi=? AND mvnt_numero=? AND numero_table=? AND numero_donne=? LIMIT 1",
                arrayOf(idTournoi.toString(), mvntNumero.toString(), numeroTable.toString(), numeroDonne.toString())
            ).use { c -> c.moveToFirst() }

            if (!resultatDejaEnregistre) {
                val values = ContentValues().apply {
                    put("id_tournoi", idTournoi); put("mvnt_numero", mvntNumero); put("equipeNS", equipeNS); put("equipeEO", equipeEO)
                    put("numero_table", numeroTable); put("numero_donne", numeroDonne); put("contrat", contrat); put("declarant", declarant)
                    put("resultat_contrat", resultatContrat); put("points", points); put("nombre_pli", nombrePlis); put("carteEntame", carteEntame)
                    if (pointsNS != null) put("pointsNS", pointsNS) else putNull("pointsNS")
                    if (pointsEO != null) put("pointsEO", pointsEO) else putNull("pointsEO")
                }
                db.insertOrThrow("resultats", null, values)
                Log.i("DatabaseManager", "✅ Résultat inséré donne=$numeroDonne equipeNS=$equipeNS")

                if (historiqueJson.isNotEmpty() && historiqueJson != "[]") {
                    try {
                        val histType = object : TypeToken<List<Map<String, String>>>() {}.type
                        val histList: List<Map<String, String>> = gson.fromJson(historiqueJson, histType)
                        Log.i("DatabaseManager", "💬 ${histList.size} enchères à enregistrer")
                        for ((i, obj) in histList.withIndex()) {
                            val joueur  = URLDecoder.decode(obj["joueur"]  ?: "", StandardCharsets.UTF_8.name())
                            val annonce = URLDecoder.decode(obj["annonce"] ?: "", StandardCharsets.UTF_8.name())
                            db.execSQL("INSERT INTO encheres (id_tournoi, numero_donne, equipeNS, equipeEO, ordre, joueur, annonce) VALUES (?, ?, ?, ?, ?, ?, ?)",
                                arrayOf(idTournoi, numeroDonne, equipeNS, equipeEO, i, joueur, annonce))
                        }
                    } catch (e: Exception) { Log.e("DatabaseManager", "⚠️ Erreur insertion enchères : ${e.message}") }
                }
            } else {
                Log.i("DatabaseManager", "ℹ️ Résultat déjà enregistré pour table=$numeroTable mvnt=$mvntNumero donne=$numeroDonne — soumission ignorée (double saisie)")
            }

            val donneDejaExiste = db.rawQuery("SELECT ID FROM donnes WHERE id_tournoi=? AND numero_donne=? LIMIT 1", arrayOf(idTournoi.toString(), numeroDonne.toString())).use { c -> c.moveToFirst() }
            if (!donneDejaExiste) {
                val mainsType = object : TypeToken<List<List<String>>>() {}.type
                val mains: List<List<String>> = try { gson.fromJson(mainsJson, mainsType) ?: emptyList() } catch (e: Exception) { emptyList() }
                if (mains.size == 4 && mains.all { it.size == 13 }) {
                    fun insertMain(cartes: List<String>): Long {
                        val v = ContentValues(); cartes.forEachIndexed { idx, carte -> v.put("carte${idx + 1}", carte) }; return db.insert("mains", null, v)
                    }
                    val idsMains = mains.map { insertMain(it) }
                    var donneur = ""; var vulnerable = ""
                    db.rawQuery("SELECT donneur, vulnerable FROM donnes_d_v WHERE ID_donnes_d_v=? LIMIT 1", arrayOf(numeroDonne.toString())).use { cur ->
                        if (cur.moveToFirst()) { donneur = cur.getString(0); vulnerable = cur.getString(1) }
                    }
                    db.insert("donnes", null, ContentValues().apply {
                        put("id_tournoi", idTournoi); put("numero_donne", numeroDonne); put("donneur", donneur); put("vulnerable", vulnerable)
                        put("main_N", idsMains.getOrNull(0)); put("main_E", idsMains.getOrNull(1)); put("main_S", idsMains.getOrNull(2)); put("main_O", idsMains.getOrNull(3))
                    })
                    Log.i("DatabaseManager", "✅ Mains insérées donne=$numeroDonne")
                }
            }

            val sqlUpdate = "UPDATE equipes SET mvnt_numero = ?, index_donne_jouee = ?, numero_donne = ? WHERE id_tournoi = ? AND equipe_numero = ?"
            db.execSQL(sqlUpdate, arrayOf(mvntNumero, indexDonneJouee, numeroDonne, idTournoi, equipeNS))
            db.execSQL(sqlUpdate, arrayOf(mvntNumero, indexDonneJouee, numeroDonne, idTournoi, equipeEO))

            if (!resultatDejaEnregistre) {
                db.execSQL("UPDATE tournois SET nbre_enregistrement = nbre_enregistrement - 1 WHERE ID = ?", arrayOf(idTournoi))
            }
            var nbreRestants = -1
            db.rawQuery("SELECT nbre_enregistrement FROM tournois WHERE ID = ?", arrayOf(idTournoi.toString())).use { c -> if (c.moveToFirst()) nbreRestants = c.getInt(0) }
            Log.i("DatabaseManager", "💾 nbre_enregistrement restants = $nbreRestants")
            if (nbreRestants == 0) { Log.i("DatabaseManager", "🏁 TOURNOI $idTournoi TERMINÉ"); shouldFinalizeClassement = true }

            db.setTransactionSuccessful()
            val indexDonneAJouer = indexDonneJouee + 1
            Log.i("DatabaseManager", "✅ enregistreDonne OK → indexDonneAJouer=$indexDonneAJouer")

            // 🔨 Prototype FFBClubNet/.bws (Étape B) : écrit le score converti
            // dans le .bws du tournoi (généré par BwsTestGenerator, Étape A) —
            // ici pour que ça marche quel que soit le chemin d'appel (HTTP
            // ClientServeurHTTP OU appel local direct ClientActivity/useLocal).
            // Non bloquant, ne doit jamais casser l'enregistrement du résultat.
            // Ignoré sur double saisie (resultatDejaEnregistre) pour ne pas
            // écraser la vraie valeur déjà écrite par une éventuelle 2e soumission erronée.
            if (!resultatDejaEnregistre) {
                try {
                    val fichierBws = BwsPaths.fichier(context, idTournoi)
                    if (fichierBws.exists()) {
                        val resultat = BridgemateConverter.convertir(
                            contrat = contrat,
                            declarant = declarant,
                            signe = resultatContrat,
                            plis = nombrePlis,
                            carteEntame = carteEntame,
                            enErreurArbitre = false,
                            equipeNS = equipeNS,
                            equipeEO = equipeEO
                        )
                        BwsWriter.ecrireScore(
                            fichierBws,
                            section = 1,
                            table = numeroTable,
                            round = mvntNumero,
                            board = numeroDonne,
                            // FFBClubNet numérote PairNS = numéro de table (NS fixe),
                            // alors que V3 numérote equipeNS = table + nbreTables en
                            // interne (pour distinguer équipe NS/EO dans toute l'appli,
                            // voir getMouvementMitchell). Il faut donc transposer ici,
                            // uniquement au moment de l'écriture vers le .bws.
                            pairNS = numeroTable,
                            pairEW = equipeEO,
                            resultat = resultat
                        )
                    }
                } catch (e: Exception) {
                    Log.e("DatabaseManager", "❌ Erreur écriture .bws (non bloquant)", e)
                }
            }

            indexDonneAJouer
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ enregistreDonne erreur : ${e.message}", e); -1 }
        finally {
            db.endTransaction(); DatabaseHelper.closeDatabaseAndWait()
            if (shouldFinalizeClassement) {
                Thread.sleep(100)
                Log.i("DatabaseManager", "🔄 Finalisation classement")
                finaliserClassementTournoi(context, idTournoi)
            }
        }
    }

    fun sauvegarderParDonne(context: Context, idTournoi: Int, numeroDonne: Int, parTexte: String) {
        val db = DatabaseHelper.getDatabase(context)
        try {
            db.execSQL(
                "UPDATE donnes SET par_texte = ? WHERE id_tournoi = ? AND numero_donne = ?",
                arrayOf(parTexte, idTournoi.toString(), numeroDonne.toString())
            )
            Log.i("DatabaseManager", "Par sauvegardé donne=$numeroDonne : $parTexte")
        } catch (e: Exception) {
            Log.e("DatabaseManager", "sauvegarderParDonne exception : ${e.message}", e)
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    fun getDonneComplete(context: Context, idTournoi: Int, numeroDonne: Int, equipeNS: Int): DonneComplete? {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            var contrat = ""; var declarant = ""; var vulnerable = "P"; var donneur = "N"; var trouve = false
            db.rawQuery("""SELECT r.contrat, r.declarant, dv.vulnerable, dv.donneur
                   FROM resultats r JOIN donnes_d_v dv ON dv.ID_donnes_d_v = r.numero_donne
                   WHERE r.id_tournoi = ? AND r.numero_donne = ? AND r.equipeNS = ? LIMIT 1""",
                arrayOf(idTournoi.toString(), numeroDonne.toString(), equipeNS.toString())).use { c ->
                if (c.moveToFirst()) { contrat = c.getString(0) ?: ""; declarant = c.getString(1) ?: ""; vulnerable = c.getString(2) ?: "P"; donneur = c.getString(3) ?: "N"; trouve = true }
            }
            if (!trouve) {
                db.rawQuery("""SELECT r.contrat, r.declarant, dv.vulnerable, dv.donneur
                   FROM resultats r JOIN donnes_d_v dv ON dv.ID_donnes_d_v = r.numero_donne
                   WHERE r.id_tournoi = ? AND r.numero_donne = ? LIMIT 1""",
                    arrayOf(idTournoi.toString(), numeroDonne.toString())).use { c ->
                    if (c.moveToFirst()) { contrat = c.getString(0) ?: ""; declarant = c.getString(1) ?: ""; vulnerable = c.getString(2) ?: "P"; donneur = c.getString(3) ?: "N"; trouve = true }
                    else { Log.e("DatabaseManager", "❌ getDonneComplete - aucun résultat pour donne=$numeroDonne equipeNS=$equipeNS"); return null }
                }
            }
            val mains = mutableMapOf<String, List<Carte>>()
            db.rawQuery("""SELECT mN.carte1,mN.carte2,mN.carte3,mN.carte4,mN.carte5,mN.carte6,mN.carte7,mN.carte8,mN.carte9,mN.carte10,mN.carte11,mN.carte12,mN.carte13,
                          mS.carte1,mS.carte2,mS.carte3,mS.carte4,mS.carte5,mS.carte6,mS.carte7,mS.carte8,mS.carte9,mS.carte10,mS.carte11,mS.carte12,mS.carte13,
                          mE.carte1,mE.carte2,mE.carte3,mE.carte4,mE.carte5,mE.carte6,mE.carte7,mE.carte8,mE.carte9,mE.carte10,mE.carte11,mE.carte12,mE.carte13,
                          mO.carte1,mO.carte2,mO.carte3,mO.carte4,mO.carte5,mO.carte6,mO.carte7,mO.carte8,mO.carte9,mO.carte10,mO.carte11,mO.carte12,mO.carte13
                   FROM donnes d JOIN mains mN ON d.main_N = mN.ID JOIN mains mS ON d.main_S = mS.ID
                   JOIN mains mE ON d.main_E = mE.ID JOIN mains mO ON d.main_O = mO.ID
                   WHERE d.id_tournoi = ? AND d.numero_donne = ? LIMIT 1""",
                arrayOf(idTournoi.toString(), numeroDonne.toString())).use { c ->
                if (c.moveToFirst()) {
                    mains["N"] = (0..12).map { Carte.fromCode(c.getString(it)) }
                    mains["S"] = (13..25).map { Carte.fromCode(c.getString(it)) }
                    mains["E"] = (26..38).map { Carte.fromCode(c.getString(it)) }
                    mains["O"] = (39..51).map { Carte.fromCode(c.getString(it)) }
                }
            }
            val encheres = mutableListOf<AnnonceJoueur>()
            db.rawQuery("SELECT joueur, annonce FROM encheres WHERE id_tournoi = ? AND numero_donne = ? AND equipeNS = ? ORDER BY ordre ASC",
                arrayOf(idTournoi.toString(), numeroDonne.toString(), equipeNS.toString())).use { c -> while (c.moveToNext()) encheres.add(
                AnnonceJoueur(c.getString(0), c.getString(1))
            ) }
            if (encheres.isEmpty()) {
                db.rawQuery("SELECT joueur, annonce FROM encheres WHERE id_tournoi = ? AND numero_donne = ? ORDER BY ordre ASC",
                    arrayOf(idTournoi.toString(), numeroDonne.toString())).use { c -> while (c.moveToNext()) encheres.add(
                    AnnonceJoueur(c.getString(0), c.getString(1))
                ) }
            }
            DonneComplete(mains, encheres, vulnerable, donneur, contrat, declarant)
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ getDonneComplete exception : ${e.message}", e); null }
        finally { DatabaseHelper.closeDatabase() }
    }

    fun getEquipesAyantJoueDonne(context: Context, idTournoi: Int, numeroDonne: Int): List<EquipeDonneInfo> {
        val db = DatabaseHelper.getDatabase(context)
        val liste = mutableListOf<EquipeDonneInfo>()
        return try {
            db.rawQuery("SELECT equipeNS, equipeEO, contrat, declarant FROM resultats WHERE id_tournoi = ? AND numero_donne = ? ORDER BY equipeNS ASC",
                arrayOf(idTournoi.toString(), numeroDonne.toString())).use { cursor ->
                while (cursor.moveToNext()) { liste.add(
                    EquipeDonneInfo(
                        equipeNS = cursor.getInt(0),
                        equipeEO = cursor.getInt(1),
                        contrat = cursor.getString(2) ?: "",
                        declarant = cursor.getString(3) ?: ""
                    )
                ) }
            }
            Log.i("DatabaseManager", "📊 getEquipesAyantJoueDonne → ${liste.size} équipes pour donne $numeroDonne")
            liste
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ Erreur getEquipesAyantJoueDonne", e)
            emptyList()
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    fun enregistrerMainsRelais(context: Context, idTournoi: Int, numeroDonne: Int, mains: List<List<String>>): Boolean {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            val donneExiste = db.rawQuery("SELECT ID FROM donnes WHERE id_tournoi = ? AND numero_donne = ? LIMIT 1", arrayOf(idTournoi.toString(), numeroDonne.toString())).use { it.moveToFirst() }
            if (donneExiste) { Log.i("DatabaseManager", "🔁 Mains relais donne $numeroDonne déjà enregistrées"); return true }
            if (mains.size != 4 || !mains.all { it.size == 13 }) { Log.w("DatabaseManager", "⚠️ Mains relais incomplètes donne $numeroDonne"); return false }
            db.beginTransaction()
            fun insertMain(cartes: List<String>): Long { val v = ContentValues(); cartes.forEachIndexed { i, c -> v.put("carte${i+1}", c) }; return db.insert("mains", null, v) }
            val idsMains = mains.map { insertMain(it) }
            var donneur = "N"; var vulnerable = "P"
            db.rawQuery("SELECT donneur, vulnerable FROM donnes_d_v WHERE ID_donnes_d_v = ? LIMIT 1", arrayOf(numeroDonne.toString())).use { cur ->
                if (cur.moveToFirst()) { donneur = cur.getString(0); vulnerable = cur.getString(1) }
            }
            db.insert("donnes", null, ContentValues().apply {
                put("id_tournoi", idTournoi); put("numero_donne", numeroDonne); put("donneur", donneur); put("vulnerable", vulnerable)
                put("main_N", idsMains.getOrNull(0)); put("main_E", idsMains.getOrNull(1)); put("main_S", idsMains.getOrNull(2)); put("main_O", idsMains.getOrNull(3))
            })
            Log.i("DatabaseManager", "✅ Mains relais donne $numeroDonne insérées")
            db.setTransactionSuccessful(); true
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ enregistrerMainsRelais donne $numeroDonne : ${e.message}", e); false }
        finally { try { db.endTransaction() } catch (_: Exception) {}; DatabaseHelper.closeDatabase() }
    }

    fun getMainsRelais(context: Context, idTournoi: Int, numeroDonne: Int): List<List<String>>? {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            db.rawQuery("""
                SELECT mN.carte1,mN.carte2,mN.carte3,mN.carte4,mN.carte5,mN.carte6,mN.carte7,mN.carte8,mN.carte9,mN.carte10,mN.carte11,mN.carte12,mN.carte13,
                       mE.carte1,mE.carte2,mE.carte3,mE.carte4,mE.carte5,mE.carte6,mE.carte7,mE.carte8,mE.carte9,mE.carte10,mE.carte11,mE.carte12,mE.carte13,
                       mS.carte1,mS.carte2,mS.carte3,mS.carte4,mS.carte5,mS.carte6,mS.carte7,mS.carte8,mS.carte9,mS.carte10,mS.carte11,mS.carte12,mS.carte13,
                       mO.carte1,mO.carte2,mO.carte3,mO.carte4,mO.carte5,mO.carte6,mO.carte7,mO.carte8,mO.carte9,mO.carte10,mO.carte11,mO.carte12,mO.carte13
                FROM donnes d JOIN mains mN ON d.main_N = mN.ID JOIN mains mE ON d.main_E = mE.ID
                JOIN mains mS ON d.main_S = mS.ID JOIN mains mO ON d.main_O = mO.ID
                WHERE d.id_tournoi = ? AND d.numero_donne = ? LIMIT 1""".trimIndent(),
                arrayOf(idTournoi.toString(), numeroDonne.toString())).use { cursor ->
                if (!cursor.moveToFirst()) {
                    Log.i("DatabaseManager", "ℹ️ getMainsRelais - pas de mains pour donne $numeroDonne")
                    return@use null
                }
                val mainN = (0..12).map { cursor.getString(it) }
                val mainE = (13..25).map { cursor.getString(it) }
                val mainS = (26..38).map { cursor.getString(it) }
                val mainO = (39..51).map { cursor.getString(it) }
                Log.i("DatabaseManager", "✅ getMainsRelais - mains trouvées pour donne $numeroDonne")
                listOf(mainN, mainE, mainS, mainO)
            }
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ getMainsRelais : ${e.message}", e); null }
        finally { DatabaseHelper.closeDatabase() }
    }

    fun getTournoiOuvertEtTermine(context: Context): Int? {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            var id: Int? = null
            db.rawQuery("SELECT ID FROM tournois WHERE ouvert = 1 AND nbre_enregistrement = 0 LIMIT 1", null).use { c ->
                if (c.moveToFirst()) id = c.getInt(0)
            }
            Log.i("DatabaseManager", "getTournoiOuvertEtTermine → $id")
            id
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ getTournoiOuvertEtTermine : ${e.message}")
            null
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    fun collecterTournoiPourExport(context: Context, idTournoi: Int): String? {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            val gson = Gson()
            val result = mutableMapOf<String, Any>()

            db.rawQuery("SELECT date, type, nbre_equipe, nbre_donne_total FROM tournois WHERE ID = ?",
                arrayOf(idTournoi.toString())).use { c ->
                if (!c.moveToFirst()) return null
                result["date"]             = c.getString(0)
                result["type"]             = c.getString(1)
                result["nbre_equipe"]      = c.getInt(2)
                result["nbre_donne_total"] = c.getInt(3)
            }

            val joueurs = mutableListOf<Map<String, Any>>()
            db.rawQuery("""SELECT DISTINCT j.ID, j.nom, j.prenom FROM joueurs j
                JOIN equipes e ON (e.id_joueur1 = j.ID OR e.id_joueur2 = j.ID)
                WHERE e.id_tournoi = ?""", arrayOf(idTournoi.toString())).use { c ->
                while (c.moveToNext()) joueurs.add(mapOf("id" to c.getInt(0), "nom" to c.getString(1), "prenom" to c.getString(2)))
            }
            result["joueurs"] = joueurs

            val equipes = mutableListOf<Map<String, Any>>()
            db.rawQuery("SELECT equipe_numero, id_joueur1, id_joueur2, pts, rang FROM equipes WHERE id_tournoi = ?",
                arrayOf(idTournoi.toString())).use { c ->
                while (c.moveToNext()) equipes.add(mapOf("equipe_numero" to c.getInt(0), "id_joueur1" to c.getInt(1), "id_joueur2" to c.getInt(2), "pts" to c.getDouble(3), "rang" to c.getInt(4)))
            }
            result["equipes"] = equipes

            val donnes = mutableListOf<Map<String, Any>>()
            db.rawQuery("SELECT d.numero_donne, d.donneur, d.vulnerable, d.main_N, d.main_E, d.main_S, d.main_O, d.par_texte FROM donnes d WHERE d.id_tournoi = ?",
                arrayOf(idTournoi.toString())).use { c ->
                while (c.moveToNext()) {
                    val numeroDonne = c.getInt(0)
                    val parTexte = c.getString(7) ?: ""
                    val mains = mutableMapOf<String, List<String>>()
                    listOf("N" to c.getInt(3), "E" to c.getInt(4), "S" to c.getInt(5), "O" to c.getInt(6)).forEach { (pos, idMain) ->
                        val cartes = mutableListOf<String>()
                        db.rawQuery("SELECT * FROM mains WHERE ID = ?", arrayOf(idMain.toString())).use { cm ->
                            if (cm.moveToFirst()) for (i in 1..13) cartes.add(cm.getString(i) ?: "")
                        }
                        mains[pos] = cartes
                    }
                    val donne = mutableMapOf<String, Any>("numero_donne" to numeroDonne, "donneur" to c.getString(1), "vulnerable" to c.getString(2), "mains" to mains)
                    if (parTexte.isNotEmpty()) donne["par_texte"] = parTexte
                    donnes.add(donne)
                }
            }
            result["donnes"] = donnes

            val resultats = mutableListOf<Map<String, Any?>>()
            db.rawQuery("""SELECT mvnt_numero, equipeNS, equipeEO, numero_table, numero_donne,
                   contrat, declarant, resultat_contrat, points, pointsNS, pointsEO,
                   nombre_pli, carteEntame, ptsNS, ptsEO FROM resultats WHERE id_tournoi = ?""",
                arrayOf(idTournoi.toString())).use { c ->
                while (c.moveToNext()) {
                    resultats.add(mapOf(
                        "mvnt_numero" to c.getInt(0), "equipeNS" to c.getInt(1), "equipeEO" to c.getInt(2),
                        "numero_table" to c.getInt(3), "numero_donne" to c.getInt(4),
                        "contrat" to (c.getString(5) ?: ""), "declarant" to (c.getString(6) ?: ""),
                        "resultat_contrat" to (c.getString(7) ?: ""), "points" to c.getInt(8),
                        "pointsNS" to if (c.isNull(9)) null else c.getInt(9),
                        "pointsEO" to if (c.isNull(10)) null else c.getInt(10),
                        "nombre_pli" to c.getInt(11), "carteEntame" to (c.getString(12) ?: ""),
                        "ptsNS" to c.getDouble(13), "ptsEO" to c.getDouble(14)
                    ))
                }
            }
            result["resultats"] = resultats

            val encheres = mutableListOf<Map<String, Any>>()
            db.rawQuery("SELECT numero_donne, equipeNS, equipeEO, ordre, joueur, annonce FROM encheres WHERE id_tournoi = ? ORDER BY numero_donne, ordre",
                arrayOf(idTournoi.toString())).use { c ->
                while (c.moveToNext()) encheres.add(mapOf("numero_donne" to c.getInt(0), "equipeNS" to c.getInt(1), "equipeEO" to c.getInt(2), "ordre" to c.getInt(3), "joueur" to (c.getString(4) ?: ""), "annonce" to (c.getString(5) ?: "")))
            }
            result["encheres"] = encheres

            Log.i("DatabaseManager", "✅ collecterTournoiPourExport : ${joueurs.size} joueurs, ${equipes.size} équipes, ${donnes.size} donnes, ${resultats.size} résultats, ${encheres.size} enchères")
            gson.toJson(result)
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ collecterTournoiPourExport erreur : ${e.message}", e)
            null
        } finally {
            DatabaseHelper.closeDatabaseAndWait()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Journal des erreurs joueurs
    // ─────────────────────────────────────────────────────────────────────────

    fun enregistrerErreurLog(context: Context, idTournoi: Int, equipeNumero: Int, etape: String, message: String) {
        try {
            val db = DatabaseHelper.getDatabase(context)
            val values = ContentValues().apply {
                put("timestamp", SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date()))
                put("id_tournoi", idTournoi)
                put("equipe_numero", equipeNumero)
                put("etape", etape)
                put("message", message)
            }
            db.insertOrThrow("erreurs_log", null, values)
            Log.i("DatabaseManager", "📋 Erreur loggée : [$etape] $message")
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ enregistrerErreurLog : ${e.message}")
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    fun getErreursLog(context: Context): List<ErreurLogItem> {
        val liste = mutableListOf<ErreurLogItem>()
        return try {
            val db = DatabaseHelper.getDatabase(context)
            db.rawQuery(
                "SELECT id, timestamp, id_tournoi, equipe_numero, etape, message FROM erreurs_log ORDER BY id DESC LIMIT 100",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    liste.add(
                        ErreurLogItem(
                            id = c.getInt(0),
                            timestamp = c.getString(1) ?: "",
                            idTournoi = c.getInt(2),
                            equipeNumero = c.getInt(3),
                            etape = c.getString(4) ?: "",
                            message = c.getString(5) ?: ""
                        )
                    )
                }
            }
            liste
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ getErreursLog : ${e.message}")
            emptyList()
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    fun effacerErreursLog(context: Context) {
        try {
            DatabaseHelper.getDatabase(context).execSQL("DELETE FROM erreurs_log")
            Log.i("DatabaseManager", "🗑️ Journal des erreurs effacé")
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ effacerErreursLog : ${e.message}")
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    fun corrigerResultat(
        context: Context, idTournoi: Int, numeroDonne: Int, equipeNS: Int,
        contrat: String, declarant: String, resultatContrat: String,
        points: Int, nombrePli: Int, carteEntame: String
    ): Boolean {
        val declarantEstNS = declarant == "Nord" || declarant == "Sud"
        val pointsNS: Int? = when (resultatContrat) {
            "=", "+" -> if (declarantEstNS) points else null
            "-"      -> if (declarantEstNS) null else points
            else     -> null
        }
        val pointsEO: Int? = when (resultatContrat) {
            "=", "+" -> if (!declarantEstNS) points else null
            "-"      -> if (declarantEstNS) points else null
            else     -> null
        }
        val db = DatabaseHelper.getDatabase(context)
        return try {
            db.beginTransaction()
            val values = ContentValues().apply {
                put("contrat", contrat); put("declarant", declarant)
                put("resultat_contrat", resultatContrat); put("points", points)
                put("nombre_pli", nombrePli); put("carteEntame", carteEntame)
                if (pointsNS != null) put("pointsNS", pointsNS) else putNull("pointsNS")
                if (pointsEO != null) put("pointsEO", pointsEO) else putNull("pointsEO")
            }
            val rows = db.update(
                "resultats", values,
                "id_tournoi=? AND numero_donne=? AND equipeNS=?",
                arrayOf(idTournoi.toString(), numeroDonne.toString(), equipeNS.toString())
            )
            db.setTransactionSuccessful()
            Log.i("DatabaseManager", "✅ corrigerResultat OK — $rows ligne(s) modifiée(s)")
            rows > 0
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ corrigerResultat : ${e.message}", e)
            false
        } finally {
            db.endTransaction()
            DatabaseHelper.closeDatabaseAndWait()
            finaliserClassementTournoi(context, idTournoi)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Erreur sur une donne (moyenne individuelle) — Organisateur uniquement
    // Marque/démarque une ligne "resultats" comme injouable/erronée, sans faute
    // d'une équipe. ClassementManager recalcule alors le score de cette donne
    // pour NS et EO à partir de la moyenne individuelle de chaque équipe.
    // ─────────────────────────────────────────────────────────────────────────
    fun marquerErreurDonne(
        context: Context, idTournoi: Int, numeroDonne: Int, equipeNS: Int, equipeEO: Int,
        enErreur: Boolean
    ): Boolean {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            db.beginTransaction()
            val values = ContentValues().apply {
                if (enErreur) put("type_correction", ClassementManager.TYPE_ERREUR_MOYENNE_IND)
                else putNull("type_correction")
            }
            val rows = db.update(
                "resultats", values,
                "id_tournoi=? AND numero_donne=? AND equipeNS=?",
                arrayOf(idTournoi.toString(), numeroDonne.toString(), equipeNS.toString())
            )
            db.setTransactionSuccessful()
            Log.i("DatabaseManager", "✅ marquerErreurDonne OK — $rows ligne(s) modifiée(s) (enErreur=$enErreur)")
            rows > 0
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ marquerErreurDonne : ${e.message}", e)
            false
        } finally {
            db.endTransaction()
            DatabaseHelper.closeDatabaseAndWait()
            enregistrerErreurLog(
                context, idTournoi, equipeNS, "CorrectionOrganisateur",
                if (enErreur)
                    "Donne $numeroDonne marquée en erreur (moyenne individuelle) — Éq.$equipeNS vs Éq.$equipeEO"
                else
                    "Donne $numeroDonne — erreur annulée, retour au calcul normal — Éq.$equipeNS vs Éq.$equipeEO"
            )
            finaliserClassementTournoi(context, idTournoi)
        }
    }

    // Code arbitre du tournoi, défini par l'organisateur à la création. Retourne
    // null si non défini/vide (le bouton "Arbitre" reste alors masqué côté table).
    fun getCodeArbitre(context: Context, idTournoi: Int): String? {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            var code: String? = null
            db.rawQuery("SELECT code_arbitre FROM tournois WHERE ID = ?", arrayOf(idTournoi.toString())).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) code = c.getString(0)
            }
            if (code.isNullOrEmpty()) null else code
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ getCodeArbitre : ${e.message}", e)
            null
        } finally {
            DatabaseHelper.closeDatabase()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Appel arbitre depuis la table : la donne n'a pas pu être jouée normalement
    // (litige, donne injouable). On enregistre une ligne "resultats" placeholder
    // (contrat="0" = pas de contrat, déjà reconnu ailleurs) via enregistreDonne
    // pour faire avancer le mouvement normalement, puis on la marque aussitôt en
    // erreur (moyenne individuelle) — pas de contrat réel à saisir à la table.
    // ─────────────────────────────────────────────────────────────────────────
    fun enregistrerDonneArbitre(
        context: Context, idTournoi: Int, mvntNumero: Int, equipeNS: Int, equipeEO: Int,
        numeroTable: Int, numeroDonne: Int, indexDonneJouee: Int
    ): Int {
        val indexDonneAJouer = enregistreDonne(
            context, idTournoi, mvntNumero, equipeNS, equipeEO, numeroTable, numeroDonne, indexDonneJouee,
            contrat = "0", declarant = "", resultatContrat = "=", points = 0, nombrePlis = 0, carteEntame = "",
            historiqueJson = "[]", mainsJson = "[]"
        )
        if (indexDonneAJouer >= 0) {
            marquerErreurDonne(context, idTournoi, numeroDonne, equipeNS, equipeEO, enErreur = true)
        }
        return indexDonneAJouer
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Défaillance d'équipe — décision organisateur (Loi 12.C.2.a WBF/FFB)
    // ─────────────────────────────────────────────────────────────────────────

    // Déclare (ou annule) la défaillance d'une équipe pour ce tournoi. Ne touche
    // jamais aux joueurs/noms — seule la ligne equipes.defaillante de CE tournoi
    // est modifiée. Aucune donne n'est pré-remplie ici : tout se joue à la volée
    // dans passerTableDefaillance, au moment où une vraie table rencontre cette
    // équipe (mirroring la table Relais existante).
    fun declarerEquipeDefaillante(context: Context, idTournoi: Int, equipeNumero: Int, defaillante: Boolean): Boolean {
        val db = DatabaseHelper.getDatabase(context)
        val ok = try {
            val rows = db.update(
                "equipes",
                ContentValues().apply { put("defaillante", if (defaillante) 1 else 0) },
                "id_tournoi=? AND equipe_numero=?",
                arrayOf(idTournoi.toString(), equipeNumero.toString())
            )
            Log.i("DatabaseManager", "✅ declarerEquipeDefaillante équipe=$equipeNumero defaillante=$defaillante ($rows ligne(s))")
            rows > 0
        } catch (e: Exception) {
            Log.e("DatabaseManager", "❌ declarerEquipeDefaillante : ${e.message}", e)
            false
        } finally {
            DatabaseHelper.closeDatabase()
        }
        enregistrerErreurLog(
            context, idTournoi, equipeNumero, "Defaillance",
            if (defaillante) "Équipe $equipeNumero déclarée défaillante"
            else "Défaillance annulée pour l'équipe $equipeNumero"
        )
        return ok
    }

    // Appelée côté table (organisateur ou autre joueur) quand elle détecte que son
    // adversaire de la ronde est une équipe défaillante. Enregistre une ligne
    // placeholder (contrat="0", même sentinel que le bouton Arbitre) pour chaque
    // donne de la ronde, flaguée DEFAILLANCE_NS/EO selon le côté occupé par
    // l'équipe défaillante — puis fait avancer la progression des deux équipes
    // (enregistreDonne s'en charge déjà pour equipeNS ET equipeEO à chaque appel).
    // Protège les donnes déjà réellement jouées (ne les écrase jamais).
    fun passerTableDefaillance(
        context: Context, idTournoi: Int, mvntNumero: Int, equipeNS: Int, equipeEO: Int,
        equipeDefaillante: Int, numeroTable: Int, numerosDonnes: List<Int>, indexDonneJouee: Int
    ): Boolean {
        val typeCorrection = if (equipeDefaillante == equipeNS)
            ClassementManager.TYPE_DEFAILLANCE_NS else ClassementManager.TYPE_DEFAILLANCE_EO

        var indexCourant = indexDonneJouee
        var ok = true
        for (numeroDonne in numerosDonnes) {
            val db = DatabaseHelper.getDatabase(context)
            val dejaJouee = try {
                db.rawQuery(
                    "SELECT 1 FROM resultats WHERE id_tournoi=? AND numero_donne=? AND equipeNS=? LIMIT 1",
                    arrayOf(idTournoi.toString(), numeroDonne.toString(), equipeNS.toString())
                ).use { it.moveToFirst() }
            } finally { DatabaseHelper.closeDatabase() }

            if (dejaJouee) {
                Log.i("DatabaseManager", "⏭️ passerTableDefaillance : donne $numeroDonne déjà jouée, non touchée")
                indexCourant++
                continue
            }

            val indexSuivant = enregistreDonne(
                context, idTournoi, mvntNumero, equipeNS, equipeEO, numeroTable, numeroDonne, indexCourant,
                contrat = "0", declarant = "", resultatContrat = "=", points = 0, nombrePlis = 0, carteEntame = "",
                historiqueJson = "[]", mainsJson = "[]"
            )
            if (indexSuivant < 0) { ok = false; continue }
            indexCourant = indexSuivant

            val dbUpdate = DatabaseHelper.getDatabase(context)
            try {
                dbUpdate.update(
                    "resultats",
                    ContentValues().apply { put("type_correction", typeCorrection) },
                    "id_tournoi=? AND numero_donne=? AND equipeNS=?",
                    arrayOf(idTournoi.toString(), numeroDonne.toString(), equipeNS.toString())
                )
            } finally { DatabaseHelper.closeDatabase() }
        }

        enregistrerErreurLog(
            context, idTournoi, equipeDefaillante,
            "Defaillance", "Ronde $mvntNumero traitée en défaillance (table $numeroTable, donnes $numerosDonnes)"
        )
        finaliserClassementTournoi(context, idTournoi)
        return ok
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Donnes avant tournoi (id_tournoi IS NULL)
    // ─────────────────────────────────────────────────────────────────────────

    fun sauvegarderMainsAvantTournoi(context: Context, numeroDonne: Int, mains: List<List<String>>): Boolean {
        if (mains.size != 4 || !mains.all { it.size == 13 }) return false
        val db = DatabaseHelper.getDatabase(context)
        return try {
            db.beginTransaction()
            // Supprimer ancienne donne si elle existe (id_tournoi=0 = sentinel "avant tournoi")
            db.rawQuery("SELECT main_N, main_E, main_S, main_O FROM donnes WHERE id_tournoi = 0 AND numero_donne = ? LIMIT 1", arrayOf(numeroDonne.toString())).use { c ->
                if (c.moveToFirst()) {
                    listOf(0, 1, 2, 3).forEach { col ->
                        val idMain = c.getLong(col)
                        if (idMain > 0) db.delete("mains", "ID = ?", arrayOf(idMain.toString()))
                    }
                    db.delete("donnes", "id_tournoi = 0 AND numero_donne = ?", arrayOf(numeroDonne.toString()))
                }
            }
            // Insérer nouvelles mains
            val idsMains = mains.map { main ->
                val v = ContentValues()
                main.forEachIndexed { i, carte -> v.put("carte${i + 1}", carte) }
                db.insert("mains", null, v)
            }
            // Récupérer donneur/vulnerable
            var donneur = "N"; var vulnerable = "P"
            db.rawQuery("SELECT donneur, vulnerable FROM donnes_d_v WHERE ID_donnes_d_v = ? LIMIT 1", arrayOf(numeroDonne.toString())).use { c ->
                if (c.moveToFirst()) { donneur = c.getString(0); vulnerable = c.getString(1) }
            }
            db.insert("donnes", null, ContentValues().apply {
                put("id_tournoi", 0)
                put("numero_donne", numeroDonne); put("donneur", donneur); put("vulnerable", vulnerable)
                put("main_N", idsMains[0]); put("main_E", idsMains[1]); put("main_S", idsMains[2]); put("main_O", idsMains[3])
            })
            db.setTransactionSuccessful()
            Log.i("DatabaseManager", "✅ sauvegarderMainsAvantTournoi donne=$numeroDonne")
            true
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ sauvegarderMainsAvantTournoi : ${e.message}", e); false }
        finally { try { db.endTransaction() } catch (_: Exception) {}; DatabaseHelper.closeDatabase() }
    }

    fun getMainsAvantTournoi(context: Context): List<DonneAvantTournoi> {
        val db = DatabaseHelper.getDatabase(context)
        val result = mutableListOf<DonneAvantTournoi>()
        return try {
            db.rawQuery("SELECT numero_donne, donneur, vulnerable, main_N, main_E, main_S, main_O FROM donnes WHERE id_tournoi = 0 ORDER BY numero_donne ASC", null).use { c ->
                while (c.moveToNext()) {
                    val numDonne   = c.getInt(0)
                    val donneur    = c.getString(1) ?: "N"
                    val vulnerable = c.getString(2) ?: "P"
                    val mains = (3..6).map { col ->
                        val idMain = c.getLong(col)
                        val cartes = mutableListOf<String>()
                        db.rawQuery("SELECT carte1,carte2,carte3,carte4,carte5,carte6,carte7,carte8,carte9,carte10,carte11,carte12,carte13 FROM mains WHERE ID = ?", arrayOf(idMain.toString())).use { m ->
                            if (m.moveToFirst()) (0..12).forEach { i -> cartes.add(m.getString(i)) }
                        }
                        cartes.toList()
                    }
                    result.add(DonneAvantTournoi(numDonne, donneur, vulnerable, mains))
                }
            }
            Log.i("DatabaseManager", "✅ getMainsAvantTournoi → ${result.size} donnes")
            result
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ getMainsAvantTournoi : ${e.message}", e); emptyList() }
        finally { DatabaseHelper.closeDatabase() }
    }

    fun supprimerMainsAvantTournoi(context: Context): Boolean {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            db.beginTransaction()
            db.rawQuery("SELECT main_N, main_E, main_S, main_O FROM donnes WHERE id_tournoi = 0", null).use { c ->
                while (c.moveToNext()) {
                    listOf(0, 1, 2, 3).forEach { col ->
                        val idMain = c.getLong(col)
                        if (idMain > 0) db.delete("mains", "ID = ?", arrayOf(idMain.toString()))
                    }
                }
            }
            db.delete("donnes", "id_tournoi = 0", null)
            db.setTransactionSuccessful()
            Log.i("DatabaseManager", "✅ supprimerMainsAvantTournoi")
            true
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ supprimerMainsAvantTournoi : ${e.message}", e); false }
        finally { try { db.endTransaction() } catch (_: Exception) {}; DatabaseHelper.closeDatabase() }
    }

    fun attribuerDonnesAuTournoi(context: Context, idTournoi: Int): Int {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            db.beginTransaction()
            val deja = mutableListOf<Int>()
            db.rawQuery("SELECT numero_donne FROM donnes WHERE id_tournoi = ?", arrayOf(idTournoi.toString())).use { c ->
                while (c.moveToNext()) deja.add(c.getInt(0))
            }
            val v = ContentValues().apply { put("id_tournoi", idTournoi) }
            val where = if (deja.isEmpty()) "id_tournoi = 0"
                        else "id_tournoi = 0 AND numero_donne NOT IN (${deja.joinToString(",")})"
            val nb = db.update("donnes", v, where, null)
            db.setTransactionSuccessful()
            Log.i("DatabaseManager", "✅ attribuerDonnesAuTournoi tournoi=$idTournoi → $nb donnes")
            nb
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ attribuerDonnesAuTournoi : ${e.message}", e); -1 }
        finally { try { db.endTransaction() } catch (_: Exception) {}; DatabaseHelper.closeDatabase() }
    }

    // ─────────────────────────────────────────────────────────────────
    // Donnes cibles (aide à la duplication manuelle — V3)
    // Réutilise les tables "donnes"/"mains" avec un sentinel dédié
    // (id_tournoi = -1) distinct de 0 ("avant tournoi") et des vrais
    // tournois (id_tournoi > 0), pour ne jamais se mélanger avec eux.
    // ─────────────────────────────────────────────────────────────────
    private const val ID_TOURNOI_DONNE_CIBLE = -1

    fun sauvegarderDonneCible(context: Context, numeroDonne: Int, mains: List<List<String>>): Boolean {
        if (mains.size != 4 || !mains.all { it.size == 13 }) return false
        val db = DatabaseHelper.getDatabase(context)
        return try {
            db.beginTransaction()
            db.rawQuery(
                "SELECT main_N, main_E, main_S, main_O FROM donnes WHERE id_tournoi = ? AND numero_donne = ? LIMIT 1",
                arrayOf(ID_TOURNOI_DONNE_CIBLE.toString(), numeroDonne.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    listOf(0, 1, 2, 3).forEach { col ->
                        val idMain = c.getLong(col)
                        if (idMain > 0) db.delete("mains", "ID = ?", arrayOf(idMain.toString()))
                    }
                    db.delete("donnes", "id_tournoi = ? AND numero_donne = ?", arrayOf(ID_TOURNOI_DONNE_CIBLE.toString(), numeroDonne.toString()))
                }
            }
            val idsMains = mains.map { main ->
                val v = ContentValues()
                main.forEachIndexed { i, carte -> v.put("carte${i + 1}", carte) }
                db.insert("mains", null, v)
            }
            var donneur = "N"; var vulnerable = "P"
            db.rawQuery("SELECT donneur, vulnerable FROM donnes_d_v WHERE ID_donnes_d_v = ? LIMIT 1", arrayOf(numeroDonne.toString())).use { c ->
                if (c.moveToFirst()) { donneur = c.getString(0); vulnerable = c.getString(1) }
            }
            db.insert("donnes", null, ContentValues().apply {
                put("id_tournoi", ID_TOURNOI_DONNE_CIBLE)
                put("numero_donne", numeroDonne); put("donneur", donneur); put("vulnerable", vulnerable)
                put("main_N", idsMains[0]); put("main_E", idsMains[1]); put("main_S", idsMains[2]); put("main_O", idsMains[3])
            })
            db.setTransactionSuccessful()
            Log.i("DatabaseManager", "✅ sauvegarderDonneCible donne=$numeroDonne")
            true
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ sauvegarderDonneCible : ${e.message}", e); false }
        finally { try { db.endTransaction() } catch (_: Exception) {}; DatabaseHelper.closeDatabase() }
    }

    fun getDonnesCibles(context: Context): List<DonneAvantTournoi> {
        val db = DatabaseHelper.getDatabase(context)
        val result = mutableListOf<DonneAvantTournoi>()
        return try {
            db.rawQuery(
                "SELECT numero_donne, donneur, vulnerable, main_N, main_E, main_S, main_O FROM donnes WHERE id_tournoi = ? ORDER BY numero_donne ASC",
                arrayOf(ID_TOURNOI_DONNE_CIBLE.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val numDonne   = c.getInt(0)
                    val donneur    = c.getString(1) ?: "N"
                    val vulnerable = c.getString(2) ?: "P"
                    val mains = (3..6).map { col ->
                        val idMain = c.getLong(col)
                        val cartes = mutableListOf<String>()
                        db.rawQuery("SELECT carte1,carte2,carte3,carte4,carte5,carte6,carte7,carte8,carte9,carte10,carte11,carte12,carte13 FROM mains WHERE ID = ?", arrayOf(idMain.toString())).use { m ->
                            if (m.moveToFirst()) (0..12).forEach { i -> cartes.add(m.getString(i)) }
                        }
                        cartes.toList()
                    }
                    result.add(DonneAvantTournoi(numDonne, donneur, vulnerable, mains))
                }
            }
            Log.i("DatabaseManager", "✅ getDonnesCibles → ${result.size} donnes")
            result
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ getDonnesCibles : ${e.message}", e); emptyList() }
        finally { DatabaseHelper.closeDatabase() }
    }

    fun supprimerDonneCible(context: Context, numeroDonne: Int): Boolean {
        val db = DatabaseHelper.getDatabase(context)
        return try {
            db.beginTransaction()
            db.rawQuery(
                "SELECT main_N, main_E, main_S, main_O FROM donnes WHERE id_tournoi = ? AND numero_donne = ?",
                arrayOf(ID_TOURNOI_DONNE_CIBLE.toString(), numeroDonne.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    listOf(0, 1, 2, 3).forEach { col ->
                        val idMain = c.getLong(col)
                        if (idMain > 0) db.delete("mains", "ID = ?", arrayOf(idMain.toString()))
                    }
                }
            }
            db.delete("donnes", "id_tournoi = ? AND numero_donne = ?", arrayOf(ID_TOURNOI_DONNE_CIBLE.toString(), numeroDonne.toString()))
            db.setTransactionSuccessful()
            Log.i("DatabaseManager", "✅ supprimerDonneCible donne=$numeroDonne")
            true
        } catch (e: Exception) { Log.e("DatabaseManager", "❌ supprimerDonneCible : ${e.message}", e); false }
        finally { try { db.endTransaction() } catch (_: Exception) {}; DatabaseHelper.closeDatabase() }
    }
}
