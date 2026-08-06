// ACTIVITÉ : Création de tournoi — type, nombre de tables, équipes, paramètres
package app.resultatsbridge.organisateur

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.resultatsbridge.main.BaseActivity
import app.resultatsbridge.common.AppScaffold
import app.resultatsbridge.common.theme.BridgeServeurTheme
import app.resultatsbridge.common.theme.ChampTextStyle
import app.resultatsbridge.common.theme.champColors
import app.resultatsbridge.organisateur.data.DatabaseManager
import app.resultatsbridge.client.ClientNetworkUtils
import app.resultatsbridge.common.model.CreationTournoiResult
import app.resultatsbridge.common.model.DonneAvantTournoi
import app.resultatsbridge.common.model.Equipe
import app.resultatsbridge.common.model.Joueur
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.resultatsbridge.common.utils.getSecurePrefs

data class TypeTournoiInfo(
    val nomBrut: String,
    val tables: Int,
    val donnes: Int
) {
    val nomAffiche = nomBrut.substringBefore("_")

    // Pour Mitchell/Guéridon (tables==0) on affiche un texte générique
    // Pour les autres types on affiche le détail habituel
    val texteAffichage: String = when {
        tables != 0       -> "$nomAffiche : $tables tables, $donnes donnes"
        nomBrut == "MitchellGueridon" -> "Mitchell Guéridon : nombre d'équipes libre (pair)"
        else              -> "Mitchell : nombre d'équipes libre"
    }
}

// Étape 2 (05/08/2026, plan "Inverser l'ordre de création d'un tournoi") : le nombre
// d'équipes est maintenant connu AVANT le choix du type (constitué à l'étape précédente),
// donc on peut filtrer les types réellement compatibles au lieu de tous les proposer.
//   - Howell / par4equ2t21d (tables != 0) : préréglage fixe, exige une correspondance exacte.
//   - MitchellGueridon : exige un nombre de tables pair, donc un nombre d'équipes multiple
//     de 4 - règle vérifiée le 05/08/2026 contre une source primaire (voir DatabaseManager.kt,
//     positionChaineGueridon), jamais codée nulle part avant aujourd'hui.
//   - Mitchell (les autres) : simple parité, comme déjà contrôlé à la constitution d'équipes.
fun estCompatible(type: TypeTournoiInfo, nbreEquipes: Int): Boolean = when {
    type.tables != 0                   -> type.tables * 2 == nbreEquipes
    type.nomBrut == "MitchellGueridon" -> nbreEquipes % 4 == 0
    else                                -> nbreEquipes % 2 == 0
}

