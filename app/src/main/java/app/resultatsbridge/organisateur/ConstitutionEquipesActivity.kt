// ACTIVITÉ : Constitution des équipes (organisateur) — saisie avant démarrage
package app.resultatsbridge.organisateur

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import app.resultatsbridge.main.BaseActivity
import app.resultatsbridge.common.AppScaffold
import app.resultatsbridge.common.theme.BridgeServeurTheme
import app.resultatsbridge.common.model.Equipe
import app.resultatsbridge.common.model.Joueur
import app.resultatsbridge.screens.ConstitutionEquipesScreen

class ConstitutionEquipesActivity : BaseActivity() {

    // Étape 3 (05/08/2026) : lance CreationTournoiActivity (choix du type) et relaie
    // son résultat final vers l'appelant de CETTE activité (soit OrganisateurActivity
    // directement à l'Étape 1, soit une autre instance de cette même activité côté
    // Étape 3 — voir onEquipesValidees plus bas et le plan "Inverser l'ordre de
    // création d'un tournoi" pour le détail de la chaîne de relais.
    private lateinit var creationTypeLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        creationTypeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                setResult(Activity.RESULT_OK, result.data)
                finish()
            }
        }

        val idTournoi          = intent.getIntExtra("id_tournoi", -1)
        val nbEquipesRecu      = intent.getIntExtra("nombre_equipes", 0)
        val modeOnline         = intent.getBooleanExtra("mode_online", false)
        // Donnes par table transmis par CreationTournoiActivity (Mitchell uniquement, 0 sinon)
        val nbreDonnesParTable = intent.getIntExtra("nbre_donnes_par_table", 0)
        val typeTournoi = intent.getStringExtra("type_tournoi") ?: ""
        // Reçu depuis OrganisateurActivity (Étape 1, 05/08/2026), retransmis tel quel à
        // CreationTournoiActivity (Étape 2) une fois les équipes validées.
        val ipServeur = intent.getStringExtra("ip_serveur") ?: ""

        @Suppress("DEPRECATION")
        val joueurs = intent.getParcelableArrayListExtra<Joueur>("joueurs") ?: emptyList()
        // Présents seulement au 2ᵉ passage (Étape 3, depuis CreationTournoiActivity) :
        // équipes déjà constituées et pool de joueurs pas encore utilisés.
        @Suppress("DEPRECATION")
        val equipesRecues = intent.getParcelableArrayListExtra<Equipe>("equipes") ?: emptyList()
        @Suppress("DEPRECATION")
        val joueursRestantsRecus = intent.getParcelableArrayListExtra<Joueur>("joueurs_restants") ?: emptyList()

        Log.i("ConstitutionEquipesActivity", "🎯 idTournoi=$idTournoi reçu")
        Log.i("ConstitutionEquipesActivity", "👥 ${joueurs.size} joueurs reçus via intent")
        Log.i("ConstitutionEquipesActivity", "👥 $nbEquipesRecu nbEquipesRecu reçus via intent")
        Log.i("ConstitutionEquipesActivity", "🌐 modeOnline=$modeOnline")
        Log.i("ConstitutionEquipesActivity", "🃏 nbreDonnesParTable=$nbreDonnesParTable")
        Log.i("ConstitutionEquipesActivity", "📋 ${equipesRecues.size} équipes déjà construites reçues (Étape 3 si > 0)")

        setContent {
            BridgeServeurTheme {
                AppScaffold(
                    ipAddress = ipServeur,
                    numeroTournoi = idTournoi,
                    equipeChoisie = null,
                    nomEcran = "Constitution des équipes"
                ) {
                    ConstitutionEquipesScreen(
                        idTournoi = idTournoi,
                        joueursInitiaux = joueurs,
                        nombreEquipes = nbEquipesRecu,
                        modeOnline = modeOnline,
                        nbreDonnesParTable = nbreDonnesParTable,
                        typeTournoi = typeTournoi,
                        joueursRestants = joueursRestantsRecus,
                        equipesInitiales = equipesRecues,
                        onEquipesValidees = { equipes, joueursDispo ->
                            // Étape 2 : lance le choix du type de tournoi avec la liste
                            // d'équipes déjà construite + le pool de joueurs restants.
                            // Via un launcher (pas un simple startActivity) : le résultat
                            // final de la chaîne (Étape 3, plus loin) doit pouvoir remonter
                            // jusqu'à OrganisateurActivity.constitutionLauncher.
                            Log.i("ConstitutionEquipesActivity", "➡️ ${equipes.size} équipes → choix du type")
                            val intent = Intent(this, CreationTournoiActivity::class.java).apply {
                                putExtra("ip_serveur", ipServeur)
                                putExtra("mode_online", modeOnline)
                                putParcelableArrayListExtra("equipes", ArrayList(equipes))
                                putParcelableArrayListExtra("joueurs_restants", ArrayList(joueursDispo))
                            }
                            creationTypeLauncher.launch(intent)
                        }
                    )
                }
            }
        }
    }
}
