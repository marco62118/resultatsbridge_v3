// ÉCRAN : Constitution des équipes — saisie des joueurs par équipe, import depuis serveur
package app.resultatsbridge.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.widget.Toast
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
import app.resultatsbridge.organisateur.data.DatabaseManager
import app.resultatsbridge.common.model.Equipe
import app.resultatsbridge.common.model.TournoiConfig
import app.resultatsbridge.common.model.Joueur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran de constitution des équipes, commun à tous les types de tournoi.
 *
 * Flux : CreationTournoiActivity crée le tournoi en BD, obtient un idTournoi,
 * puis lance ConstitutionEquipesActivity qui affiche cet écran.
 * Le tournoi est donc déjà persisté avant d'arriver ici.
 *
 * [idTournoi]          ID du tournoi déjà créé en BD.
 * [joueursInitiaux]    Liste des joueurs disponibles pour former les équipes.
 * [nombreEquipes]      Nombre d'équipes attendu (Howell) ou -1 pour Mitchell (libre).
 * [modeOnline]         true = serveur distant alwaysdata, false = serveur embarqué NanoHTTPD.
 * [nbreDonnesParTable] Donnes par table (Mitchell uniquement, 0 sinon).
 * [typeTournoi]        Type de tournoi transmis par CreationTournoiActivity.
 *                      Utilisé localement pour le calcul Guéridon — jamais renvoyé au serveur.
 */
@Composable
fun ConstitutionEquipesScreen(
    idTournoi: Int,
    joueursInitiaux: List<Joueur>,
    nombreEquipes: Int,
    modeOnline: Boolean = false,
    nbreDonnesParTable: Int = 0,
    typeTournoi: String = ""
) {
    Log.i("ConstitutionEquipes", "👥 ${joueursInitiaux.size} joueurs disponibles")
    val context  = LocalContext.current
    val activity = context as? Activity
    val scope    = rememberCoroutineScope()

    val estMitchell  = nombreEquipes == -1
    val estGueridon  = estMitchell && typeTournoi == "MitchellGueridon"

    var joueursDispo = remember {
        mutableStateListOf<Joueur>().apply {
            addAll(joueursInitiaux.sortedBy { it.prenom.lowercase() })
        }
    }
    var joueur1         by remember { mutableStateOf<Joueur?>(null) }
    var joueur2         by remember { mutableStateOf<Joueur?>(null) }
    var equipes         by remember { mutableStateOf<List<Equipe>>(emptyList()) }
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
    var afficherEquipes by remember { mutableStateOf(false) }
    var nouveauNom      by remember { mutableStateOf("") }
    var nouveauPrenom   by remember { mutableStateOf("") }
    var messageParite   by remember { mutableStateOf<String?>(null) }
    var importEnCours   by remember { mutableStateOf(false) }
    var msgImport       by remember { mutableStateOf<String?>(null) }
    var equipeASupprimer by remember { mutableStateOf<Equipe?>(null) }

    // Supprime une équipe déjà constituée : ses 2 joueurs retournent dans le pool,
    // les autres équipes ne bougent pas (leur numéro est conservé). Le numéro libéré
    // sera repris automatiquement par la prochaine équipe créée (cf. numeroEquipe ci-dessus).
    val supprimerEquipe: (Equipe) -> Unit = { equipe ->
        joueursDispo.add(equipe.joueur1)
        joueursDispo.add(equipe.joueur2)
        val sortedList = joueursDispo.sortedBy { it.prenom.lowercase() }
        joueursDispo.clear()
        joueursDispo.addAll(sortedList)

        equipes = equipes.filter { it.equipeNumero != equipe.equipeNumero }

        messageParite   = null
        afficherEquipes = false
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

    Column(modifier = Modifier.fillMaxSize().padding(vertical = 16.dp)) {

        if (estMitchell) {
            Text(
                "Constitution des équipes Mitchell (tournoi $idTournoi)",
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
                                    val joueurCree = if (modeOnline) {
                                        withContext(Dispatchers.IO) {
                                            ClientNetworkUtils.ajouterNouveauJoueur(
                                                nom    = nouveauNom.trim(),
                                                prenom = nouveauPrenom.trim()
                                            )
                                        }
                                    } else {
                                        DatabaseManager.ajouterNouveauJoueur(
                                            context = context,
                                            nom     = nouveauNom.trim(),
                                            prenom  = nouveauPrenom.trim()
                                        )
                                    }
                                    if (joueurCree != null) {
                                        Log.i("ConstitutionEquipes", "💾 Joueur ${joueurCree.prenom} créé et assigné")
                                        if (joueur1 == null) joueur1 = joueurCree else joueur2 = joueurCree
                                        nouveauNom    = ""
                                        nouveauPrenom = ""
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

        } else {
            // --- AFFICHAGE FINAL ---
            LazyColumn(modifier = Modifier.fillMaxSize()) {

                if (estMitchell) {
                    val nbreTables     = equipes.size / 2
                    // Guéridon : nbreMouvements = nbreTables (pas de skip)
                    // Mitchell  : nbreMouvements = nbreTables - 1 si nbreTables pair
                    val nbreMouvements = if (estGueridon) nbreTables
                                         else if (nbreTables % 2 == 0) nbreTables - 1 else nbreTables

                    item {
                        Card(
                            colors   = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    if (estGueridon) "📊 Récapitulatif Mitchell Guéridon"
                                    else             "📊 Récapitulatif Mitchell",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("Équipes      : ${equipes.size}")
                                Text("Tables       : $nbreTables")
                                Text("Mouvements   : $nbreMouvements")
                                Text("Donnes/table : $nbreDonnesParTable")
                                Text("Total donnes : ${nbreTables * nbreDonnesParTable}")
                            }
                        }
                    }
                }

                val positions: Map<Int, Pair<String, Int>> = if (estMitchell) {
                    val nbreTables = equipes.size / 2
                    equipes.associate { equipe ->
                        if (equipe.equipeNumero <= nbreTables) {
                            equipe.equipeNumero to Pair("EO", equipe.equipeNumero)
                        } else {
                            equipe.equipeNumero to Pair("NS", equipe.equipeNumero - nbreTables)
                        }
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

                item {
                    Spacer(Modifier.height(16.dp))
                    Button(
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
                                                    context     = context,
                                                    idTournoi   = idTournoi,
                                                    nbreEquipes = equipes.size
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
