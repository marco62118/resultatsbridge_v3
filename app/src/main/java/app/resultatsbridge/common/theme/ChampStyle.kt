package app.resultatsbridge.common.theme

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Style commun à tous les champs de saisie (cadre = bordure, pas un fond) et aux boutons
// sélecteurs — un seul endroit à modifier pour changer le style dans toute l'appli.
// Public cible : bridgeurs pas tous jeunes, donc gros contraste et grande police (15/07/2026).

val ChampTextStyle = TextStyle(fontSize = 22.sp, color = Color.Black, fontWeight = FontWeight.Bold)

@Composable
fun champColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color.White,
    unfocusedBorderColor = Color.White,
    focusedTextColor = Color.Black,
    unfocusedTextColor = Color.Black,
    focusedLabelColor = Color.Black,
    unfocusedLabelColor = Color.Black,
    cursorColor = Color.Black
)

// Boutons sélecteurs (ex. accueil : mode de saisie / mode connexion)
val BordureSelectionnee = 4.dp
val BordureNonSelectionnee = 1.dp
val CouleurSelectionnee = Color(0xFF6650A4)
val CouleurNonSelectionnee = Color.White.copy(alpha = 0.6f) // blanc moins éclatant (15/07/2026)

// Texte blanc sur fond vert (ex. libellés des boutons sélecteurs) — adouci différemment
// selon sélectionné/non sélectionné, comme la bordure (15/07/2026).
val TexteSelectionne = Color.White
val TexteNonSelectionne = Color.White.copy(alpha = 0.6f)
