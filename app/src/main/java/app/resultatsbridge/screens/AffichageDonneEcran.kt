// ÉCRAN : Affichage donne — tableau enchères + mains N/S/E/O (AffichageDonneEcrant + TableauEncheres)
package app.resultatsbridge.screens

import android.app.Activity
import app.resultatsbridge.BuildConfig
import android.content.pm.ActivityInfo
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import app.resultatsbridge.client.DdsCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import app.resultatsbridge.common.ui.components.CarteUnifiee
import app.resultatsbridge.common.ui.components.TextSuites
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resultatsbridge.common.EcranPleinScaffold
import app.resultatsbridge.common.model.Carte
import app.resultatsbridge.common.model.AnnonceJoueur


@Composable
fun AffichageDonneEcrant(
    idTournoi: Int,
    dateTournoi: String,
    numeroDonne: Int,
    mains: Map<String, List<Carte>>,
    encheres: List<AnnonceJoueur>,
    equipeNS: Int,
    equipeEO: Int,
    vulnerable: String,
    donneur: String,
    contrat: String = "",
    declarant: String = "",
    onParCalcule: ((String) -> Unit)? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { }
    }

    EcranPleinScaffold {
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20))) {
            if (BuildConfig.DEBUG) Text("[ Affichage donne complète ]", color = Color(0xFFFFEB3B), fontWeight = FontWeight.Bold, fontSize = 11.sp)

            val mainsTriees = mapOf(
                "N" to trierCartes(mains["N"] ?: emptyList()),
                "S" to trierCartes(mains["S"] ?: emptyList()),
                "E" to trierCartes(mains["E"] ?: emptyList()),
                "O" to trierCartes(mains["O"] ?: emptyList())
            )

            var parTexte by remember { mutableStateOf("") }
            LaunchedEffect(mains, vulnerable) {
                if (mains.values.all { it.size == 13 }) {
                    parTexte = "calcul…"
                    val codesParJoueur = mains.mapValues { (_, cartes) -> cartes.map { it.code } }
                    val resultat = withContext(Dispatchers.Default) {
                        DdsCalculator.calculer(codesParJoueur, vulnerable)
                    }
                    parTexte = resultat?.texte ?: ""
                    if (resultat != null) onParCalcule?.invoke(resultat.texte)
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp)) {
                val cardW = maxWidth.value / 13f
                val cardH = cardW * 2.2f

                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center
                ) {
                    // NORD — horizontal
                    Text("NORD", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth()) {
                        for (i in 0 until 13) {
                            CarteUnifiee(mainsTriees["N"]?.getOrNull(i), largeur = cardW.dp, hauteur = cardH.dp, valeurSizeSp = 21f, symbolSizeDp = 20f)
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // OUEST | TableauEncheres | EST
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // OUEST : rotate +90° → base (symbole) vers la gauche (Ouest)
                        Column(Modifier.width(cardH.dp).align(Alignment.Top)) {
                            Text("OUEST", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            for (i in 0 until 13) {
                                Box(Modifier.rotateWithLayout(90f)) {
                                    CarteUnifiee(mainsTriees["O"]?.getOrNull(i), largeur = cardW.dp, hauteur = cardH.dp, valeurSizeSp = 21f, symbolSizeDp = 20f)
                                }
                            }
                        }

                        // Centre : infos donne + tableau enchères + bouton retour — centré verticalement
                        Box(
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {

                                Text(
                                    "Donne $numeroDonne",
                                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("NS ", color = Color.Yellow, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                    Text("$equipeNS", color = Color.Yellow, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                    Text("  EO ", color = Color(0xFF64B5F6), fontWeight = FontWeight.Black, fontSize = 16.sp)
                                    Text("$equipeEO", color = Color(0xFF64B5F6), fontWeight = FontWeight.Black, fontSize = 16.sp)
                                }
                                TableauEncheres(encheres, vulnerable, contrat, declarant, donneur)
                                if (parTexte.isNotEmpty()) {
                                    AfficherPar(parTexte)
                                }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { onBack() }.padding(vertical = 4.dp)
                                ) {
                                    Box(Modifier.size(38.dp).background(Color.Red, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size(22.dp))
                                    }
                                    Row {
                                        Text(
                                            "Retour",
                                            color = Color.Red,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            " au Tournoi $idTournoi  —  $dateTournoi",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }

                        // EST : rotate -90° → base (symbole) vers la droite (Est)
                        Column(Modifier.width(cardH.dp).align(Alignment.Top)) {
                            Text("EST", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            for (i in 0 until 13) {
                                Box(Modifier.rotateWithLayout(-90f)) {
                                    CarteUnifiee(mainsTriees["E"]?.getOrNull(i), largeur = cardW.dp, hauteur = cardH.dp, valeurSizeSp = 21f, symbolSizeDp = 20f)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // SUD — horizontal (label AVANT les cartes)
                    Text("SUD", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth()) {
                        for (i in 0 until 13) {
                            CarteUnifiee(mainsTriees["S"]?.getOrNull(i), largeur = cardW.dp, hauteur = cardH.dp, valeurSizeSp = 21f, symbolSizeDp = 20f)
                        }
                    }
                }
            }
        }
    }
}

// Affiche la ligne "Par : ..." avec couleurs sémantiques
// Formats : "Passe = 0" | "3♥ NS N = +140" | "Sacrifice EO 4♠ = -100"
@Composable
private fun AfficherPar(parTexte: String) {
    val colorNS = Color.Yellow
    val colorEO = Color(0xFF64B5F6)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text("Par : ", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        when {
            parTexte == "Passe = 0" ->
                Text("Passe = 0", color = Color.White, fontSize = 15.sp)

            parTexte.startsWith("Sacrifice") -> {
                // "Sacrifice NS 3♠ = -200" ou "Sacrifice EO 4♥ = -100"
                val p = parTexte.split(" ")
                if (p.size >= 5) {
                    val side  = p[1]
                    val score = p[4]
                    Text("Sacrifice ", color = Color.White, fontSize = 15.sp)
                    Text(side, color = if (side == "NS") colorNS else colorEO,
                        fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(" ", color = Color.White, fontSize = 15.sp)
                    TextSuites(p[2], color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(" = ", color = Color.White, fontSize = 15.sp)
                    Text(score, color = Color.Red, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                } else {
                    TextSuites(parTexte, color = Color(0xFFFFD54F), fontSize = 15.sp)
                }
            }

            else -> {
                // "3♠ NS N = +140" ou "4♥ EO E = +420"
                val p = parTexte.split(" ")
                if (p.size >= 5) {
                    val side       = p[1]
                    val decl       = p[2]
                    val score      = p[4]
                    val sideColor  = if (side == "NS") colorNS else colorEO
                    val scoreColor = if (score.startsWith("-")) Color.Red else Color.White
                    TextSuites(p[0], color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(" $side $decl", color = sideColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(" = ", color = Color.White, fontSize = 15.sp)
                    Text(score, color = scoreColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                } else {
                    TextSuites(parTexte, color = Color(0xFFFFD54F), fontSize = 15.sp)
                }
            }
        }
    }
}

// Échange les dimensions layout (width↔height) puis applique la rotation visuelle.
// Le parent voit cardH×cardW en layout ; la carte s'affiche tournée à 90°.
private fun Modifier.rotateWithLayout(degrees: Float): Modifier =
    this
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.height, placeable.width) {
                placeable.placeRelative(
                    x = -(placeable.width - placeable.height) / 2,
                    y = -(placeable.height - placeable.width) / 2
                )
            }
        }
        .rotate(degrees)

// Convertit un code contrat "3K" → "3♦", "4P" → "4♠", "2S"/"2SA" → "2SA",
// "1CX" → "1♥X", "3TXX" → "3♣XX", etc.
fun formatContrat(contrat: String): String {
    if (contrat.isBlank()) return contrat
    val first = contrat.first()
    if (!first.isDigit()) return contrat
    var reste = contrat.drop(1).uppercase()
    val insulte = when {
        reste.endsWith("XX") -> { reste = reste.dropLast(2); "XX" }
        reste.endsWith("X")  -> { reste = reste.dropLast(1); "X" }
        else -> ""
    }
    val symbole = when (reste) {
        "P"       -> "♠"
        "C"       -> "♥"
        "K"       -> "♦"
        "T"       -> "♣"
        "S", "SA" -> "SA"
        else      -> reste
    }
    return "$first$symbole$insulte"
}

// Convertit un code entame "AsK" → "As♦", "10P" → "10♠", etc.
// (dernier caractère = code couleur P/C/K/T, le reste = la carte)
fun formatEntame(entame: String): String {
    if (entame.isBlank()) return entame
    val couleurCode = entame.last().toString()
    val carte = entame.dropLast(1)
    val symbole = when (couleurCode) {
        "P" -> "♠"
        "C" -> "♥"
        "K" -> "♦"
        "T" -> "♣"
        else -> couleurCode
    }
    return "$carte$symbole"
}

private val ORDRE_COULEUR = mapOf("P" to 0, "C" to 1, "T" to 2, "K" to 3)
private val ORDRE_VALEUR  = mapOf("A" to 0, "R" to 1, "D" to 2, "V" to 3, "10" to 4,
    "9" to 5, "8" to 6, "7" to 7, "6" to 8, "5" to 9, "4" to 10, "3" to 11, "2" to 12)

private fun trierCartes(cartes: List<Carte>): List<Carte> =
    cartes.sortedWith(compareBy({ ORDRE_COULEUR[it.couleur] ?: 99 }, { ORDRE_VALEUR[it.valeur] ?: 99 }))

@Composable
fun MainHorizontaleAdaptative(cartes: List<Carte>) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val largeurCarte = maxWidth / 13.5f
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            trierCartes(cartes).forEach { CarteUnifiee(it, largeurCarte, largeurCarte * 1.6f, fond = Color.White) }
        }
    }
}

// =========================================================================
// Reconstruire les enchères à partir du contrat
// =========================================================================

fun reconstruireEncheres(
    contrat: String,
    declarant: String,
    donneur: String
): List<AnnonceJoueur> {
    val encheres = mutableListOf<AnnonceJoueur>()

    val posDeclarant = when (declarant.uppercase()) {
        "NORD" -> "N"
        "SUD" -> "S"
        "EST" -> "E"
        "OUEST" -> "O"
        else -> declarant.uppercase().take(1)
    }

    val positions = listOf("O", "N", "E", "S")

    val indexDonneur = positions.indexOf(donneur.uppercase())
    val indexDeclarant = positions.indexOf(posDeclarant)

    if (indexDonneur == -1 || indexDeclarant == -1) {
        Log.e("TableauEncheres", "❌ Donneur ou déclarant invalide: donneur=$donneur, declarant=$posDeclarant")
        return emptyList()
    }

    var index = indexDonneur
    while (index != indexDeclarant) {
        encheres.add(AnnonceJoueur(positions[index], "Passe"))
        index = (index + 1) % 4
    }

    encheres.add(AnnonceJoueur(posDeclarant, contrat))

    index = (indexDeclarant + 1) % 4
    repeat(3) {
        encheres.add(AnnonceJoueur(positions[index], "Passe"))
        index = (index + 1) % 4
    }

    Log.i("TableauEncheres", "✅ Enchères reconstruites: ${encheres.size} annonces")
    encheres.forEach { Log.i("TableauEncheres", "   ${it.joueur}: ${it.annonce}") }

    return encheres
}

@Composable
fun TableauEncheres(
    encheres: List<AnnonceJoueur>,
    vulnerable: String,
    contrat: String = "",
    declarant: String = "",
    donneur: String = ""
) {
    val positionsSource = listOf("N", "E", "S", "O")
    val encheresFinales = if (encheres.isEmpty() && contrat.isNotEmpty() && declarant.isNotEmpty()) {
        Log.i("AffichageDonneEcran", "TableauEncheres🔄 Reconstruction des enchères depuis contrat: $contrat par $declarant")
        reconstruireEncheres(contrat, declarant, donneur)
    } else {
        encheres
    }

    val encheresAlignees = mutableListOf<String>()

    if (encheresFinales.isNotEmpty()) {
        val premierJoueur = encheresFinales.first().joueur
        val decalage = positionsSource.indexOf(premierJoueur)
        repeat(decalage) { encheresAlignees.add("") }
        encheresFinales.forEach { ann ->
            val texteOriginal = ann.annonce.uppercase()
            val texteFormate = when {
                texteOriginal == "PASSE" -> "Passe"
                texteOriginal == "X" || texteOriginal == "CONTRE" -> "X"
                texteOriginal == "XX" || texteOriginal == "SURCONTRE" -> "XX"
                else -> formatContrat(texteOriginal)
            }
            encheresAlignees.add(texteFormate)
        }
    }

    Surface(
        modifier = Modifier.width(220.dp).heightIn(max = 200.dp),
        color = Color.White,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, Color.Gray)
    ) {
        Column {
            Row(Modifier.fillMaxWidth()) {
                positionsSource.forEach { pos ->
                    val nom = when(pos) { "O" -> "OUEST"; "N" -> "NORD"; "E" -> "EST"; else -> "SUD" }
                    val isVulnerable = when (vulnerable.uppercase()) {
                        "NS" -> pos == "N" || pos == "S"
                        "EO" -> pos == "E" || pos == "O"
                        "T" -> true
                        else -> false
                    }
                    val fondCouleur = if (isVulnerable) Color(0xFFEF5350) else Color(0xFFECEFF1)
                    val texteCouleur = if (isVulnerable) Color.White else Color.Black
                    Box(
                        modifier = Modifier.weight(1f).background(fondCouleur).border(1.dp, Color(0xFF546E7A)).padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(nom, textAlign = TextAlign.Center, color = texteCouleur, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                val lignes = encheresAlignees.chunked(4)
                items(lignes) { ligne ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        for (i in 0..3) {
                            val texte = ligne.getOrNull(i) ?: ""
                            Box(
                                modifier = Modifier.weight(1f).fillMaxHeight().border(1.dp, Color(0xFF546E7A)).padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (texte.isNotEmpty())
                                    TextSuites(texte, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }
        }
    }
}
