// ACTIVITÉ : Importation du fichier des donnes — pour l'aide à la duplication manuelle (V3)
// Import PBN (une ou plusieurs donnes à la fois) + liste des donnes cibles déjà enregistrées
// (lecture, suppression). La saisie manuelle est un écran séparé (SaisieManuelleDonneeActivity) :
// pas d'intérêt pour un usage normal (on reçoit un fichier), utile seulement pour corriger une
// donne importée erronée ou pour tester — voir organigramme.
package app.resultatsbridge.organisateur

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resultatsbridge.main.BaseActivity
import app.resultatsbridge.common.AppScaffold
import app.resultatsbridge.common.model.Carte
import app.resultatsbridge.common.model.DonneAvantTournoi
import app.resultatsbridge.common.theme.BridgeServeurTheme
import app.resultatsbridge.common.utils.PbnParser
import app.resultatsbridge.client.ClientNetworkUtils
import app.resultatsbridge.organisateur.data.DatabaseManager
import app.resultatsbridge.screens.AffichageMainsLectureScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SaisieDonneCibleActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Non-null si on rejoint la préparation d'un organisateur en réseau (voir
        // PreparationTournoiPreetabliActivity) : les lectures/écritures passent alors
        // par ClientNetworkUtils au lieu de la base locale.
        val ipReseau = intent.getStringExtra("ip_serveur_preparation")
        if (ipReseau != null) ClientNetworkUtils.initialiserServeur(ipReseau)

        setContent {
            BridgeServeurTheme {
                val scope = rememberCoroutineScope()
                var donnes by remember { mutableStateOf<List<DonneAvantTournoi>>(emptyList()) }
                var numerosPrepares by remember { mutableStateOf<Set<Int>>(emptySet()) }
                var isLoading by remember { mutableStateOf(true) }
                var donneEnLecture by remember { mutableStateOf<DonneAvantTournoi?>(null) }
                var message by remember { mutableStateOf("") }

                fun chargerDonnes() {
                    scope.launch {
                        isLoading = true
                        donnes = if (ipReseau != null) ClientNetworkUtils.getDonnesCibles()
                                 else DatabaseManager.getDonnesCibles(this@SaisieDonneCibleActivity)
                        numerosPrepares = (if (ipReseau != null) ClientNetworkUtils.getMainsAvantTournoi()
                                           else DatabaseManager.getMainsAvantTournoi(this@SaisieDonneCibleActivity))
                            .map { it.numeroDonne }.toSet()
                        isLoading = false
                    }
                }

                fun importerPbn(uri: Uri) {
                    scope.launch {
                        message = ""
                        try {
                            val texte = withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                                    ?: throw PbnParser.PbnParseException("Impossible de lire le fichier")
                            }
                            val donnesLues = PbnParser.parse(texte)
                            if (donnesLues.isEmpty()) {
                                message = "❌ Aucune donne trouvée dans ce fichier PBN"
                            } else {
                                donnesLues.forEach { donne ->
                                    if (ipReseau != null) {
                                        ClientNetworkUtils.sauvegarderDonneCible(donne.numeroDonne, donne.mains)
                                    } else {
                                        DatabaseManager.sauvegarderDonneCible(
                                            this@SaisieDonneCibleActivity, donne.numeroDonne, donne.mains
                                        )
                                    }
                                }
                                message = "✅ ${donnesLues.size} donne(s) importée(s) depuis le PBN"
                                chargerDonnes()
                            }
                        } catch (e: PbnParser.PbnParseException) {
                            message = "❌ Fichier PBN invalide : ${e.message}"
                        } catch (e: Exception) {
                            message = "❌ Erreur import PBN : ${e.message}"
                        }
                    }
                }

                val importPbnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    uri?.let { importerPbn(it) }
                }

                LaunchedEffect(Unit) { chargerDonnes() }

                // ── Écran de lecture (vérifier une donne déjà saisie) ──────
                donneEnLecture?.let { donne ->
                    AffichageMainsLectureScreen(
                        numeroDonne = donne.numeroDonne,
                        mains = donne.mains.map { codes -> codes.map { Carte.fromCode(it) } },
                        onRetour = { donneEnLecture = null }
                    )
                    return@BridgeServeurTheme
                }

                // ── Liste des donnes cibles déjà importées ────────────────────
                AppScaffold(ipAddress = "", numeroTournoi = null, equipeChoisie = null, nomEcran = "Importation du fichier des donnes") {
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Donnes cibles pour duplication manuelle", style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Importez le fichier des donnes reçu (bulletin, simultané...). " +
                            "Elles serviront de référence pour aider à trier les cartes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black
                        )

                        if (message.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(message, color = if (message.startsWith("✅")) Color(0xFF2E7D32) else Color(0xFFC62828), fontWeight = FontWeight.Medium)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = { importPbnLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("📄 Importer depuis un fichier PBN") }

                        Spacer(modifier = Modifier.height(10.dp))

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                                donnes.isEmpty() -> Text("Aucune donne cible saisie pour l'instant.", color = Color.Black)
                                else -> {
                                    Column {
                                        Text("${donnes.size} donne(s) cible(s)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            items(donnes) { donne ->
                                                Card(
                                                    onClick = { donneEnLecture = donne },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text("Donne ${donne.numeroDonne}", fontWeight = FontWeight.Bold)
                                                            Text(
                                                                if (donne.numeroDonne in numerosPrepares) "✅ Préparée" else "⏳ À préparer",
                                                                fontSize = 12.sp,
                                                                color = if (donne.numeroDonne in numerosPrepares) Color(0xFF2E7D32) else Color(0xFFE65100),
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                        }
                                                        Text(
                                                            "Donneur: ${donne.donneur}  Vulnérable: ${donne.vulnerable}",
                                                            fontSize = 12.sp, color = Color.Black
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        IconButton(onClick = {
                                                            scope.launch {
                                                                if (ipReseau != null) ClientNetworkUtils.supprimerDonneCible(donne.numeroDonne)
                                                                else DatabaseManager.supprimerDonneCible(this@SaisieDonneCibleActivity, donne.numeroDonne)
                                                                chargerDonnes()
                                                            }
                                                        }) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer") }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                        ) { Text("✅ Terminer") }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}
