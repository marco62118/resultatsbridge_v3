package app.resultatsbridge.organisateur.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object DatabaseHelper {

    /**
     *  base de données "tournoi.db" contien tous les types de tournoi
     *  tous les tournois fermé et encours (ouvert)
     *  tous les joueurs du l'amicale de Bridge d'Embrun
     */
    private const val DATABASE_NAME = "tournoi.db"
    private const val ASSETS_DATABASE = "tournoi_vierge.db"
    private var db: SQLiteDatabase? = null
    private var refCount = 0
    private val lock = Any()

    // 🔹 Initialisation de la base : copie depuis assets si elle n’existe pas encore
    fun initializeDatabase(context: Context) {
        try {
            val dbPath = context.getDatabasePath(DATABASE_NAME)
            if (!dbPath.exists()) {
                Log.i("DatabaseHelper", "📁 Copie de la base depuis assets (premier lancement)")
                copyDatabaseFromAssets(context, dbPath)
            } else {
                Log.i("DatabaseHelper", "✅ Base déjà existante détectée")
            }
            db = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READWRITE)
            Log.i("DatabaseHelper", "📂 Base ouverte avec succès")
            migrerSiNecessaire(db!!)
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "❌ Erreur lors de l’initialisation de la base", e)
        }
    }

    private fun migrerSiNecessaire(database: SQLiteDatabase) {
        val colonnesExistantes = mutableSetOf<String>()
        database.rawQuery("PRAGMA table_info(tournois)", null).use { c ->
            while (c.moveToNext()) colonnesExistantes.add(c.getString(c.getColumnIndexOrThrow("name")))
        }
        val aAjouter = mapOf(
            "nbre_mouvements"      to "INTEGER NOT NULL DEFAULT 0",
            "nbre_donnes_par_table" to "INTEGER NOT NULL DEFAULT 0",
            "nbre_tables"          to "INTEGER NOT NULL DEFAULT 0",
            "code_arbitre"         to "TEXT DEFAULT NULL"
        )
        aAjouter.forEach { (col, def) ->
            if (col !in colonnesExistantes) {
                database.execSQL("ALTER TABLE tournois ADD COLUMN $col $def")
                Log.i("DatabaseHelper", "🔧 Colonne ajoutée : $col")
            }
        }
        if ("nbre_donne_total" !in colonnesExistantes) {
            database.execSQL("ALTER TABLE tournois ADD COLUMN nbre_donne_total INTEGER NOT NULL DEFAULT 0")
            if ("nbre_donne" in colonnesExistantes) {
                database.execSQL("UPDATE tournois SET nbre_donne_total = nbre_donne")
            }
            Log.i("DatabaseHelper", "🔧 Colonne ajoutée : nbre_donne_total")
        }
        // Ajouter MitchellGuéridon dans la table type si absent (tables=0 = type dynamique)
        database.rawQuery("SELECT COUNT(*) FROM type WHERE type = 'MitchellGueridon'", null).use { c ->
            if (c.moveToFirst() && c.getInt(0) == 0) {
                database.execSQL("INSERT INTO type (type, nombre_table, nombre_donne) VALUES ('MitchellGueridon', 0, 0)")
                Log.i("DatabaseHelper", "🔧 Type MitchellGuéridon ajouté dans la table type")
            }
        }

        // 06/08/2026 : nouveaux préréglages Howell élargis (mêmes équipes/tables que
        // Howell_4t21d / Howell_5t18d, réutilisées telles quelles - seuls les jeux de
        // donnes sont plus larges, formule de rotation par table déjà confirmée). Ajout
        // pour les installations existantes (les installs neuves les ont déjà via
        // tournoi_vierge.db) : création de table idempotente + seed uniquement si vide,
        // même style que le bloc MitchellGuéridon ci-dessus.
        ajouterPreregleHowell4t28d(database)
        ajouterPreregleHowell5t27d(database)
        ajouterPreregleHowell7t26d(database)
        // Ajouter par_texte dans donnes si absent
        val colonnesDonnes = mutableSetOf<String>()
        database.rawQuery("PRAGMA table_info(donnes)", null).use { c ->
            while (c.moveToNext()) colonnesDonnes.add(c.getString(c.getColumnIndexOrThrow("name")))
        }
        if ("par_texte" !in colonnesDonnes) {
            database.execSQL("ALTER TABLE donnes ADD COLUMN par_texte TEXT DEFAULT NULL")
            Log.i("DatabaseHelper", "🔧 Colonne ajoutée : donnes.par_texte")
        }

        // Ajouter defaillante dans equipes si absent (Défaillance d'équipe)
        val colonnesEquipes = mutableSetOf<String>()
        database.rawQuery("PRAGMA table_info(equipes)", null).use { c ->
            while (c.moveToNext()) colonnesEquipes.add(c.getString(c.getColumnIndexOrThrow("name")))
        }
        if ("defaillante" !in colonnesEquipes) {
            database.execSQL("ALTER TABLE equipes ADD COLUMN defaillante INTEGER DEFAULT 0")
            Log.i("DatabaseHelper", "🔧 Colonne ajoutée : equipes.defaillante")
        }

        // Ajouter type_correction dans resultats si absent (Erreur sur une donne / Défaillance)
        val colonnesResultats = mutableSetOf<String>()
        database.rawQuery("PRAGMA table_info(resultats)", null).use { c ->
            while (c.moveToNext()) colonnesResultats.add(c.getString(c.getColumnIndexOrThrow("name")))
        }
        if ("type_correction" !in colonnesResultats) {
            database.execSQL("ALTER TABLE resultats ADD COLUMN type_correction TEXT DEFAULT NULL")
            Log.i("DatabaseHelper", "🔧 Colonne ajoutée : resultats.type_correction")
        }

        database.execSQL(
            """CREATE TABLE IF NOT EXISTS erreurs_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp TEXT NOT NULL,
                id_tournoi INTEGER DEFAULT -1,
                equipe_numero INTEGER DEFAULT -1,
                etape TEXT DEFAULT '',
                message TEXT DEFAULT ''
            )"""
        )
        Log.i("DatabaseHelper", "🔧 Table erreurs_log vérifiée/créée")
    }

    // ─────────────────────────────────────────────────────────────────────
    // 06/08/2026 : préréglage Howell 4 tables / 28 donnes (7 mouvements ×
    // 4 donnes, au lieu de 3 pour Howell_4t21d). Équipes/tables identiques à
    // Howell_4t21d, donnes recalculées avec la même formule de rotation par
    // table (offsets T1=0, T2=3, T3=5, T4=6) sur un jeu de 4 donnes.
    // ─────────────────────────────────────────────────────────────────────
    private fun ajouterPreregleHowell4t28d(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS "Howell_4t28d" (
                "ID" INTEGER,
                "mvnt_numero" INTEGER NOT NULL,
                "table_numero" INTEGER NOT NULL,
                "equipe_NS" INTEGER NOT NULL,
                "equipe_EO" INTEGER NOT NULL,
                "numero_d1" INTEGER NOT NULL,
                "numero_d2" INTEGER NOT NULL,
                "numero_d3" INTEGER NOT NULL,
                "numero_d4" INTEGER NOT NULL,
                PRIMARY KEY("ID" AUTOINCREMENT)
            )"""
        )
        var nbreLignes = 0
        database.rawQuery("SELECT COUNT(*) FROM Howell_4t28d", null).use { c ->
            if (c.moveToFirst()) nbreLignes = c.getInt(0)
        }
        if (nbreLignes == 0) {
            val lignes = listOf(
                intArrayOf(1, 1, 8, 1, 1, 2, 3, 4),
                intArrayOf(1, 2, 2, 6, 13, 14, 15, 16),
                intArrayOf(1, 3, 5, 7, 21, 22, 23, 24),
                intArrayOf(1, 4, 3, 4, 25, 26, 27, 28),
                intArrayOf(2, 1, 8, 2, 5, 6, 7, 8),
                intArrayOf(2, 2, 3, 7, 17, 18, 19, 20),
                intArrayOf(2, 3, 6, 1, 25, 26, 27, 28),
                intArrayOf(2, 4, 4, 5, 1, 2, 3, 4),
                intArrayOf(3, 1, 8, 3, 9, 10, 11, 12),
                intArrayOf(3, 2, 4, 1, 21, 22, 23, 24),
                intArrayOf(3, 3, 7, 2, 1, 2, 3, 4),
                intArrayOf(3, 4, 5, 6, 5, 6, 7, 8),
                intArrayOf(4, 1, 8, 4, 13, 14, 15, 16),
                intArrayOf(4, 2, 5, 2, 25, 26, 27, 28),
                intArrayOf(4, 3, 1, 3, 5, 6, 7, 8),
                intArrayOf(4, 4, 6, 7, 9, 10, 11, 12),
                intArrayOf(5, 1, 8, 5, 17, 18, 19, 20),
                intArrayOf(5, 2, 6, 3, 1, 2, 3, 4),
                intArrayOf(5, 3, 2, 4, 9, 10, 11, 12),
                intArrayOf(5, 4, 7, 1, 13, 14, 15, 16),
                intArrayOf(6, 1, 8, 6, 21, 22, 23, 24),
                intArrayOf(6, 2, 7, 4, 5, 6, 7, 8),
                intArrayOf(6, 3, 3, 5, 13, 14, 15, 16),
                intArrayOf(6, 4, 1, 2, 17, 18, 19, 20),
                intArrayOf(7, 1, 8, 7, 25, 26, 27, 28),
                intArrayOf(7, 2, 1, 5, 9, 10, 11, 12),
                intArrayOf(7, 3, 4, 6, 17, 18, 19, 20),
                intArrayOf(7, 4, 2, 3, 21, 22, 23, 24)
            )
            lignes.forEach { l ->
                database.execSQL(
                    "INSERT INTO Howell_4t28d (mvnt_numero, table_numero, equipe_NS, equipe_EO, numero_d1, numero_d2, numero_d3, numero_d4) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    l.toTypedArray()
                )
            }
            Log.i("DatabaseHelper", "🔧 ${lignes.size} lignes de mouvement insérées dans Howell_4t28d")
        }
        database.rawQuery("SELECT COUNT(*) FROM type WHERE type = 'Howell_4t28d'", null).use { c ->
            if (c.moveToFirst() && c.getInt(0) == 0) {
                database.execSQL("INSERT INTO type (type, nombre_table, nombre_donne) VALUES ('Howell_4t28d', 4, 28)")
                Log.i("DatabaseHelper", "🔧 Type Howell_4t28d ajouté dans la table type")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 06/08/2026 : préréglage Howell 5 tables / 27 donnes (9 mouvements ×
    // 3 donnes, au lieu de 2 pour Howell_5t18d). Équipes/tables identiques à
    // Howell_5t18d, donnes recalculées avec la même formule de rotation par
    // table (offsets T1=0, T2=1, T3=2, T4=7, T5=8) sur un jeu de 3 donnes.
    // ─────────────────────────────────────────────────────────────────────
    private fun ajouterPreregleHowell5t27d(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS "Howell_5t27d" (
                "ID" INTEGER,
                "mvnt_numero" INTEGER NOT NULL,
                "table_numero" INTEGER NOT NULL,
                "equipe_NS" INTEGER NOT NULL,
                "equipe_EO" INTEGER NOT NULL,
                "numero_d1" INTEGER NOT NULL,
                "numero_d2" INTEGER NOT NULL,
                "numero_d3" INTEGER NOT NULL,
                PRIMARY KEY("ID" AUTOINCREMENT)
            )"""
        )
        var nbreLignes = 0
        database.rawQuery("SELECT COUNT(*) FROM Howell_5t27d", null).use { c ->
            if (c.moveToFirst()) nbreLignes = c.getInt(0)
        }
        if (nbreLignes == 0) {
            val lignes = listOf(
                intArrayOf(1, 1, 10, 1, 1, 2, 3),
                intArrayOf(1, 2, 8, 9, 4, 5, 6),
                intArrayOf(1, 3, 6, 4, 7, 8, 9),
                intArrayOf(1, 4, 3, 7, 22, 23, 24),
                intArrayOf(1, 5, 2, 5, 25, 26, 27),
                intArrayOf(2, 1, 10, 2, 4, 5, 6),
                intArrayOf(2, 2, 9, 1, 7, 8, 9),
                intArrayOf(2, 3, 7, 5, 10, 11, 12),
                intArrayOf(2, 4, 4, 8, 25, 26, 27),
                intArrayOf(2, 5, 3, 6, 1, 2, 3),
                intArrayOf(3, 1, 10, 3, 7, 8, 9),
                intArrayOf(3, 2, 1, 2, 10, 11, 12),
                intArrayOf(3, 3, 8, 6, 13, 14, 15),
                intArrayOf(3, 4, 5, 9, 1, 2, 3),
                intArrayOf(3, 5, 4, 7, 4, 5, 6),
                intArrayOf(4, 1, 10, 4, 10, 11, 12),
                intArrayOf(4, 2, 2, 3, 13, 14, 15),
                intArrayOf(4, 3, 9, 7, 16, 17, 18),
                intArrayOf(4, 4, 6, 1, 4, 5, 6),
                intArrayOf(4, 5, 5, 8, 7, 8, 9),
                intArrayOf(5, 1, 10, 5, 13, 14, 15),
                intArrayOf(5, 2, 3, 4, 16, 17, 18),
                intArrayOf(5, 3, 1, 8, 19, 20, 21),
                intArrayOf(5, 4, 7, 2, 7, 8, 9),
                intArrayOf(5, 5, 6, 9, 10, 11, 12),
                intArrayOf(6, 1, 10, 6, 16, 17, 18),
                intArrayOf(6, 2, 4, 5, 19, 20, 21),
                intArrayOf(6, 3, 2, 9, 22, 23, 24),
                intArrayOf(6, 4, 8, 3, 10, 11, 12),
                intArrayOf(6, 5, 7, 1, 13, 14, 15),
                intArrayOf(7, 1, 10, 7, 19, 20, 21),
                intArrayOf(7, 2, 5, 6, 22, 23, 24),
                intArrayOf(7, 3, 3, 1, 25, 26, 27),
                intArrayOf(7, 4, 9, 4, 13, 14, 15),
                intArrayOf(7, 5, 8, 2, 16, 17, 18),
                intArrayOf(8, 1, 10, 8, 22, 23, 24),
                intArrayOf(8, 2, 6, 7, 25, 26, 27),
                intArrayOf(8, 3, 4, 2, 1, 2, 3),
                intArrayOf(8, 4, 1, 5, 16, 17, 18),
                intArrayOf(8, 5, 9, 3, 19, 20, 21),
                intArrayOf(9, 1, 10, 9, 25, 26, 27),
                intArrayOf(9, 2, 7, 8, 1, 2, 3),
                intArrayOf(9, 3, 5, 3, 4, 5, 6),
                intArrayOf(9, 4, 2, 6, 19, 20, 21),
                intArrayOf(9, 5, 1, 4, 22, 23, 24)
            )
            lignes.forEach { l ->
                database.execSQL(
                    "INSERT INTO Howell_5t27d (mvnt_numero, table_numero, equipe_NS, equipe_EO, numero_d1, numero_d2, numero_d3) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    l.toTypedArray()
                )
            }
            Log.i("DatabaseHelper", "🔧 ${lignes.size} lignes de mouvement insérées dans Howell_5t27d")
        }
        database.rawQuery("SELECT COUNT(*) FROM type WHERE type = 'Howell_5t27d'", null).use { c ->
            if (c.moveToFirst() && c.getInt(0) == 0) {
                database.execSQL("INSERT INTO type (type, nombre_table, nombre_donne) VALUES ('Howell_5t27d', 5, 27)")
                Log.i("DatabaseHelper", "🔧 Type Howell_5t27d ajouté dans la table type")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 06/08/2026 : préréglage Howell 7 tables / 26 donnes (13 mouvements ×
    // 2 donnes) - NOUVEAU préréglage (aucun équivalent existant avant),
    // transcrit intégralement depuis le carton officiel FFB "Howell classique
    // 14 paires" (bridge-macon.fr, compatible FFBClubNet) - équipes/tables ET
    // donnes, vérifié par script (14 équipes chacune 1x par mouvement, 26
    // donnes distinctes par table, étuis disjoints entre tables à un même
    // mouvement).
    // ─────────────────────────────────────────────────────────────────────
    private fun ajouterPreregleHowell7t26d(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS "Howell_7t26d" (
                "ID" INTEGER,
                "mvnt_numero" INTEGER NOT NULL,
                "table_numero" INTEGER NOT NULL,
                "equipe_NS" INTEGER NOT NULL,
                "equipe_EO" INTEGER NOT NULL,
                "numero_d1" INTEGER NOT NULL,
                "numero_d2" INTEGER NOT NULL,
                PRIMARY KEY("ID" AUTOINCREMENT)
            )"""
        )
        var nbreLignes = 0
        database.rawQuery("SELECT COUNT(*) FROM Howell_7t26d", null).use { c ->
            if (c.moveToFirst()) nbreLignes = c.getInt(0)
        }
        if (nbreLignes == 0) {
            val lignes = listOf(
                intArrayOf(1, 1, 14, 1, 1, 2),
                intArrayOf(2, 1, 14, 2, 3, 4),
                intArrayOf(3, 1, 14, 3, 5, 6),
                intArrayOf(4, 1, 14, 4, 7, 8),
                intArrayOf(5, 1, 14, 5, 9, 10),
                intArrayOf(6, 1, 14, 6, 11, 12),
                intArrayOf(7, 1, 14, 7, 13, 14),
                intArrayOf(8, 1, 14, 8, 15, 16),
                intArrayOf(9, 1, 14, 9, 17, 18),
                intArrayOf(10, 1, 14, 10, 19, 20),
                intArrayOf(11, 1, 14, 11, 21, 22),
                intArrayOf(12, 1, 14, 12, 23, 24),
                intArrayOf(13, 1, 14, 13, 25, 26),
                intArrayOf(1, 2, 6, 9, 5, 6),
                intArrayOf(2, 2, 7, 10, 7, 8),
                intArrayOf(3, 2, 8, 11, 9, 10),
                intArrayOf(4, 2, 9, 12, 11, 12),
                intArrayOf(5, 2, 10, 13, 13, 14),
                intArrayOf(6, 2, 11, 1, 15, 16),
                intArrayOf(7, 2, 12, 2, 17, 18),
                intArrayOf(8, 2, 13, 3, 19, 20),
                intArrayOf(9, 2, 1, 4, 21, 22),
                intArrayOf(10, 2, 2, 5, 23, 24),
                intArrayOf(11, 2, 3, 6, 25, 26),
                intArrayOf(12, 2, 4, 7, 1, 2),
                intArrayOf(13, 2, 5, 8, 3, 4),
                intArrayOf(1, 3, 5, 11, 7, 8),
                intArrayOf(2, 3, 6, 12, 9, 10),
                intArrayOf(3, 3, 7, 13, 11, 12),
                intArrayOf(4, 3, 8, 1, 13, 14),
                intArrayOf(5, 3, 9, 2, 15, 16),
                intArrayOf(6, 3, 10, 3, 17, 18),
                intArrayOf(7, 3, 11, 4, 19, 20),
                intArrayOf(8, 3, 12, 5, 21, 22),
                intArrayOf(9, 3, 13, 6, 23, 24),
                intArrayOf(10, 3, 1, 7, 25, 26),
                intArrayOf(11, 3, 2, 8, 1, 2),
                intArrayOf(12, 3, 3, 9, 3, 4),
                intArrayOf(13, 3, 4, 10, 5, 6),
                intArrayOf(1, 4, 4, 13, 9, 10),
                intArrayOf(2, 4, 5, 1, 11, 12),
                intArrayOf(3, 4, 6, 2, 13, 14),
                intArrayOf(4, 4, 7, 3, 15, 16),
                intArrayOf(5, 4, 8, 4, 17, 18),
                intArrayOf(6, 4, 9, 5, 19, 20),
                intArrayOf(7, 4, 10, 6, 21, 22),
                intArrayOf(8, 4, 11, 7, 23, 24),
                intArrayOf(9, 4, 12, 8, 25, 26),
                intArrayOf(10, 4, 13, 9, 1, 2),
                intArrayOf(11, 4, 1, 10, 3, 4),
                intArrayOf(12, 4, 2, 11, 5, 6),
                intArrayOf(13, 4, 3, 12, 7, 8),
                intArrayOf(1, 5, 7, 12, 19, 20),
                intArrayOf(2, 5, 8, 13, 21, 22),
                intArrayOf(3, 5, 9, 1, 23, 24),
                intArrayOf(4, 5, 10, 2, 25, 26),
                intArrayOf(5, 5, 11, 3, 1, 2),
                intArrayOf(6, 5, 12, 4, 3, 4),
                intArrayOf(7, 5, 13, 5, 5, 6),
                intArrayOf(8, 5, 1, 6, 7, 8),
                intArrayOf(9, 5, 2, 7, 9, 10),
                intArrayOf(10, 5, 3, 8, 11, 12),
                intArrayOf(11, 5, 4, 9, 13, 14),
                intArrayOf(12, 5, 5, 10, 15, 16),
                intArrayOf(13, 5, 6, 11, 17, 18),
                intArrayOf(1, 6, 2, 3, 21, 22),
                intArrayOf(2, 6, 3, 4, 23, 24),
                intArrayOf(3, 6, 4, 5, 25, 26),
                intArrayOf(4, 6, 5, 6, 1, 2),
                intArrayOf(5, 6, 6, 7, 3, 4),
                intArrayOf(6, 6, 7, 8, 5, 6),
                intArrayOf(7, 6, 8, 9, 7, 8),
                intArrayOf(8, 6, 9, 10, 9, 10),
                intArrayOf(9, 6, 10, 11, 11, 12),
                intArrayOf(10, 6, 11, 12, 13, 14),
                intArrayOf(11, 6, 12, 13, 15, 16),
                intArrayOf(12, 6, 13, 1, 17, 18),
                intArrayOf(13, 6, 1, 2, 19, 20),
                intArrayOf(1, 7, 8, 10, 23, 24),
                intArrayOf(2, 7, 9, 11, 25, 26),
                intArrayOf(3, 7, 10, 12, 1, 2),
                intArrayOf(4, 7, 11, 13, 3, 4),
                intArrayOf(5, 7, 12, 1, 5, 6),
                intArrayOf(6, 7, 13, 2, 7, 8),
                intArrayOf(7, 7, 1, 3, 9, 10),
                intArrayOf(8, 7, 2, 4, 11, 12),
                intArrayOf(9, 7, 3, 5, 13, 14),
                intArrayOf(10, 7, 4, 6, 15, 16),
                intArrayOf(11, 7, 5, 7, 17, 18),
                intArrayOf(12, 7, 6, 8, 19, 20),
                intArrayOf(13, 7, 7, 9, 21, 22)
            )
            lignes.forEach { l ->
                database.execSQL(
                    "INSERT INTO Howell_7t26d (mvnt_numero, table_numero, equipe_NS, equipe_EO, numero_d1, numero_d2) VALUES (?, ?, ?, ?, ?, ?)",
                    l.toTypedArray()
                )
            }
            Log.i("DatabaseHelper", "🔧 ${lignes.size} lignes de mouvement insérées dans Howell_7t26d")
        }
        database.rawQuery("SELECT COUNT(*) FROM type WHERE type = 'Howell_7t26d'", null).use { c ->
            if (c.moveToFirst() && c.getInt(0) == 0) {
                database.execSQL("INSERT INTO type (type, nombre_table, nombre_donne) VALUES ('Howell_7t26d', 7, 26)")
                Log.i("DatabaseHelper", "🔧 Type Howell_7t26d ajouté dans la table type")
            }
        }
    }

    // 🔹 Ouvre la base (lecture/écriture)
    // Compte le nombre d'appels en cours (refCount) pour qu'un appel concurrent
    // ne ferme jamais la base pendant qu'un autre est encore en train de l'utiliser.
    fun getDatabase(context: Context): SQLiteDatabase = synchronized(lock) {
        if (db == null || !db!!.isOpen) {
            val dbPath = context.getDatabasePath(DATABASE_NAME)
            db = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READWRITE)
            Log.i("DatabaseHelper", "📂 Base OUVERTE - Stack: ${Thread.currentThread().stackTrace[3]}")
        }
        refCount++
        db!!
    }

    // ✅ Ne ferme réellement la base que lorsque le dernier appelant en cours l'a libérée
    fun closeDatabase() {
        synchronized(lock) {
            if (refCount > 0) refCount--
            if (refCount > 0) return
            try {
                db?.let {
                    if (it.isOpen) {
                        it.close()
                        Log.i("DatabaseHelper", "📌 Base FERMÉE - Stack: ${Thread.currentThread().stackTrace[3]}")
                    }
                }
            } catch (e: Exception) {
                Log.e("DatabaseHelper", "❌ Erreur lors de la fermeture de la base", e)
            } finally {
                db = null
            }
        }
    }

    // ✅ NOUVELLE FONCTION : Fermeture forcée avec attente
    fun closeDatabaseAndWait() {
        closeDatabase()
        try {
            Thread.sleep(200)  // Attendre que le pool libère
            Log.i("DatabaseHelper", "⏱️ Attente libération pool terminée")
        } catch (e: InterruptedException) {
            Log.w("DatabaseHelper", "Interruption pendant l'attente")
        }
    }

    // 🔹 Copie le fichier tournoi.db depuis les assets
    private fun copyDatabaseFromAssets(context: Context, destinationFile: File) {
        try {
            val inputStream = context.assets.open(ASSETS_DATABASE)
            destinationFile.parentFile?.mkdirs()
            val outputStream = FileOutputStream(destinationFile)
            val buffer = ByteArray(1024)
            var length: Int
            while (inputStream.read(buffer).also { length = it } > 0) {
                outputStream.write(buffer, 0, length)
            }
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            Log.i("DatabaseHelper", "📦 Base copiée avec succès depuis les assets")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "❌ Erreur lors de la copie de la base depuis assets", e)
        }
    }
}
