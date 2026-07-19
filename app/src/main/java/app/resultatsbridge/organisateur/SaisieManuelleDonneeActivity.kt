// ACTIVITÉ : Saisie manuelle d'une donne cible — pour l'aide à la duplication manuelle (V3)
// Écran séparé de l'import (voir organigramme) : n'a pas d'intérêt pour un usage normal
// (on reçoit un fichier), utile seulement pour corriger une donne importée erronée ou pour
// tester. Champ N° de donne + AffichageMainsScreen (saisie manuelle 4×13 cartes), même
// stockage que l'import (DatabaseManager.sauvegarderDonneCible, sentinel dédié).
package app.resultatsbridge.organisateur

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.resultatsbridge.main.BaseActivity
import app.resultatsbridge.common.AppScaffold
import app.resultatsbridge.common.theme.BridgeServeurTheme
import app.resultatsbridge.common.theme.ChampTextStyle
import app.resultatsbridge.common.theme.champColors
import app.resultatsbridge.client.ClientNetworkUtils
import app.resultatsbridge.organisateur.data.DatabaseManager
import app.resultatsbridge.screens.AffichageMainsScreen
import kotlinx.coroutines.launch

class SaisieManuelleDonneeActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ipReseau = intent.getStringExtra("ip_serveur_preparation")
        if (ipReseau != null) ClientNetworkUtils.initialiserServeur(ipReseau)

        setContent {
            BridgeServeurTheme {
                val scope = rememberCoroutineScope()
                var numeroDonneSaisie by remember { mutableStateOf("") }
                var donneEnSaisie by remember { mutableStateOf<Int?>(null) }
                var message by remember { mutableStateOf("") }

                fun demarrerSaisie() {
                    val n = numeroDonneSaisie.toIntOrNull() ?: 0
                    if (n > 0) {
                        message = ""
                        donneEnSaisie = n
                        numeroDonneSaisie = ""
                    }
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
                                val ok = if (ipReseau != null) {
                                    ClientNetworkUtils.sauvegarderDonneCible(numDonne, mainsCodes)
                                } else {
                                    DatabaseManager.sauvegarderDonneCible(
                                        this@SaisieManuelleDonneeActivity, numDonne, mainsCodes
                                    )
                                }
                                message = if (ok) "✅ Donne $numDonne enregistrée" else "❌ Erreur sauvegarde donne $numDonne"
                            }
                        }
                    )
                    return@BridgeServeurTheme
                }

                AppScaffold(ipAddress = "", numeroTournoi = null, equipeChoisie = null, nomEcran = "Saisie manuelle des donnes") {
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Saisie manuelle d'une donne cible", style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Pas d'intérêt pour un tournoi entier reçu par fichier — utile seulement pour " +
                            "corriger une donne importée erronée, ou pour faire un test.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black
                        )

                        if (message.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(message, color = if (message.startsWith("✅")) Color(0xFF2E7D32) else Color(0xFFC62828), fontWeight = FontWeight.Medium)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = numeroDonneSaisie,
                                onValueChange = { numeroDonneSaisie = it.filter { c -> c.isDigit() } },
                                label = { Text("N° de donne à saisir", fontWeight = FontWeight.Bold) },
                                textStyle = ChampTextStyle,
                                colors = champColors(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(onDone = { demarrerSaisie() }),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = { demarrerSaisie() }) { Text("Saisir") }
                        }

                        Spacer(modifier = Modifier.weight(1f))
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
