package app.resultatsbridge.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resultatsbridge.common.model.ClassementManager
import app.resultatsbridge.common.model.DonneResultatDetail
import app.resultatsbridge.organisateur.data.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Écran Organisateur : marquer/démarquer une donne comme "erreur, aucune équipe
// fautive" (moyenne individuelle) — tournois locaux uniquement.
@Composable
fun ErreurDonneScreen(
    idTournoi: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var resultats by remember { mutableStateOf<List<DonneResultatDetail>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var erreurChargement by remember { mutableStateOf<String?>(null) }

    var resultSelectionne by remember { mutableStateOf<DonneResultatDetail?>(null) }
    var enregistrementEnCours by remember { mutableStateOf(false) }
    var messageRetour by remember { mutableStateOf<String?>(null) }

    suspend fun recharger() {
        resultats = withContext(Dispatchers.IO) { DatabaseManager.getDonneResultatDetails(context, idTournoi) }
    }

    LaunchedEffect(idTournoi) {
        isLoading = true; erreurChargement = null
        recharger()
        isLoading = false
        if (resultats.isEmpty()) erreurChargement = "Aucun résultat enregistré pour ce tournoi."
    }

    // Dialog de confirmation
    resultSelectionne?.let { res ->
        val dejaFlaggee = res.typeCorrection == ClassementManager.TYPE_ERREUR_MOYENNE_IND
        AlertDialog(
            onDismissRequest = { resultSelectionne = null; messageRetour = null },
            title = {
                Text(
                    if (dejaFlaggee) "Annuler l'erreur — Donne N°${res.numeroDonne}"
                    else "Marquer en erreur — Donne N°${res.numeroDonne}",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("NS Éq.${res.equipeNS}  vs  EO Éq.${res.equipeEO}",
                        style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text(
                        if (dejaFlaggee)
                            "Cette donne est actuellement en erreur : les deux équipes reçoivent leur moyenne individuelle. Annuler pour revenir au calcul normal ?"
                        else
                            "Aucune équipe fautive : chaque équipe recevra sa propre moyenne individuelle (calculée sur ses autres donnes du tournoi) pour cette donne, au lieu du score réel.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    messageRetour?.let {
                        Text(it,
                            color = if (it.startsWith("✅")) Color(0xFF2E7D32) else Color.Red,
                            fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !enregistrementEnCours,
                    colors = if (dejaFlaggee) ButtonDefaults.buttonColors()
                             else ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                    onClick = {
                        enregistrementEnCours = true
                        messageRetour = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                DatabaseManager.marquerErreurDonne(
                                    context, idTournoi, res.numeroDonne, res.equipeNS, res.equipeEO,
                                    enErreur = !dejaFlaggee
                                )
                            }
                            enregistrementEnCours = false
                            if (ok) {
                                messageRetour = "✅ Correction enregistrée"
                                recharger()
                                resultSelectionne = null
                            } else {
                                messageRetour = "❌ Erreur lors de la correction"
                            }
                        }
                    }
                ) {
                    if (enregistrementEnCours) Text("Enregistrement...")
                    else Text(if (dejaFlaggee) "Annuler l'erreur" else "⚠️ Marquer en erreur")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { resultSelectionne = null; messageRetour = null }) {
                    Text("Fermer")
                }
            }
        )
    }

    // Écran principal
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).background(Color.Red, CircleShape).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Text("Erreur sur une donne — Tournoi $idTournoi",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()

        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            erreurChargement != null -> {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(erreurChargement!!, color = Color.Red)
                }
            }
            else -> {
                Text(
                    "  Touchez une donne pour la marquer en erreur (moyenne individuelle)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(resultats) { res ->
                        val enErreur = res.typeCorrection == ClassementManager.TYPE_ERREUR_MOYENNE_IND
                        Card(
                            onClick = { resultSelectionne = res; messageRetour = null },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                            colors = if (enErreur) CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                                     else CardDefaults.cardColors(),
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Donne N°${res.numeroDonne}",
                                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("NS Éq.${res.equipeNS}  vs  EO Éq.${res.equipeEO}",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if (enErreur) {
                                    Text(
                                        "⚠️ EN ERREUR",
                                        color = Color(0xFFE65100),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
