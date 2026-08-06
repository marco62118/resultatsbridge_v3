// ÉCRAN : Constitution des équipes — saisie des joueurs par équipe, import depuis serveur
package app.resultatsbridge.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import app.resultatsbridge.common.theme.ChampTextStyle
import app.resultatsbridge.common.theme.champColors
import androidx.compose.ui.unit.dp
import app.resultatsbridge.client.ClientNetworkUtils
import app.resultatsbridge.common.utils.getSecurePrefs
import app.resultatsbridge.common.utils.positionMitchell
import app.resultatsbridge.organisateur.data.DatabaseManager
import app.resultatsbridge.common.model.Equipe
import app.resultatsbridge.common.model.TournoiConfig
import app.resultatsbridge.common.model.Joueur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran de constitution des équipes — réutilisé aux Étapes 1 ET 3 du flux de
 * création (05/08/2026, plan "Inverser l'ordre de création d'un tournoi").
 *
 * **Étape 1** (`idTournoi == -1`) : lancée directement depuis
 * OrganisateurActivity, AVANT tout choix de type de tournoi — aucune ligne
 * `tournois` n'existe encore en base, `nombreEquipes`/`typeTournoi` ne sont
 * pas connus. Ne fait que construire la liste d'équipes en mémoire (nombre
 * pair contrôlé), le bouton "Continuer →" transmet cette liste à l'écran de
 * choix du type (`CreationTournoiActivity`).
 *
 * **Étape 3** (`idTournoi > 0`) : relancée depuis `CreationTournoiActivity`
 * une fois le type choisi et la ligne `tournois` réellement créée — affiche
 * la liste `equipesInitiales` (déjà construite à l'Étape 1) avec ses
 * positions NS/EO/table, encore modifiable, et "Enregistrer et Démarrer" fait
 * l'enregistrement réel en base.
 *
 * [idTournoi]          -1 (Étape 1, aucun tournoi créé) ou l'ID réel (Étape 3).
 * [joueursInitiaux]    Pool de joueurs pour l'Étape 1 (tous les joueurs du club).
 * [joueursRestants]    Pool de joueurs pour l'Étape 3 (ceux pas encore utilisés
 *                      à la fin de l'Étape 1) — permet de "remplacer un joueur"
 *                      d'une équipe déjà constituée.
 * [equipesInitiales]   Liste d'équipes déjà construite (Étape 3 seulement).
 * [nombreEquipes]      -1 pour Mitchell/Guéridon, nombre réel pour Howell.
 * [modeOnline]         true = serveur distant alwaysdata, false = serveur embarqué NanoHTTPD.
 * [nbreDonnesParTable] Mitchell/Guéridon uniquement (0 sinon).
 * [typeTournoi]        Connu seulement à l'Étape 3 (type déjà choisi).
 */
@Composable
fun ConstitutionEquipesScreen(
    idTournoi: Int,
    joueursInitiaux: List<Joueur>,
    nombreEquipes: Int,
    modeOnline: Boolean = false,
    nbreDonnesParTable: Int = 0,
    typeTournoi: String = "",
    joueursRestants: List<Joueur> = emptyList(),
    equipesInitiales: List<Equipe> = emptyList(),
    // Étape 2 (05/08/2026) : appelé par le bouton "Continuer →" avec la liste
    // d'équipes déjà construite ET le pool de joueurs pas encore utilisés
    // (nécessaire pour "remplacer un joueur" à l'Étape 3), pour lancer le
    // choix du type de tournoi. Ne sert qu'à l'Étape 1 (idTournoi == -1).
    onEquipesValidees: (List<Equipe>, List<Joueur>) -> Unit = { _, _ -> }
) {
    Log.i("ConstitutionEquipes", "👥 ${joueursInitiaux.size} joueurs disponibles")
    val context  = LocalContext.current
    val activity = context as? Activity
    val scope    = rememberCoroutineScope()

    // Étape 3 : le tournoi existe déjà (ligne `tournois` créée par
    // CreationTournoiActivity) — c'est ce qui distingue les 2 réutilisations
    // de cet écran, pas idTournoi seul (qui vaut -1 puis un vrai ID).
    val estEtape3 = idTournoi > 0

    val estMitchell  = nombreEquipes == -1
    val estGueridon  = estMitchell && typeTournoi == "MitchellGueridon"

    var joueursDispo = remember {
        mutableStateListOf<Joueur>().apply {
            val pool = if (estEtape3) joueursRestants else joueursInitiaux
            addAll(pool.sortedBy { it.prenom.lowercase() })
        }
    }
    var joueur1         by remember { mutableStateOf<Joueur?>(null) }
    var joueur2         by remember { mutableStateOf<Joueur?>(null) }
    var equipes         by remember { mutableStateOf(equipesInitiales) }
    // Numéro de la prochaine équipe à créer : le plus petit numéro pas encore utilisé.
    // Réutilise automatiquement un numéro libéré par une suppression, sans jamais
    // renuméroter les équipes déjà constituées (elles gardent leur table/position).
    val numeroEquipe by remember {
        derivedStateOf {
            val utilises = equipes.map { it.equipeNumero }.toSet()
            var n = 1
            while (utilises.contains(n)) n++
            n
        }
    }
    // Étape 3 : va directement à la revue (équipes déjà constituées à l'Étape 1),
    // pas besoin de recliquer "Valider".
    var afficherEquipes by remember { mutableStateOf(estEtape3 && equipesInitiales.isNotEmpty()) }
    var nouveauNom      by remember { mutableStateOf("") }
    var nouveauPrenom   by remember { mutableStateOf("") }
    var messageParite   by remember { mutableStateOf<String?>(null) }
    var importEnCours   by remember { mutableStateOf(false) }
    var msgImport       by remember { mutableStateOf<String?>(null) }

    // Enregistrement d'un visiteur/touriste sur le site (voir bouton "Ajouter à l'équipe"
    // plus bas) : si pas d'Internet au moment de l'ajout, on garde le nom/prénom en
    // attente pour pouvoir réessayer depuis la boîte de dialogue une fois le réseau rétabli.
    var showDialogPasInternetVisiteur by remember { mutableStateOf(false) }
    var visiteurNomEnAttente by remember { mutableStateOf("") }
    var visiteurPrenomEnAttente by remember { mutableStateOf("") }

    // Import d'un vrai fichier .bws fourni par FFBClubNet (Mitchell uniquement,
    // mode local) : remplace le gabarit de test généré automatiquement par
    // BwsTestGenerator, pour qu'un tournoi joué sur V3 produise un fichier
    // réellement renvoyable à FFBClubNet. Voir BwsPaths.importerBwsExterne.
    var bwsImporteUri by remember { mutableStateOf<Uri?>(null) }
    var bwsImporteNom by remember { mutableStateOf<String?>(null) }
    val bwsPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        bwsImporteUri = uri
        bwsImporteNom = uri?.let { u ->
            var nom: String? = null
            context.contentResolver.query(u, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) nom = cursor.getString(idx)
            }
            nom ?: u.lastPathSegment
        }
        Log.i("ConstitutionEquipes", "📥 Fichier .bws sélectionné : $bwsImporteNom")
    }

    // Tente d'enregistrer durablement un visiteur sur le site (en plus de l'ajout local,
    // qui a toujours lieu immédiatement et fait fonctionner le tournoi même sans réseau).
    // Si pas d'accès Internet à cet instant, ouvre une boîte de dialogue au lieu d'échouer
    // silencieusement — appelée à la fois juste après l'ajout et depuis le bouton "Réessayer".
    val tenterEnregistrementVisiteur: suspend (String, String) -> Unit = { nom, prenom ->
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            visiteurNomEnAttente = nom
            visiteurPrenomEnAttente = prenom
            showDialogPasInternetVisiteur = true
        } else {
            val token = getSecurePrefs(context).getString("tokenJoueur", "") ?: ""
            val visiteur = withContext(Dispatchers.IO) {
                ClientNetworkUtils.ajouterVisiteurSurSite(nom = nom, prenom = prenom, tokenJoueur = token)
            }
            if (visiteur != null) {
                showDialogPasInternetVisiteur = false
                Toast.makeText(context, "✅ $prenom $nom aussi enregistré sur le site", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "⚠️ Échec de l'enregistrement de $prenom $nom sur le site (gardé en local pour ce tournoi)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var equipeASupprimer by remember { mutableStateOf<Equipe?>(null) }
    // 06/08/2026 : après une suppression qui laisse un nombre d'équipes impair,
    // demande explicitement quoi faire au lieu de renvoyer directement vers l'écran
    // d'ajout d'une équipe — signalé par Marco : supprimer une 2e équipe (pour
    // revenir à un nombre pair) est aussi légitime qu'en ajouter une nouvelle.
    var demanderActionApresSuppression by remember { mutableStateOf(false) }

    // Supprime une équipe déjà constituée : ses 2 joueurs retournent dans le pool,
    // les autres équipes ne bougent pas (leur numéro est conservé). Le numéro libéré
    // sera repris automatiquement par la prochaine équipe créée (cf. numeroEquipe ci-dessus).
    val supprimerEquipe: (Equipe) -> Unit = { equipe ->
        joueursDispo.add(equipe.joueur1)
        joueursDispo.add(equipe.joueur2)
        val sortedList = joueursDispo.sortedBy { it.prenom.lowercase() }
        joueursDispo.clear()
        joueursDispo.addAll(sortedList)

        val restantes = equipes.filter { it.equipeNumero != equipe.equipeNumero }

        messageParite = null
        if (restantes.size % 2 != 0) {
            // Nombre impair après suppression : on demande quoi faire au lieu de
            // forcer directement l'ajout d'une équipe (voir le dialog plus bas).
            equipes = restantes
            demanderActionApresSuppression = true
        } else {
            // Redevenu pair (ex. 2e suppression d'affilée) : à l'Étape 3 uniquement,
            // renumérote 1..N (garde l'ordre relatif actuel) - demande explicite de
            // Marco le 06/08/2026, sinon les positions NS/EO/table calculées à partir
            // de numéros avec des trous seraient fausses. Pas fait à l'Étape 1 : la
            // règle "ne jamais renuméroter" reste pertinente là-bas, où une équipe
            // supprimée est toujours censée être remplacée par une nouvelle (les
            // flèches monter/descendre suffisent pour réorganiser sans supprimer).
            equipes = if (estEtape3) {
                restantes.sortedBy { it.equipeNumero }
                    .mapIndexed { index, e -> e.copy(equipeNumero = index + 1) }
            } else {
                restantes
            }
            afficherEquipes = true
        }
        Log.i("ConstitutionEquipes", "🗑️ Équipe ${equipe.equipeNumero} supprimée, joueurs remis dans le pool, numéro libéré")
    }

    // Remplace un seul joueur (1 ou 2) d'une équipe déjà constituée : le joueur gardé
    // est préchargé dans le panneau de sélection, l'autre retourne dans le pool.
    // L'équipe garde son numéro (même mécanisme de réutilisation que supprimerEquipe).
    val remplacerJoueur: (Equipe, Int) -> Unit = { equipe, numJoueur ->
        val joueurGarde    = if (numJoueur == 1) equipe.joueur2 else equipe.joueur1
        val joueurRemplace = if (numJoueur == 1) equipe.joueur1 else equipe.joueur2

        joueursDispo.add(joueurRemplace)
        val sortedList = joueursDispo.sortedBy { it.prenom.lowercase() }
        joueursDispo.clear()
        joueursDispo.addAll(sortedList)

        equipes = equipes.filter { it.equipeNumero != equipe.equipeNumero }

        joueur1         = joueurGarde
        joueur2         = null
        messageParite   = null
        afficherEquipes = false
        Log.i("ConstitutionEquipes", "✏️ Équipe ${equipe.equipeNumero} : remplacement de ${joueurRemplace.prenom}")
    }

    // Intervertit les numéros de 2 équipes (utilisé par les flèches ▲▼ du récapitulatif) :
    // ni les joueurs ni le pool ne bougent, seuls les 2 numéros — et donc les
    // positions/tables Mitchell qui en découlent — sont échangés.
    val intervertirEquipes: (Equipe, Equipe) -> Unit = { a, b ->
        equipes = equipes.map {
            when (it.equipeNumero) {
                a.equipeNumero -> it.copy(equipeNumero = b.equipeNumero)
                b.equipeNumero -> it.copy(equipeNumero = a.equipeNumero)
                else           -> it
            }
        }.sortedBy { it.equipeNumero }
        Log.i("ConstitutionEquipes", "🔀 Équipes ${a.equipeNumero} et ${b.equipeNumero} interverties")
    }

    equipeASupprimer?.let { equipe ->
        AlertDialog(
            onDismissRequest = { equipeASupprimer = null },
            title   = { Text("Supprimer l'équipe n°${equipe.equipeNumero} ?") },
            text    = {
                Text(
                    "${equipe.joueur1.prenom} ${equipe.joueur1.nom} et " +
                            "${equipe.joueur2.prenom} ${equipe.joueur2.nom} " +
                            "retourneront dans la liste des joueurs disponibles."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    supprimerEquipe(equipe)
                    equipeASupprimer = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { equipeASupprimer = null }) { Text("Annuler") }
            }
        )
    }

    // 06/08/2026 : après une suppression laissant un nombre impair d'équipes, demande
    // explicitement quoi faire plutôt que de renvoyer directement vers l'écran d'ajout
    // (supprimer une 2e équipe pour revenir à un nombre pair est tout aussi légitime).
    if (demanderActionApresSuppression) {
        AlertDialog(
            onDismissRequest = { demanderActionApresSuppression = false; afficherEquipes = true },
            title   = { Text("Nombre d'équipes impair (${equipes.size})") },
            text    = { Text("Voulez-vous supprimer une autre équipe, ou en ajouter une nouvelle ?") },
            confirmButton = {
                TextButton(onClick = {
                    demanderActionApresSuppression = false
                    afficherEquipes = false
                }) { Text("Ajouter une équipe") }
            },
            dismissButton = {
                TextButton(onClick = {
                    demanderActionApresSuppression = false
                    afficherEquipes = true
                }) { Text("Supprimer une autre équipe") }
            }
        )
    }

    if (showDialogPasInternetVisiteur) {
        AlertDialog(
            onDismissRequest = { showDialogPasInternetVisiteur = false },
            title   = { Text("Pas de connexion Internet") },
            text    = {
                Column {
                    Text(
                        "Impossible d'enregistrer $visiteurPrenomEnAttente $visiteurNomEnAttente " +
                                "sur le site pour l'instant : pas d'accès Internet. Le joueur reste " +
                                "disponible pour ce tournoi, seul l'enregistrement durable sur le site " +
                                "a échoué."
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Ouvrir les paramètres Wi-Fi") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        tenterEnregistrementVisiteur(visiteurNomEnAttente, visiteurPrenomEnAttente)
                    }
                }) { Text("Réessayer") }
            },
            dismissButton = {
                TextButton(onClick = { showDialogPasInternetVisiteur = false }) { Text("Annuler") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(vertical = 16.dp)) {

        if (estMitchell) {
            Text(
                "Constitution des équipes",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Équipes constituées : ${equipes.size}" +
                        when {
                            equipes.isEmpty()     -> ""
                            equipes.size % 2 == 0 -> " ✅ nombre pair"
                            else                  -> " ⚠️ nombre impair"
                        },
                style = MaterialTheme.typography.bodyMedium,
                color = if (equipes.isNotEmpty() && equipes.size % 2 != 0)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurface
            )
        } else {
            Text(
                "Constitution des $nombreEquipes équipes du tournoi $idTournoi",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(Modifier.height(16.dp))

        if (!afficherEquipes) {
            Row(modifier = Modifier.fillMaxWidth()) {

                LazyColumn(
                    modifier = Modifier
                        .weight(1.4f)
                        .fillMaxHeight()
                        .padding(end = 4.dp)
                ) {
                    items(joueursDispo) { joueur ->
                        JoueurCard(
                            joueur  = joueur,
                            onClick = {
                                if (joueur1 == null) {
                                    joueur1 = joueur
                                    joueursDispo.remove(joueur)
                                    Log.i("ConstitutionEquipes", "👤 ${joueur.prenom} assigné à J1")
                                } else if (joueur2 == null) {
                                    joueur2 = joueur
                                    joueursDispo.remove(joueur)
                                    Log.i("ConstitutionEquipes", "👤 ${joueur.prenom} assigné à J2")
                                }
                            }
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Text("Équipe n°$numeroEquipe", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    joueur1?.let { j1 ->
                        JoueurCard(
                            joueur  = j1,
                            onClick = {
                                joueursDispo.add(j1)
                                val sortedList = joueursDispo.sortedBy { it.prenom.lowercase() }
                                joueursDispo.clear()
                                joueursDispo.addAll(sortedList)
                                joueur1 = null
                                Log.i("ConstitutionEquipes", "🔄 ${j1.prenom} remis dans la liste")
                            }
                        )
                    }

                    joueur2?.let { j2 ->
                        JoueurCard(
                            joueur  = j2,
                            onClick = {
                                joueursDispo.add(j2)
                                val sortedList = joueursDispo.sortedBy { it.prenom.lowercase() }
                                joueursDispo.clear()
                                joueursDispo.addAll(sortedList)
                                joueur2 = null
                                Log.i("ConstitutionEquipes", "🔄 ${j2.prenom} remis dans la liste")
                            }
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (joueur1 != null && joueur2 != null) {
                                val equipe = Equipe(
                                    equipeNumero = numeroEquipe,
                                    joueur1 = joueur1!!,
                                    joueur2 = joueur2!!,
                                    idTournoi = idTournoi
                                )
                                equipes       = (equipes + equipe).sortedBy { it.equipeNumero }
                                messageParite = null
                                Log.i("ConstitutionEquipes", "✅ Équipe $numeroEquipe validée")
                                joueur1      = null
                                joueur2      = null
                                if (!estMitchell && equipes.size == nombreEquipes) {
                                    afficherEquipes = true
                                    Log.i("ConstitutionEquipes", "🎯 Fin constitution Howell")
                                }
                            }
                        },
                        enabled  = (joueur1 != null && joueur2 != null),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Valider équipe")
                    }

                    if (estMitchell && equipes.size >= 2) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val numerosManquants = (1..equipes.size).filter { n ->
                                    equipes.none { it.equipeNumero == n }
                                }
                                if (equipes.size % 2 != 0) {
                                    messageParite =
                                        "⚠️ Nombre d'équipes impair (${equipes.size}).\n" +
                                                "Ajoutez une équipe Relais pour avoir un nombre pair."
                                    Log.w("ConstitutionEquipes", "⚠️ Nombre impair : ${equipes.size} équipes")
                                } else if (numerosManquants.isNotEmpty()) {
                                    messageParite =
                                        "⚠️ Numéro(s) d'équipe manquant(s) : ${numerosManquants.joinToString(", ")}.\n" +
                                                "Complétez ces équipes avant de continuer."
                                    Log.w("ConstitutionEquipes", "⚠️ Numéros manquants : $numerosManquants")
                                } else {
                                    messageParite   = null
                                    afficherEquipes = true
                                    Log.i("ConstitutionEquipes", "✅ Validation Mitchell : ${equipes.size} équipes → ${equipes.size / 2} tables")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            Text("✅ Valider le nombre d'équipes")
                        }

                        messageParite?.let { msg ->
                            Spacer(Modifier.height(8.dp))
                            Card(
                                colors   = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text     = msg,
                                    modifier = Modifier.padding(12.dp),
                                    color    = MaterialTheme.colorScheme.onErrorContainer,
                                    style    = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    Text("Nouveau joueur :", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value         = nouveauPrenom,
                        onValueChange = { nouveauPrenom = it },
                        label         = { Text("Prénom", fontWeight = FontWeight.Bold) },
                        textStyle     = ChampTextStyle,
                        colors        = champColors(),
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value         = nouveauNom,
                        onValueChange = { nouveauNom = it },
                        label         = { Text("Nom", fontWeight = FontWeight.Bold) },
                        textStyle     = ChampTextStyle,
                        colors        = champColors(),
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick  = {
                            if (nouveauNom.isNotBlank() && nouveauPrenom.isNotBlank()) {
                                if (joueur1 != null && joueur2 != null) {
                                    Toast.makeText(context, "L'équipe est déjà pleine !", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                scope.launch {
                                    val nomSaisi    = nouveauNom.trim()
                                    val prenomSaisi = nouveauPrenom.trim()
                                    val joueurCree = if (modeOnline) {
                                        withContext(Dispatchers.IO) {
                                            ClientNetworkUtils.ajouterNouveauJoueur(
                                                nom    = nomSaisi,
                                                prenom = prenomSaisi
                                            )
                                        }
                                    } else {
                                        DatabaseManager.ajouterNouveauJoueur(
                                            context = context,
                                            nom     = nomSaisi,
                                            prenom  = prenomSaisi
                                        )
                                    }
                                    if (joueurCree != null) {
                                        Log.i("ConstitutionEquipes", "💾 Joueur ${joueurCree.prenom} créé et assigné")
                                        if (joueur1 == null) joueur1 = joueurCree else joueur2 = joueurCree
                                        nouveauNom    = ""
                                        nouveauPrenom = ""
                                        // Enregistrement durable sur le site (visiteur ajouté à la
                                        // volée) : uniquement en mode local, le mode online l'a déjà
                                        // fait ci-dessus via ajouterNouveauJoueur.
                                        if (!modeOnline) {
                                            tenterEnregistrementVisiteur(nomSaisi, prenomSaisi)
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Ajouter à l'équipe")
                    }

                    if (!modeOnline) {
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                // Garde synchrone : un double-clic rapide peut déclencher 2 clics
                                // avant que le bouton n'ait le temps de se désactiver à l'écran.
                                // Sans ce test avant le scope.launch, les 2 imports partiraient en
                                // parallèle et dupliqueraient tous les joueurs importés.
                                if (importEnCours) return@Button
                                importEnCours = true
                                scope.launch {
                                    msgImport = null
                                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                                    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                                    if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
                                        msgImport = "⚠️ Pas de connexion internet — activez les données mobiles le temps d'importer les joueurs"
                                        importEnCours = false
                                        return@launch
                                    }
                                    val token = getSecurePrefs(context).getString("tokenJoueur", "") ?: ""
                                    if (token.isBlank()) {
                                        msgImport = "❌ Non connecté au site — lancez l'app et connectez-vous d'abord"
                                        importEnCours = false
                                        return@launch
                                    }
                                    val joueursFetches = withContext(Dispatchers.IO) {
                                        ClientNetworkUtils.importerJoueursDepuisServeurEnLigne(token)
                                    }
                                    if (joueursFetches.isEmpty()) {
                                        msgImport = "❌ Impossible de contacter le serveur"
                                        importEnCours = false
                                        return@launch
                                    }
                                    var nbAjoutes = 0
                                    joueursFetches.forEach { joueur ->
                                        val dejaPresent = joueursDispo.any {
                                            it.nom.uppercase() == joueur.nom.uppercase() &&
                                            it.prenom.uppercase() == joueur.prenom.uppercase()
                                        } || equipes.any { e ->
                                            (e.joueur1.nom.uppercase() == joueur.nom.uppercase() && e.joueur1.prenom.uppercase() == joueur.prenom.uppercase()) ||
                                            (e.joueur2.nom.uppercase() == joueur.nom.uppercase() && e.joueur2.prenom.uppercase() == joueur.prenom.uppercase())
                                        }
                                        if (!dejaPresent) {
                                            val cree = DatabaseManager.ajouterNouveauJoueur(context, joueur.nom, joueur.prenom)
                                            if (cree != null) { joueursDispo.add(cree); nbAjoutes++ }
                                        }
                                    }
                                    val sortedList = joueursDispo.sortedBy { it.prenom.lowercase() }
                                    joueursDispo.clear()
                                    joueursDispo.addAll(sortedList)
                                    msgImport = "✅ $nbAjoutes joueur(s) importé(s) depuis le serveur"
                                    importEnCours = false
                                }
                            },
                            enabled  = !importEnCours,
                            modifier = Modifier.fillMaxWidth(),
                            colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Text(if (importEnCours) "Importation..." else "☁️ Importer les joueurs")
                        }
                        msgImport?.let { msg ->
                            Spacer(Modifier.height(4.dp))
                            Text(msg, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

        } else if (estEtape3) {
            // --- ÉTAPE 3 (05/08/2026) : revue avec positions + enregistrement réel ---
            // Restauré depuis ce qui existait avant l'Étape 1 (retiré temporairement car
            // le tournoi n'existait pas encore à ce moment du flux) - logique inchangée,
            // juste redéplacée ici et gardée derrière estEtape3 (idTournoi > 0).
            LazyColumn(modifier = Modifier.fillMaxSize()) {

                if (estMitchell) {
                    val nbreTables     = equipes.size / 2
                    // Guéridon : nbreMouvements = nbreTables (pas de skip)
                    // Mitchell  : nbreMouvements = nbreTables - 1 si nbreTables pair
                    val nbreMouvements = if (estGueridon) nbreTables
                                         else if (nbreTables % 2 == 0) nbreTables - 1 else nbreTables
                    // Mitchell classique à nombre de tables pair : saut nécessaire (voir
                    // EcranChangementMouvement.kt::aSaute) - ici on ne fait qu'expliquer le
                    // mécanisme, pas le recalculer.
                    val aSkip = !estGueridon && nbreTables % 2 == 0
                    val mvntSaut = nbreTables / 2 + 1

                    item {
                        Card(
                            colors   = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    if (estGueridon) "📊 Mitchell Guéridon"
                                    else if (aSkip)  "📊 Mitchell — saut à mi-parcours"
                                    else             "📊 Récapitulatif Mitchell",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(Modifier.height(4.dp))
                                // Explications succinctes (Guéridon/saut) à la place du
                                // récapitulatif chiffré - demandé par Marco le 06/08/2026,
                                // vérifiées à la main avec lui table par table (voir la
                                // session du 05/08 pour le détail des formules).
                                if (estGueridon) {
                                    Text(
                                        "Les équipes NS ne bougent pas. Les EO montent vers la " +
                                                "table supérieure. Les donnes vont vers les tables inférieures."
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Le guéridon (relais de donnes) se trouve entre les tables " +
                                                "${nbreTables / 2} et $mvntSaut : la table $mvntSaut y dépose ses " +
                                                "donnes jouées, la table ${nbreTables / 2} vient chercher les " +
                                                "donnes sur le guéridon."
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text("La dernière table (Table $nbreTables) partage le set de donnes avec la Table 1.")
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Avantage : toutes les équipes se rencontrent et tous les sets " +
                                                "sont joués — autant de rencontres et de sets que de tables.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                } else if (aSkip) {
                                    Text(
                                        "Les équipes NS ne bougent pas. Les EO montent vers la " +
                                                "table supérieure. Les donnes vont vers les tables inférieures."
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Avec $nbreTables tables (nombre pair), un saut est nécessaire " +
                                                "au mouvement $mvntSaut : les équipes EO sautent une table " +
                                                "supplémentaire pour éviter de rejouer les mêmes donnes. " +
                                                "L'écran de mouvement affichera \"sauté la table\" pour les " +
                                                "équipes EO au mouvement $mvntSaut."
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Inconvénient : toutes les équipes ne se rencontrent pas — une " +
                                                "rencontre et un set de donnes en moins que de tables.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                } else {
                                    Text("Équipes      : ${equipes.size}")
                                    Text("Tables       : $nbreTables")
                                    Text("Mouvements   : $nbreMouvements")
                                    Text("Donnes/table : $nbreDonnesParTable")
                                    Text("Total donnes : ${nbreTables * nbreDonnesParTable}")
                                }
                            }
                        }
                    }
                }

                val positions: Map<Int, Pair<String, Int>> = if (estMitchell) {
                    val nbreTables = equipes.size / 2
                    equipes.associate { equipe ->
                        equipe.equipeNumero to positionMitchell(equipe.equipeNumero, nbreTables)
                    }
                } else {
                    // Howell : positions lues en BD (serveur ou local) à partir de l'idTournoi.
                    // Le type n'est plus passé en paramètre — le serveur et la BD locale le lisent
                    // eux-mêmes pour éviter toute interpolation SQL depuis une source externe.
                    if (modeOnline) {
                        ClientNetworkUtils.getPositionsMouvement1(idTournoi)
                    } else {
                        DatabaseManager.getPositionsMouvement1(context, idTournoi)
                    }
                }

                item { Log.i("ConstitutionEquipes", "📋 Affichage des équipes") }
                itemsIndexed(equipes) { index, equipe ->
                    val pos = positions[equipe.equipeNumero]
                    EquipeItem(
                        equipe              = equipe,
                        orientationMitchell = pos?.first,
                        tableMitchell       = pos?.second,
                        onSupprimer         = { equipeASupprimer = equipe },
                        onRemplacerJoueur   = { numJoueur -> remplacerJoueur(equipe, numJoueur) },
                        afficherFleches     = true,
                        onMonter            = if (index > 0) { { intervertirEquipes(equipe, equipes[index - 1]) } } else null,
                        onDescendre         = if (index < equipes.size - 1) { { intervertirEquipes(equipe, equipes[index + 1]) } } else null
                    )
                }

                if (estMitchell && !estGueridon && !modeOnline) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick  = { bwsPickerLauncher.launch("*/*") }
                        ) {
                            Text(
                                bwsImporteNom?.let { "📄 Fichier FFBClubNet : $it" }
                                    ?: "Importer un fichier .bws FFBClubNet (optionnel)"
                            )
                        }
                        if (bwsImporteNom != null) {
                            TextButton(onClick = { bwsImporteUri = null; bwsImporteNom = null }) {
                                Text("Annuler l'import, générer un fichier de test")
                            }
                        }
                    }
                }

                // 06/08/2026 : après une ou plusieurs suppressions, le type de tournoi
                // choisi à l'étape précédente peut ne plus être compatible avec le nombre
                // d'équipes restant - Mitchell simple se recalcule tout seul (jamais invalide
                // ici), mais Howell exige un nombre exact d'équipes (préréglage fixe) et
                // Guéridon exige un multiple de 4. Bloque l'enregistrement dans ce cas plutôt
                // que de laisser démarrer un tournoi incohérent.
                val typeCompatible = when {
                    estGueridon  -> equipes.size % 4 == 0
                    estMitchell  -> true // parité déjà garantie par supprimerEquipe
                    else         -> equipes.size == nombreEquipes // Howell : préréglage fixe
                }

                if (!typeCompatible) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        Card(
                            colors   = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                val texteProbleme = if (estGueridon) {
                                    val prochainMultiple = ((equipes.size / 4) + 1) * 4
                                    "Un Guéridon exige un nombre d'équipes multiple de 4 " +
                                            "(actuellement ${equipes.size}). Il faut changer de " +
                                            "type de tournoi ou rajouter ${prochainMultiple - equipes.size} équipe(s)."
                                } else {
                                    "Ce type de tournoi exige exactement $nombreEquipes équipes " +
                                            "(actuellement ${equipes.size}). Il faut changer de type " +
                                            "de tournoi ou rajouter ${nombreEquipes - equipes.size} équipe(s)."
                                }
                                Text(texteProbleme, color = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick  = { afficherEquipes = false }
                                ) { Text("Retour aux équipes") }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick  = {
                                        // RESULT_FIRST_USER (pas RESULT_OK ni CANCELED) :
                                        // signale à CreationTournoiActivity "équipes mises à
                                        // jour, pas un succès final" - voir son launcher.
                                        val retourIntent = Intent().apply {
                                            putParcelableArrayListExtra("equipes", ArrayList(equipes))
                                            putParcelableArrayListExtra("joueurs_restants", ArrayList(joueursDispo))
                                        }
                                        activity?.setResult(Activity.RESULT_FIRST_USER, retourIntent)
                                        activity?.finish()
                                    }
                                ) { Text("Retour au choix du type") }
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        enabled  = typeCompatible,
                        modifier = Modifier.fillMaxWidth(),
                        onClick  = {
                            Log.i("ConstitutionEquipes", "🖱️ Enregistrement final")
                            scope.launch {
                                val saved = if (modeOnline) {
                                    withContext(Dispatchers.IO) {
                                        ClientNetworkUtils.enregistrerEquipes(idTournoi, equipes)
                                    }
                                } else {
                                    DatabaseManager.enregistrerEquipes(context, idTournoi, equipes)
                                }
                                if (saved) {
                                    val equipeRelais = equipes.firstOrNull { equipe ->
                                        equipe.joueur1.nom.trim().lowercase() == "relais" ||
                                                equipe.joueur2.nom.trim().lowercase() == "relais"
                                    }
                                    TournoiConfig.EQUIPE_RELAIS = equipeRelais?.equipeNumero
                                    Log.i("ConstitutionEquipes", "🏖️ Équipe relais : ${TournoiConfig.EQUIPE_RELAIS ?: "aucune"}")

                                    if (estMitchell) {
                                        val nbreTables     = equipes.size / 2
                                        val nbreMouvements = if (estGueridon) nbreTables
                                                             else if (nbreTables % 2 == 0) nbreTables - 1 else nbreTables
                                        val nbreEnreg      = nbreTables * nbreMouvements * nbreDonnesParTable

                                        TournoiConfig.NBRE_MOUVEMENTS       = nbreMouvements
                                        TournoiConfig.NBRE_DONNES_PAR_TABLE = nbreDonnesParTable
                                        TournoiConfig.NBRE_TABLES           = nbreTables

                                        Log.i("ConstitutionEquipes",
                                            "⚙️ ${if (estGueridon) "MitchellGuéridon" else "Mitchell"} - tables=$nbreTables mvnts=$nbreMouvements " +
                                                    "donnes/table=$nbreDonnesParTable nbreEnreg=$nbreEnreg"
                                        )
                                        if (!modeOnline) {
                                            if (estGueridon) {
                                                DatabaseManager.miseAJourTournoiMitchellGueridon(
                                                    context     = context,
                                                    idTournoi   = idTournoi,
                                                    nbreEquipes = equipes.size
                                                )
                                            } else {
                                                DatabaseManager.miseAJourTournoiMitchell(
                                                    context       = context,
                                                    idTournoi     = idTournoi,
                                                    nbreEquipes   = equipes.size,
                                                    bwsExterneUri = bwsImporteUri
                                                )
                                            }
                                        }
                                        if (modeOnline) {
                                            withContext(Dispatchers.IO) {
                                                if (estGueridon) {
                                                    ClientNetworkUtils.miseAJourTournoiMitchellGueridon(
                                                        idTournoi   = idTournoi,
                                                        nbreEquipes = equipes.size
                                                    )
                                                } else {
                                                    ClientNetworkUtils.miseAJourTournoiMitchell(
                                                        idTournoi   = idTournoi,
                                                        nbreEquipes = equipes.size
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (!modeOnline) {
                                        DatabaseManager.ouvrirTournoi(context, idTournoi)
                                    }

                                    val resultIntent = Intent().apply {
                                        putExtra("equipes_success", true)
                                        putExtra("id_tournoi", idTournoi)
                                        putExtra("type_tournoi", typeTournoi)
                                    }
                                    activity?.setResult(Activity.RESULT_OK, resultIntent)
                                    activity?.finish()
                                }
                            }
                        }
                    ) {
                        Text("Enregistrer et Démarrer")
                    }
                }
            }

        } else {
            // --- ÉTAPE 1 (05/08/2026) : simple confirmation, sans type ni tournoi connu ---
            // Le récapitulatif complet (positions NS/EO/table, mouvements, import .bws,
            // enregistrement réel en base) n'a de sens qu'une fois le type de tournoi
            // choisi (positions Howell notamment, lues en BD par idTournoi - qui n'existe
            // pas encore à ce stade) — affiché à l'Étape 3 (ci-dessus, estEtape3) une fois
            // CreationTournoiActivity passée.
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        "${equipes.size} équipes prêtes (${equipes.size / 2} tables) :",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                }
                itemsIndexed(equipes) { index, equipe ->
                    EquipeItem(
                        equipe              = equipe,
                        orientationMitchell = null,
                        tableMitchell       = null,
                        onSupprimer         = { equipeASupprimer = equipe },
                        onRemplacerJoueur   = { numJoueur -> remplacerJoueur(equipe, numJoueur) },
                        afficherFleches     = true,
                        onMonter            = if (index > 0) { { intervertirEquipes(equipe, equipes[index - 1]) } } else null,
                        onDescendre         = if (index < equipes.size - 1) { { intervertirEquipes(equipe, equipes[index + 1]) } } else null
                    )
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick  = {
                            // Étape 2 : lance le choix du type de tournoi avec cette liste
                            // d'équipes déjà construite (aucune écriture BD ici, la ligne
                            // `tournois` n'est créée qu'une fois le type choisi).
                            Log.i("ConstitutionEquipes", "➡️ ${equipes.size} équipes validées, direction choix du type")
                            onEquipesValidees(equipes, joueursDispo.toList())
                        }
                    ) {
                        Text("Continuer →")
                    }
                }
            }
        }
    }
}

@Composable
fun JoueurCard(joueur: Joueur, onClick: (() -> Unit)? = null) {
    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        colors    = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Text(
            text     = "${joueur.prenom} ${joueur.nom}",
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            style    = MaterialTheme.typography.bodyLarge,
            color    = Color.Black
        )
    }
}
