// ACTIVITÉ : Menu "Préparation d'un tournoi préétabli" (aide à la duplication — V3)
// Regroupe les 3 étapes : renseigner la donne cible (import PBN ou saisie manuelle,
// les deux mènent au même écran SaisieDonneCibleActivity qui gère déjà les deux)
// et l'aide à la répartition des cartes (TriDuplicationActivity).
package app.resultatsbridge.organisateur

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.resultatsbridge.main.BaseActivity
import app.resultatsbridge.common.AppScaffold
import app.resultatsbridge.common.theme.BridgeServeurTheme

class PreparationTournoiPreetabliActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BridgeServeurTheme {
                val context = LocalContext.current

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
                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { context.startActivity(Intent(context, SaisieDonneCibleActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("📄 Importation du fichier des donnes") }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { context.startActivity(Intent(context, SaisieDonneCibleActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("✍️ Saisie manuelle des donnes") }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { context.startActivity(Intent(context, TriDuplicationActivity::class.java)) },
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
