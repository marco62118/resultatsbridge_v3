// COMPOSANT : EquipeItem — ligne d'affichage d'une équipe dans une liste
package app.resultatsbridge.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resultatsbridge.common.model.Equipe

/**
 * Carte affichant une équipe :
 * - Numéro d'équipe
 * - Joueur1 (Nom + Prénom)
 * - Joueur2 (Nom + Prénom)
 * - Pour Mitchell : orientation (NS/EO) et table de départ
 *
 * [onSupprimer] si non-null, affiche un bouton de suppression de l'équipe.
 * [onRemplacerJoueur] si non-null, affiche un crayon à côté de chaque joueur pour le
 * remplacer individuellement ; appelé avec 1 ou 2 selon le joueur cliqué.
 * [onMonter]/[onDescendre] si non-null, affichent des flèches pour intervertir cette
 * équipe avec sa voisine du dessus/dessous ; passer null pour désactiver la flèche
 * (ex. équipe déjà en première/dernière position). Actives seulement si [afficherFleches].
 */
@Composable
fun EquipeItem(
    equipe: Equipe,
    orientationMitchell: String? = null,  // "NS" ou "EO", null si pas Mitchell
    tableMitchell: Int? = null,           // table de départ, null si pas Mitchell
    onSupprimer: (() -> Unit)? = null,
    onRemplacerJoueur: ((Int) -> Unit)? = null,
    onMonter: (() -> Unit)? = null,
    onDescendre: (() -> Unit)? = null,
    afficherFleches: Boolean = false
) {
    Card(
        modifier  = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (afficherFleches) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick  = { onMonter?.invoke() },
                        enabled  = onMonter != null,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Monter l'équipe", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick  = { onDescendre?.invoke() },
                        enabled  = onDescendre != null,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Descendre l'équipe", modifier = Modifier.size(18.dp))
                    }
                }
            }
            Column(modifier = Modifier.padding(12.dp).weight(1f)) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(text = "Équipe n°${equipe.equipeNumero}")
                    // Affichage NS/EO + table uniquement si Mitchell
                    if (orientationMitchell != null && tableMitchell != null) {
                        val couleur = if (orientationMitchell == "NS")
                            Color(0xFF1565C0)  // bleu pour NS
                        else
                            Color(0xFFB71C1C)  // rouge pour EO
                        Text(
                            text       = "$orientationMitchell — Table $tableMitchell",
                            fontWeight = FontWeight.Bold,
                            color      = couleur
                        )
                    }
                    if (onSupprimer != null) {
                        IconButton(onClick = onSupprimer) {
                            Icon(Icons.Filled.Delete, contentDescription = "Supprimer l'équipe")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text     = "${equipe.joueur1.nom} ${equipe.joueur1.prenom}",
                        modifier = Modifier.weight(1f)
                    )
                    if (onRemplacerJoueur != null) {
                        IconButton(onClick = { onRemplacerJoueur(1) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Remplacer ce joueur")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text     = "${equipe.joueur2.nom} ${equipe.joueur2.prenom}",
                        modifier = Modifier.weight(1f)
                    )
                    if (onRemplacerJoueur != null) {
                        IconButton(onClick = { onRemplacerJoueur(2) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Remplacer ce joueur")
                        }
                    }
                }
            }
        }
    }
}
