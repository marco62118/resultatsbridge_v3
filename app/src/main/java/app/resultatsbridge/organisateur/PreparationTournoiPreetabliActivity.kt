// ACTIVITÉ : Menu "Préparation d'un tournoi préétabli" (aide à la duplication — V3)
// Regroupe les 3 étapes, chacune un écran séparé (voir organigramme) : import du fichier
// des donnes (SaisieDonneCibleActivity), saisie manuelle (SaisieManuelleDonneeActivity,
// utile seulement pour corriger/tester), et l'aide à la répartition des cartes
// (TriDuplicationActivity).
//
// Avant le menu, un choix de mode de travail permet de préparer les donnes à plusieurs
// téléphones sur le même réseau Wi-Fi (indépendant de tout tournoi, donc distinct du
// mode réseau utilisé pendant un vrai tournoi) :
// - Solo : ce téléphone seulement, base locale (comportement d'origine).
// - Organisateur : démarre le serveur local embarqué et affiche l'IP à communiquer.
//   L'organisateur reste sur sa base locale (c'est lui qui héberge les données).
// - Rejoindre : se connecte au serveur d'un organisateur déjà démarré (IP saisie),
//   toutes les lectures/écritures passent alors par le réseau.
package app.resultatsbridge.organisateur

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resultatsbridge.main.BaseActivity
import app.resultatsbridge.common.AppScaffold
import app.resultatsbridge.common.theme.BridgeServeurTheme
import app.resultatsbridge.common.theme.ChampTextStyle
import app.resultatsbridge.common.theme.champColors
import app.resultatsbridge.client.ClientNetworkUtils
import app.resultatsbridge.organisateur.server.ServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PhasePreparation { CHOIX_MODE, REJOINDRE, MENU }

class PreparationTournoiPreetabliActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BridgeServeurTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                var phase by remember { mutableStateOf(PhasePreparation.CHOIX_MODE) }
                var ipServeur by remember { mutableStateOf<String?>(null) } // non-null = mode "rejoindre" (réseau)
                var ipOrganisateur by remember { mutableStateOf<String?>(null) } // IP à afficher/communiquer
                var ipSaisie by remember { mutableStateOf("") }
                var message by remember { mutableStateOf("") }
                var connexionEnCours by remember { mutableStateOf(false) }

                fun lancer(activite: Class<*>) {
                    val intent = Intent(context, activite)
                    ipServeur?.let { intent.putExtra("ip_serveur_preparation", it) }
                    context.startActivity(intent)
                }

                when (phase) {
                    PhasePreparation.CHOIX_MODE -> {
                        BackHandler(onBack = { finish() })
                        AppScaffold(ipAddress = "", numeroTournoi = null, equipeChoisie = null, nomEcran = "Préparation d'un tournoi préétabli") {
                            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Comment travailler ?", style = MaterialTheme.typography.headlineSmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "La préparation des donnes (import, saisie, répartition) peut se faire seul, " +
                                        "ou à plusieurs téléphones sur le même Wi-Fi pour se répartir le travail.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(modifier = Modifier.height(20.dp))

                                Card(
                                    onClick = { ipServeur = null; phase = PhasePreparation.MENU },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text("📱 Solo", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("Ce téléphone seulement.", fontSize = 13.sp, color = Color.Black)
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))

                                Card(
                                    onClick = {
                                        scope.launch {
                                            message = ""
                                            val ip = withContext(Dispatchers.IO) { ServerManager.startServer(context) }
                                            if (ip != null) {
                                                ipOrganisateur = ip
                                                ipServeur = null // l'organisateur reste sur sa base locale
                                                phase = PhasePreparation.MENU
                                            } else {
                                                message = "❌ Impossible de démarrer le réseau"
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text("📶 Organisateur — démarrer le réseau", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "Ce téléphone héberge les donnes. Les autres se connectent avec l'IP affichée ensuite.",
                                            fontSize = 13.sp, color = Color.Black
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))

                                Card(
                                    onClick = { message = ""; phase = PhasePreparation.REJOINDRE },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text("🔌 Rejoindre une préparation", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "Se connecter au téléphone organisateur (IP communiquée par lui).",
                                            fontSize = 13.sp, color = Color.Black
                                        )
                                    }
                                }

                                if (message.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(message, color = Color(0xFFC62828), fontWeight = FontWeight.Medium)
                                }

                                Spacer(modifier = Modifier.weight(1f))
                                OutlinedButton(
                                    onClick = { finish() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Retour") }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }

                    PhasePreparation.REJOINDRE -> {
                        BackHandler(onBack = { phase = PhasePreparation.CHOIX_MODE })
                        AppScaffold(ipAddress = "", numeroTournoi = null, equipeChoisie = null, nomEcran = "Rejoindre une préparation") {
                            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("IP de l'organisateur", style = MaterialTheme.typography.headlineSmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Demandez l'IP affichée sur le téléphone organisateur, sur le même Wi-Fi.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(modifier = Modifier.height(16.dp))

                                OutlinedTextField(
                                    value = ipSaisie,
                                    onValueChange = { ipSaisie = it.trim() },
                                    label = { Text("Adresse IP", fontWeight = FontWeight.Bold) },
                                    textStyle = ChampTextStyle,
                                    colors = champColors(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                if (message.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(message, color = Color(0xFFC62828), fontWeight = FontWeight.Medium)
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        if (ipSaisie.isBlank()) { message = "❌ Saisissez une IP"; return@Button }
                                        connexionEnCours = true
                                        message = ""
                                        scope.launch {
                                            ClientNetworkUtils.initialiserServeur(ipSaisie)
                                            val ok = withContext(Dispatchers.IO) {
                                                try { ClientNetworkUtils.getDonnesCibles(); true } catch (e: Exception) { false }
                                            }
                                            connexionEnCours = false
                                            if (ok) {
                                                ipServeur = ipSaisie
                                                phase = PhasePreparation.MENU
                                            } else {
                                                message = "❌ Connexion impossible — vérifiez l'IP et le Wi-Fi"
                                            }
                                        }
                                    },
                                    enabled = !connexionEnCours,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (connexionEnCours) "Connexion..." else "Se connecter") }

                                Spacer(modifier = Modifier.weight(1f))
                                OutlinedButton(
                                    onClick = { phase = PhasePreparation.CHOIX_MODE },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Retour") }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }

                    PhasePreparation.MENU -> {
                        BackHandler(onBack = { phase = PhasePreparation.CHOIX_MODE })
                        AppScaffold(ipAddress = "", numeroTournoi = null, equipeChoisie = null, nomEcran = "Préparation d'un tournoi préétabli") {
                            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Préparation d'un tournoi préétabli", style = MaterialTheme.typography.headlineSmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Renseignez les donnes cibles connues à l'avance (bulletin, simultané...), " +
                                        "par import de fichier ou saisie manuelle.",
                                    style = MaterialTheme.typography.bodySmall
                                )

                                if (ipOrganisateur != null) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                                        Text(
                                            "📶 Réseau démarré — IP à communiquer : $ipOrganisateur",
                                            modifier = Modifier.padding(12.dp),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else if (ipServeur != null) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                                        Text(
                                            "🔌 Connecté à l'organisateur ($ipServeur)",
                                            modifier = Modifier.padding(12.dp),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                // L'import du fichier ne se fait que depuis le téléphone organisateur
                                // (ou en solo) — un téléphone qui rejoint n'a pas à réimporter le fichier.
                                if (ipServeur == null) {
                                    Button(
                                        onClick = { lancer(SaisieDonneCibleActivity::class.java) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("📄 Importation du fichier des donnes") }

                                    Spacer(modifier = Modifier.height(12.dp))
                                }

                                Button(
                                    onClick = { lancer(SaisieManuelleDonneeActivity::class.java) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("✍️ Saisie manuelle des donnes") }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = { lancer(TriDuplicationActivity::class.java) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("🃏 Aide à la répartition des cartes pour le tournoi préétabli") }

                                Spacer(modifier = Modifier.weight(1f))

                                OutlinedButton(
                                    onClick = { finish() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Retour") }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
