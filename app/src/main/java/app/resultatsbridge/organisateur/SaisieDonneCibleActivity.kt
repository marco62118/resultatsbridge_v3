// ACTIVITÉ : Saisie d'une donne cible — pour l'aide à la duplication manuelle (V3)
// Différence avec SaisieDonnesAvantTournoiActivity : saisie manuelle uniquement
// (pas de photo, la donne cible vient d'un bulletin/simultané déjà connu),
// et stockage séparé (DatabaseManager.sauvegarderDonneCible, sentinel dédié).
package app.resultatsbridge.organisateur

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resultatsbridge.main.BaseActivity
import app.resultatsbridge.common.AppScaffold
import app.resultatsbridge.common.model.Carte
import app.resultatsbridge.common.model.DonneAvantTournoi
import app.resultatsbridge.common.theme.BridgeServeurTheme
import app.resultatsbridge.organisateur.data.DatabaseManager
import app.resultatsbridge.screens.AffichageMainsLectureScreen
import app.resultatsbridge.screens.AffichageMainsScreen
import kotlinx.coroutines.launch

class SaisieDonneCibleActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BridgeServeurTheme {
                val scope = rememberCoroutineScope()
                var donnes by remember { mutableStateOf<List<DonneAvantTournoi>>(emptyList()) }
                var isLoading by remember { mutableStateOf(true) }
                var numeroDonneSaisie by remember { mutableStateOf("") }
                var donneEnSaisie by remember { mutableStateOf<Int?>(null) }
                var donneEnLecture by remember { mutableStateOf<DonneAvantTournoi?>(null) }
                var message by remember { mutableStateOf("") }

                fun chargerDonnes() {
                    scope.launch {
                        isLoading = true
                        donnes = DatabaseManager.getDonnesCibles(this@SaisieDonneCibleActivity)
                        isLoading = false
                    }
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

                // ── Écran de saisie manuelle (4 mains x 13 cartes) ─────────
                donneEnSaisie?.let { numDonne ->
                    AffichageMainsScreen(
                        numeroDonne = numDonne,
                        onRetour = { donneEnSaisie = null },
                        demarrerPhoto = false,
                        onEnregistrer = { mainsCartes ->
                            val mainsCodes = mainsCartes.map { main -> main.map { it.code } }
                            donneEnSaisie = null
                            scope.launch {
                                val ok = DatabaseManager.sauvegarderDonneCible(
                                    this@SaisieDonneCibleActivity, numDonne, mainsCodes
                                )
                                message = if (ok) "✅ Donne $numDonne enregistrée" else "❌ Erreur sauvegarde donne $numDonne"
                                chargerDonnes()
                            }
                        }
                    )
                    return@BridgeServeurTheme
                }

                // ── Liste des donnes cibles déjà saisies ────────────────────
                AppScaffold(ipAddress = "", numeroTournoi = null, equipeChoisie = null, nomEcran = "Donnes cibles (duplication)") {
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Donnes cibles pour duplication manuelle", style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Saisissez les 4 mains connues à l'avance (bulletin, simultané...). " +
                            "Elles serviront de référence pour aider à trier les cartes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black
                        )

                        if (message.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(message, color = if (message.startsWith("✅")) Color(0xFF2E7D32) else Color(0xFFC62828), fontWeight = FontWeight.Medium)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = numeroDonneSaisie,
                                onValueChange = { numeroDonneSaisie = it.filter { c -> c.isDigit() } },
                                label = { Text("N° de donne à saisir") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(onDone = {
                                    val n = numeroDonneSaisie.toIntOrNull() ?: 0
                                    if (n > 0) {
                                        message = ""
                                        donneEnSaisie = n
                                        numeroDonneSaisie = ""
                                    }
                                }),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                val n = numeroDonneSaisie.toIntOrNull() ?: 0
                                if (n > 0) {
                                    message = ""
                                    donneEnSaisie = n
                                    numeroDonneSaisie = ""
                                }
                            }) { Text("Saisir") }
                        }

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
                                                        Text("Donne ${donne.numeroDonne}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                                        Text(
                                                            "Donneur: ${donne.donneur}  Vulnérable: ${donne.vulnerable}",
                                                            fontSize = 12.sp, color = Color.Black
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        IconButton(onClick = {
                                                            scope.launch {
                                                                DatabaseManager.supprimerDonneCible(this@SaisieDonneCibleActivity, donne.numeroDonne)
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
