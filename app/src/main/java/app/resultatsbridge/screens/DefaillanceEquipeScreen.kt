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
import app.resultatsbridge.common.model.Equipe
import app.resultatsbridge.organisateur.data.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Écran Organisateur : déclarer (ou annuler) la défaillance d'une équipe.
// Tournois locaux uniquement — ne pré-remplit aucune donne : la correction
// 40/60 se fait à la volée, à la table, quand une vraie équipe rencontre
// l'équipe défaillante (voir DIALOG_DEFAILLANCE côté client).
@Composable
fun DefaillanceEquipeScreen(
    idTournoi: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var equipes by remember { mutableStateOf<List<Equipe>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var equipeSelectionnee by remember { mutableStateOf<Equipe?>(null) }
    var enregistrementEnCours by remember { mutableStateOf(false) }
    var messageRetour by remember { mutableStateOf<String?>(null) }

    suspend fun recharger() {
        equipes = withContext(Dispatchers.IO) { DatabaseManager.getEquipesDuTournoi(context, idTournoi) }
            .sortedBy { it.equipeNumero }
    }

    LaunchedEffect(idTournoi) {
        isLoading = true
        recharger()
        isLoading = false
    }

    // Dialog de confirmation
    equipeSelectionnee?.let { eq ->
        val nomComplet = "${eq.joueur1.prenom} ${eq.joueur1.nom} / ${eq.joueur2.prenom} ${eq.joueur2.nom}"
        AlertDialog(
            onDismissRequest = { equipeSelectionnee = null; messageRetour = null },
            title = {
                Text(
                    if (eq.defaillante) "Annuler la défaillance — Équipe ${eq.equipeNumero}"
                    else "Déclarer défaillante — Équipe ${eq.equipeNumero}",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(nomComplet, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text(
                        if (eq.defaillante)
                            "Cette équipe est actuellement déclarée défaillante. Annuler pour revenir à la normale ?"
                        else
                            "À partir de maintenant, chaque table qui rencontrera cette équipe recevra " +
                                    "60% du top et l'équipe ${eq.equipeNumero} recevra 40% (ou sa moyenne réelle " +
                                    "si celle-ci est déjà pire), sur toutes les donnes restantes. Aucune donne déjà " +
                                    "jouée n'est modifiée.",
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
                    colors = if (eq.defaillante) ButtonDefaults.buttonColors()
                             else ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                    onClick = {
                        enregistrementEnCours = true
                        messageRetour = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                DatabaseManager.declarerEquipeDefaillante(
                                    context, idTournoi, eq.equipeNumero, defaillante = !eq.defaillante
                                )
                            }
                            enregistrementEnCours = false
                            if (ok) {
                                messageRetour = "✅ Enregistré"
                                recharger()
                                equipeSelectionnee = null
                            } else {
                                messageRetour = "❌ Erreur lors de l'enregistrement"
                            }
                        }
                    }
                ) {
                    if (enregistrementEnCours) Text("Enregistrement...")
                    else Text(if (eq.defaillante) "Annuler la défaillance" else "🚫 Déclarer défaillante")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { equipeSelectionnee = null; messageRetour = null }) {
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
            Text("Défaillance d'équipe — Tournoi $idTournoi",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()

        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            equipes.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("Aucune équipe trouvée pour ce tournoi.", color = Color.Red)
                }
            }
            else -> {
                Text(
                    "  Touchez une équipe pour la déclarer défaillante (ou annuler)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(equipes) { eq ->
                        Card(
                            onClick = { equipeSelectionnee = eq; messageRetour = null },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                            colors = if (eq.defaillante) CardDefaults.cardColors(containerColor = Color(0xFFFFCDD2))
                                     else CardDefaults.cardColors(),
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Équipe ${eq.equipeNumero}",
                                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("${eq.joueur1.prenom} ${eq.joueur1.nom} / ${eq.joueur2.prenom} ${eq.joueur2.nom}",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if (eq.defaillante) {
                                    Text(
                                        "🚫 DÉFAILLANTE",
                                        color = Color(0xFFB71C1C),
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