@OptIn(ExperimentalMaterial3Api::class)
class CreationTournoiActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ipServeur  = intent.getStringExtra("ip_serveur") ?: ""
        val modeOnline = intent.getBooleanExtra("mode_online", false)
        // Étape 2 (05/08/2026) : équipes déjà constituées, transmises par
        // ConstitutionEquipesActivity - le type de tournoi proposé ci-dessous est
        // filtré selon leur nombre (voir estCompatible). Valeurs initiales seulement -
        // deviennent un état Compose plus bas, car l'Étape 3 peut les faire évoluer
        // (suppression d'équipes) et revenir ici pour changer de type (06/08/2026).
        @Suppress("DEPRECATION")
        val equipesRecuesInitial = intent.getParcelableArrayListExtra<Equipe>("equipes") ?: arrayListOf()
        @Suppress("DEPRECATION")
        val joueursRestantsInitial = intent.getParcelableArrayListExtra<Joueur>("joueurs_restants") ?: arrayListOf()

        Log.i("CreationTournoiActivity", "🚀 Démarrage - modeOnline=$modeOnline, ${equipesRecuesInitial.size} équipes reçues")

        if (modeOnline) {
            ClientNetworkUtils.initialiserServeur(ipServeur)
        }

        setContent {
            BridgeServeurTheme {
                val scope = rememberCoroutineScope()
                val optionsTypes = remember { mutableStateOf<List<TypeTournoiInfo>>(emptyList()) }
                val isLoading = remember { mutableStateOf(true) }
                var selectedOption by remember { mutableStateOf<TypeTournoiInfo?>(null) }
                var expanded by remember { mutableStateOf(false) }

                // État (pas juste des val) : l'Étape 3 peut revenir ici avec une liste
                // d'équipes réduite (suppression), voir constitutionEtape3Launcher plus bas.
                var equipesRecues by remember { mutableStateOf(equipesRecuesInitial) }
                var joueursRestantsRecus by remember { mutableStateOf(joueursRestantsInitial) }
                val nbreEquipesConnu = equipesRecues.size

                // Étape 3 (05/08/2026) : lance ConstitutionEquipesActivity une 2ᵉ fois (revue
                // des équipes avec positions + enregistrement réel) une fois le tournoi créé.
                // 3 issues possibles au retour (06/08/2026) :
                //  - RESULT_OK : succès final, relayé tel quel vers l'appelant.
                //  - RESULT_FIRST_USER : équipes modifiées (suppression) mais type devenu
                //    incompatible - l'équipe demande à changer de type ; on met à jour
                //    equipesRecues/joueursRestantsRecus pour que le filtrage et un futur
                //    aller-retour reflètent la liste réduite, sans quitter cet écran.
                //  - RESULT_CANCELED (retour arrière simple) : rien à faire.
                val constitutionEtape3Launcher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    when (result.resultCode) {
                        Activity.RESULT_OK -> {
                            setResult(Activity.RESULT_OK, result.data)
                            finish()
                        }
                        Activity.RESULT_FIRST_USER -> {
                            @Suppress("DEPRECATION")
                            val equipesMaj = result.data?.getParcelableArrayListExtra<Equipe>("equipes")
                            @Suppress("DEPRECATION")
                            val joueursMaj = result.data?.getParcelableArrayListExtra<Joueur>("joueurs_restants")
                            if (equipesMaj != null) equipesRecues = equipesMaj
                            if (joueursMaj != null) joueursRestantsRecus = joueursMaj
                            Log.i("CreationTournoiActivity", "🔄 Retour Étape 3 : ${equipesMaj?.size} équipes mises à jour")
                        }
                        else -> {}
                    }
                }

                // Nombre de donnes par table saisi par l'organisateur (Mitchell uniquement)
                var nbreDonnesParTableSaisie by remember { mutableStateOf("4") }

                // Code arbitre (tournois locaux uniquement) : protège le bouton "Arbitre"
                // côté table. Mémorisé d'un tournoi à l'autre pour ne pas avoir à le
                // ressaisir à chaque création — laisser vide désactive la fonctionnalité.
                val securePrefs = remember { getSecurePrefs(this@CreationTournoiActivity) }
                var codeArbitreSaisi by remember {
                    mutableStateOf(securePrefs.getString("lastCodeArbitre", "") ?: "")
                }

                // Gestion du dialog d'avertissement (Howell uniquement)
                var messageAvertissement by remember { mutableStateOf<String?>(null) }
                var estBloque by remember { mutableStateOf(false) }
                var showDialog by remember { mutableStateOf(false) }

                // Résultat en attente de confirmation dans le dialog
                // Triple(idTournoi, nomBrut, nbEquipes)
                var resultatEnAttente by remember { mutableStateOf<Triple<Int, String, Int>?>(null) }

                var donnesPreparees by remember { mutableStateOf<List<DonneAvantTournoi>>(emptyList()) }

                // Launcher : revient de SaisieDonnesAvantTournoi → recharge les donnes
                val donnesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    scope.launch {
                        donnesPreparees = withContext(Dispatchers.IO) {
                            if (modeOnline) ClientNetworkUtils.getMainsAvantTournoi()
                            else DatabaseManager.getMainsAvantTournoi(this@CreationTournoiActivity)
                        }
                    }
                }

                // Donnes préparées : chargement immédiat, indépendant du reste
                LaunchedEffect("donnes") {
                    donnesPreparees = withContext(Dispatchers.IO) {
                        if (modeOnline) ClientNetworkUtils.getMainsAvantTournoi()
                        else DatabaseManager.getMainsAvantTournoi(this@CreationTournoiActivity)
                    }
                }

                // Chargement des types de tournoi
                LaunchedEffect("types") {
                    isLoading.value = true
                    val liste = if (modeOnline) {
                        withContext(Dispatchers.IO) {
                            ClientNetworkUtils.getListeTypesTournoi().map {
                                TypeTournoiInfo(it.first, it.second, it.third)
                            }
                        }
                    } else {
                        DatabaseManager.getListeTypesTournoi(this@CreationTournoiActivity).map {
                            TypeTournoiInfo(it.first, it.second, it.third)
                        }
                    }
                    optionsTypes.value = liste
                    // Étape 2 : ne proposer que les types compatibles avec le nombre
                    // d'équipes déjà constitué (voir estCompatible ci-dessus).
                    selectedOption = liste.firstOrNull { estCompatible(it, nbreEquipesConnu) }
                    isLoading.value = false
                }

                // 06/08/2026 : re-sélectionne un type valide quand on revient de l'Étape 3
                // avec une liste d'équipes modifiée (nbreEquipesConnu a changé) - sans
                // recharger la liste des types elle-même (déjà en cache, pas besoin de
                // refaire l'appel réseau/BD ci-dessus).
                LaunchedEffect(nbreEquipesConnu) {
                    if (optionsTypes.value.isNotEmpty()) {
                        selectedOption = optionsTypes.value.firstOrNull { estCompatible(it, nbreEquipesConnu) }
                    }
                }

                // Dialog d'avertissement (utilisé pour Howell, pas Mitchell)
                if (showDialog && messageAvertissement != null) {
                    AlertDialog(
                        onDismissRequest = { },
                        title = {
                            Text(if (estBloque) "🚫 Limite atteinte" else "⚠️ Avertissement")
                        },
                        text = {
                            Text(
                                messageAvertissement!!,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                showDialog = false
                                if (!estBloque) {
                                    val r = resultatEnAttente
                                    if (r != null) {
                                        // Étape 3 : lance la revue des équipes (positions +
                                        // enregistrement réel), le tournoi vient d'être créé.
                                        val intentEtape3 = Intent(this@CreationTournoiActivity, ConstitutionEquipesActivity::class.java).apply {
                                            putExtra("id_tournoi", r.first)
                                            putExtra("type_tournoi", r.second)
                                            putExtra("nombre_equipes", r.third)
                                            putExtra("mode_online", modeOnline)
                                            putExtra("ip_serveur", ipServeur)
                                            putParcelableArrayListExtra("equipes", ArrayList(equipesRecues))
                                            putParcelableArrayListExtra("joueurs_restants", ArrayList(joueursRestantsRecus))
                                        }
                                        constitutionEtape3Launcher.launch(intentEtape3)
                                    }
                                }
                            }) {
                                Text("OK")
                            }
                        }
                    )
                }

                AppScaffold(
                    ipAddress = ipServeur,
                    numeroTournoi = null,
                    equipeChoisie = null,
                    nomEcran = "Création de tournoi"
                ) {
                    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Spacer(modifier = Modifier.height(20.dp))

                        // Donnes préparées — toujours visible en haut, indépendant du chargement
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (donnesPreparees.isNotEmpty()) Color(0xFFFFF3E0) else Color(0xFFF5F5F5)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                if (donnesPreparees.isNotEmpty()) {
                                    Text(
                                        "⚠️ ${donnesPreparees.size} donne(s) préparée(s) — seront utilisées au démarrage",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color(0xFFE65100)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        donnesPreparees.map { it.numeroDonne }.sorted()
                                            .joinToString("  ") { "N°$it" },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                val ok = withContext(Dispatchers.IO) {
                                                    if (modeOnline) ClientNetworkUtils.supprimerMainsAvantTournoi()
                                                    else DatabaseManager.supprimerMainsAvantTournoi(this@CreationTournoiActivity)
                                                }
                                                if (ok) donnesPreparees = emptyList()
                                            }
                                        },
                                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB71C1C))
                                    ) {
                                        Text("🗑 Supprimer les donnes préparées")
                                    }
                                } else {
                                    Text(
                                        "Aucune donne préparée",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            "Choisissez le type de tournoi",
                            style = MaterialTheme.typography.headlineSmall
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Étape 2 : liste réellement affichée, filtrée selon le nombre
                        // d'équipes déjà constitué.
                        val optionsFiltrees = optionsTypes.value.filter { estCompatible(it, nbreEquipesConnu) }

                        if (isLoading.value) {
                            CircularProgressIndicator()
                            Text("Chargement des types de tournoi...")
                        } else if (optionsTypes.value.isEmpty()) {
                            Text(
                                "❌ Aucun type de tournoi disponible",
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (optionsFiltrees.isEmpty()) {
                            Text(
                                "❌ Aucun type de tournoi ne correspond à $nbreEquipesConnu équipes.\n" +
                                        "Revenez à la constitution des équipes pour ajuster le nombre.",
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {

                            // Dropdown de sélection du type de tournoi
                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = !expanded }
                            ) {
                                OutlinedTextField(
                                    value = selectedOption?.texteAffichage
                                        ?: "Aucun type disponible",
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                    },
                                    textStyle = ChampTextStyle,
                                    colors = champColors(),
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    optionsFiltrees.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.texteAffichage) },
                                            onClick = { selectedOption = option; expanded = false },
                                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                        )
                                    }
                                }
                            }

                            // Paramètres supplémentaires pour Mitchell uniquement
                            // tables == 0 est notre convention pour "dynamique"
                            if (selectedOption?.tables == 0) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            "⚙️ Paramètres Mitchell",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "Nombre de donnes par table :",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        OutlinedTextField(
                                            value = nbreDonnesParTableSaisie,
                                            onValueChange = {
                                                // On n'accepte que les chiffres
                                                nbreDonnesParTableSaisie =
                                                    it.filter { c -> c.isDigit() }
                                            },
                                            label = { Text("Donnes par table (ex: 4)", fontWeight = FontWeight.Bold) },
                                            textStyle = ChampTextStyle,
                                            colors = champColors(),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "ℹ️ Le nombre de tables et les mouvements seront calculés " +
                                                    "automatiquement après la constitution des équipes.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }

                            if (!modeOnline) {
                                Spacer(modifier = Modifier.height(16.dp))
                                OutlinedTextField(
                                    value = codeArbitreSaisi,
                                    onValueChange = {
                                        codeArbitreSaisi = it
                                        securePrefs.edit().putString("lastCodeArbitre", it).apply()
                                    },
                                    label = { Text("Code arbitre") },
                                    textStyle = ChampTextStyle,
                                    colors = champColors(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(this@CreationTournoiActivity, SaisieDonnesAvantTournoiActivity::class.java).apply {
                                        putExtra("ip_serveur", ipServeur)
                                        putExtra("mode_online", modeOnline)
                                    }
                                    donnesLauncher.launch(intent)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("📷 Préparer les donnes avant tournoi")
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    val current = selectedOption ?: return@Button

                                    // ── CAS MITCHELL ──────────────────────────────────────────
                                    if (current.tables == 0) {
                                        val nbreDonnes = nbreDonnesParTableSaisie.toIntOrNull() ?: 0
                                        if (nbreDonnes <= 0) {
                                            Toast.makeText(
                                                this@CreationTournoiActivity,
                                                "❌ Saisissez un nombre de donnes valide",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            return@Button
                                        }
                                        scope.launch {
                                            // Création avec valeurs provisoires.
                                            // nbreEquipes et nbreEnregistrement seront mis à jour
                                            // dans ConstitutionEquipesScreen après constitution des équipes.
                                            val resultat = if (modeOnline) {
                                                withContext(Dispatchers.IO) {
                                                    ClientNetworkUtils.creerTournoi(
                                                        type = current.nomBrut,
                                                        nbreEquipes = 0,
                                                        nbreDonnes = nbreDonnes,
                                                        nbreEnregistrement = 0
                                                    )
                                                }
                                            } else {
                                                val id =
                                                    if (current.nomBrut == "MitchellGueridon") {
                                                        DatabaseManager.creerTournoiMitchellGueridon(
                                                            context = this@CreationTournoiActivity,
                                                            nbreDonnesParTable = nbreDonnes,
                                                            codeArbitre = codeArbitreSaisi
                                                        ).toInt()
                                                    } else {
                                                        DatabaseManager.creerTournoiMitchell(
                                                            context = this@CreationTournoiActivity,
                                                            nbreDonnesParTable = nbreDonnes,
                                                            codeArbitre = codeArbitreSaisi
                                                        ).toInt()
                                                    }
                                                CreationTournoiResult(id, null)
                                            }

                                            if (resultat.idTournoi > 0) {
                                                Log.i(
                                                    "CreationTournoiActivity",
                                                    "✅ Tournoi Mitchell créé ID=${resultat.idTournoi}"
                                                )
                                                // Étape 3 : lance la revue des équipes (positions +
                                                // enregistrement réel), le tournoi vient d'être créé.
                                                val intentEtape3 = Intent(this@CreationTournoiActivity, ConstitutionEquipesActivity::class.java).apply {
                                                    putExtra("id_tournoi", resultat.idTournoi)
                                                    putExtra("type_tournoi", current.nomBrut)
                                                    // -1 = signal Mitchell : pas de limite d'équipes
                                                    putExtra("nombre_equipes", -1)
                                                    putExtra("nbre_donnes_par_table", nbreDonnes)
                                                    putExtra("mode_online", modeOnline)
                                                    putExtra("ip_serveur", ipServeur)
                                                    putParcelableArrayListExtra("equipes", ArrayList(equipesRecues))
                                                    putParcelableArrayListExtra("joueurs_restants", ArrayList(joueursRestantsRecus))
                                                }
                                                constitutionEtape3Launcher.launch(intentEtape3)
                                            } else if (resultat.avertissement != null) {
                                                // Bloqué ou avertissement → afficher le message dans un dialog
                                                messageAvertissement = resultat.avertissement
                                                estBloque = resultat.idTournoi <= 0
                                                showDialog = true
                                            } else {
                                                Toast.makeText(
                                                    this@CreationTournoiActivity,
                                                    "❌ Erreur lors de la création du tournoi Mitchell",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }

                                        // ── CAS HOWELL : comportement existant inchangé ────────────
                                    } else {
                                        val nbEquipes = current.tables * 2
                                        scope.launch {
                                            val resultat = if (modeOnline) {
                                                withContext(Dispatchers.IO) {
                                                    ClientNetworkUtils.creerTournoi(
                                                        type = current.nomBrut,
                                                        nbreEquipes = nbEquipes,
                                                        nbreDonnes = current.donnes,
                                                        nbreEnregistrement = current.tables * current.donnes
                                                    )
                                                }
                                            } else {
                                                val id = DatabaseManager.creerTournoi(
                                                    context = this@CreationTournoiActivity,
                                                    type = current.nomBrut,
                                                    nbreEquipes = nbEquipes,
                                                    nbreDonnes = current.donnes,
                                                    nbreEnregistrement = current.tables * current.donnes,
                                                    codeArbitre = codeArbitreSaisi
                                                ).toInt()
                                                CreationTournoiResult(id, null)
                                            }

                                            when {
                                                resultat.idTournoi <= 0 && resultat.avertissement != null -> {
                                                    messageAvertissement = resultat.avertissement
                                                    estBloque = true
                                                    showDialog = true
                                                    Log.w(
                                                        "CreationTournoiActivity",
                                                        "🚫 Limite dépassée"
                                                    )
                                                }

                                                resultat.idTournoi > 0 && resultat.avertissement != null -> {
                                                    messageAvertissement = resultat.avertissement
                                                    estBloque = false
                                                    resultatEnAttente = Triple(
                                                        resultat.idTournoi,
                                                        current.nomBrut,
                                                        nbEquipes
                                                    )
                                                    showDialog = true
                                                    Log.i(
                                                        "CreationTournoiActivity",
                                                        "⚠️ Tournoi créé avec avertissement ID=${resultat.idTournoi}"
                                                    )
                                                }

                                                resultat.idTournoi > 0 -> {
                                                    Log.i(
                                                        "CreationTournoiActivity",
                                                        "✅ Tournoi Howell créé ID=${resultat.idTournoi}"
                                                    )
                                                    // Étape 3 : lance la revue des équipes
                                                    // (positions + enregistrement réel).
                                                    val intentEtape3 = Intent(this@CreationTournoiActivity, ConstitutionEquipesActivity::class.java).apply {
                                                        putExtra("id_tournoi", resultat.idTournoi)
                                                        putExtra("type_tournoi", current.nomBrut)
                                                        putExtra("nombre_equipes", nbEquipes)
                                                        putExtra("mode_online", modeOnline)
                                                        putExtra("ip_serveur", ipServeur)
                                                        putParcelableArrayListExtra("equipes", ArrayList(equipesRecues))
                                                        putParcelableArrayListExtra("joueurs_restants", ArrayList(joueursRestantsRecus))
                                                    }
                                                    constitutionEtape3Launcher.launch(intentEtape3)
                                                }

                                                else -> {
                                                    Log.e(
                                                        "CreationTournoiActivity",
                                                        "❌ Échec création"
                                                    )
                                                    Toast.makeText(
                                                        this@CreationTournoiActivity,
                                                        "❌ Erreur lors de la création du tournoi",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = selectedOption != null
                            ) {
                                Text("Démarrer le tournoi")
                            }

                        }
                    }
                }
            }
        }
    }
}
