// ACTIVITÉ : Tri de cartes mélangées selon une donne cible (aide à la duplication manuelle — V3)
// On choisit une donne cible déjà connue (saisie manuelle ou import PBN), on photographie
// les 4 mains avec le flux existant (AffichageMainsBatchScreen, non modifié — la correction
// croisée a besoin des 4 mains en même temps). Une fois validées, on les garde en mémoire
// (pas encore enregistrées) et on montre la répartition contre la donne cible un lot à la
// fois. À la fin, vérification (visuelle ou nouvelle photo comparée à la cible) puis
// enregistrement définitif dans "donnes avant tournoi".
package app.resultatsbridge.organisateur

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import app.resultatsbridge.common.ui.components.SuiteStyle
import app.resultatsbridge.common.ui.components.SuiteSymbol
import app.resultatsbridge.common.utils.RepartitionCartes
import app.resultatsbridge.organisateur.data.DatabaseManager
import app.resultatsbridge.screens.AffichageMainsBatchScreen
import app.resultatsbridge.screens.CardSlotPhoto
import kotlinx.coroutines.launch

private enum class PhaseTri { CHOIX_DONNE, CAPTURE_INITIALE, LOT_RESULTAT, VERIF_CHOIX, CAPTURE_VERIF, VERIF_RESULTAT }

class TriDuplicationActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BridgeServeurTheme {
                val scope = rememberCoroutineScope()
                var donneCibles by remember { mutableStateOf<List<DonneAvantTournoi>>(emptyList()) }
                var isLoading by remember { mutableStateOf(true) }
                var phase by remember { mutableStateOf(PhaseTri.CHOIX_DONNE) }
                var donneChoisie by remember { mutableStateOf<DonneAvantTournoi?>(null) }
                // Mains validées par la capture (4x13), gardées en mémoire, pas encore enregistrées en base.
                var mainsValidees by remember { mutableStateOf<List<List<Carte>>?>(null) }
                var lotIndex by remember { mutableStateOf(0) } // 0..3 : quel lot déjà capturé est affiché
                var mainsVerif by remember { mutableStateOf<List<List<Carte>>?>(null) }
                var messageFinal by remember { mutableStateOf("") }

                LaunchedEffect(Unit) {
                    scope.launch {
                        isLoading = true
                        donneCibles = DatabaseManager.getDonnesCibles(this@TriDuplicationActivity)
                        isLoading = false
                    }
                }

                fun enregistrerEtTerminer(mains: List<List<Carte>>) {
                    val numDonne = donneChoisie?.numeroDonne ?: return
                    scope.launch {
                        val mainsCodes = mains.map { main -> main.map { it.code } }
                        val ok = DatabaseManager.sauvegarderMainsAvantTournoi(
                            this@TriDuplicationActivity, numDonne, mainsCodes
                        )
                        if (ok) { finish() } else { messageFinal = "❌ Erreur lors de l'enregistrement" }
                    }
                }

                // Retour "un pas en arrière" — utilisé à la fois par le bouton rouge à l'écran
                // et par la touche/geste retour du système (neutralisé sinon il quitterait l'écran
                // en cours et ferait perdre le tri déjà fait).
                fun retourDepuisChoixDonne() { finish() }
                fun retourDepuisCaptureInitiale() { phase = PhaseTri.CHOIX_DONNE; donneChoisie = null }
                fun retourDepuisLot() {
                    if (lotIndex > 0) lotIndex-- else { phase = PhaseTri.CHOIX_DONNE; donneChoisie = null }
                }
                fun retourDepuisVerifChoix() { lotIndex = mainsValidees!!.size - 1; phase = PhaseTri.LOT_RESULTAT }
                fun retourDepuisCaptureVerif() { phase = PhaseTri.VERIF_CHOIX }
                fun retourDepuisVerifResultat() { phase = PhaseTri.VERIF_CHOIX }

                when (phase) {
                    PhaseTri.CHOIX_DONNE -> {
                        BackHandler(onBack = ::retourDepuisChoixDonne)
                        EcranChoixDonne(
                            donneCibles = donneCibles,
                            isLoading = isLoading,
                            onChoisir = { donne -> donneChoisie = donne; phase = PhaseTri.CAPTURE_INITIALE },
                            onRetour = ::retourDepuisChoixDonne
                        )
                    }

                    PhaseTri.CAPTURE_INITIALE -> {
                        BackHandler(onBack = ::retourDepuisCaptureInitiale)
                        AffichageMainsBatchScreen(
                            numeroDonne = donneChoisie!!.numeroDonne,
                            demarrerDirectement = true,
                            initialRotation90 = true,
                            onRetour = ::retourDepuisCaptureInitiale,
                            onEnregistrer = { mains -> mainsValidees = mains; lotIndex = 0; phase = PhaseTri.LOT_RESULTAT }
                        )
                    }

                    PhaseTri.LOT_RESULTAT -> {
                        BackHandler(onBack = ::retourDepuisLot)
                        val mains = mainsValidees!!
                        val resultatLot = RepartitionCartes.calculer(donneChoisie!!, mains[lotIndex].map { it.code })
                        val dernierLot = lotIndex == mains.size - 1
                        EcranRepartitionLot(
                            titre = "Lot ${lotIndex + 1}/${mains.size}",
                            lot = mains[lotIndex],
                            resultat = resultatLot,
                            texteBouton = if (dernierLot) "✅ Validé — passer à la vérification" else "✅ Validé — lot suivant",
                            onValide = { if (dernierLot) phase = PhaseTri.VERIF_CHOIX else lotIndex++ },
                            onRetour = ::retourDepuisLot
                        )
                    }

                    PhaseTri.VERIF_CHOIX -> {
                        BackHandler(onBack = ::retourDepuisVerifChoix)
                        EcranVerifChoix(
                            ordreDePose = ordreDePoseParPosition(mainsValidees!!, donneChoisie!!),
                            onVerifieVisuellement = { enregistrerEtTerminer(mainsValidees!!) },
                            onRepasserPhoto = { phase = PhaseTri.CAPTURE_VERIF },
                            message = messageFinal,
                            onRetour = ::retourDepuisVerifChoix
                        )
                    }

                    PhaseTri.CAPTURE_VERIF -> {
                        BackHandler(onBack = ::retourDepuisCaptureVerif)
                        AffichageMainsBatchScreen(
                            numeroDonne = donneChoisie!!.numeroDonne,
                            demarrerDirectement = true,
                            initialRotation90 = true,
                            onRetour = ::retourDepuisCaptureVerif,
                            onEnregistrer = { mains -> mainsVerif = mains; phase = PhaseTri.VERIF_RESULTAT }
                        )
                    }

                    PhaseTri.VERIF_RESULTAT -> {
                        BackHandler(onBack = ::retourDepuisVerifResultat)
                        EcranVerifResultat(
                            ordreDePose = ordreDePoseParPosition(mainsValidees!!, donneChoisie!!),
                            mainsVerif = mainsVerif!!,
                            message = messageFinal,
                            onValiderEtEnregistrer = { enregistrerEtTerminer(mainsVerif!!) },
                            onRetour = ::retourDepuisVerifResultat
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EcranChoixDonne(
    donneCibles: List<DonneAvantTournoi>,
    isLoading: Boolean,
    onChoisir: (DonneAvantTournoi) -> Unit,
    onRetour: () -> Unit
) {
    AppScaffold(ipAddress = "", numeroTournoi = null, equipeChoisie = null, nomEcran = "Trier un lot de cartes") {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Choisissez la donne cible", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Puis photographiez les 4 mains mélangées (smartphone en mode paysage), comme " +
                    "aujourd'hui : l'appli indiquera ensuite, lot par lot, à quel joueur chaque carte appartient.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(10.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    donneCibles.isEmpty() -> Text(
                        "Aucune donne cible disponible. Saisissez-en une ou importez un PBN d'abord.",
                        color = Color.Black
                    )
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(donneCibles) { donne ->
                            Card(
                                onClick = { onChoisir(donne) },
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
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            BoutonRetourRouge(onRetour)
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun EcranRepartitionLot(
    titre: String,
    lot: List<Carte>,
    resultat: RepartitionCartes.Resultat,
    texteBouton: String,
    onValide: () -> Unit,
    onRetour: () -> Unit
) {
    AppScaffold(ipAddress = "", numeroTournoi = null, equipeChoisie = null, nomEcran = titre) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Spacer(modifier = Modifier.height(6.dp))

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CarteJoueur("N", resultat, modifier = Modifier.fillMaxWidth(0.72f))
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CarteJoueur("O", resultat, modifier = Modifier.weight(1f))
                    CarteJoueur("E", resultat, modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(8.dp))
                CarteJoueur("S", resultat, modifier = Modifier.fillMaxWidth(0.72f))

                if (resultat.nonReconnues.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("⚠️ Non reconnues (${resultat.nonReconnues.size})", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Absentes de la donne cible — erreur de photo probable : " +
                                    resultat.nonReconnues.joinToString("   ") { Carte.fromCode(it).affichage },
                                fontSize = 14.sp,
                                color = Color.Black
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                LotAvecDestinations(lot, resultat)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BoutonRetourRouge(onRetour)
                Button(onClick = onValide, modifier = Modifier.weight(1f)) { Text(texteBouton) }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun EcranVerifChoix(
    ordreDePose: Map<String, List<String>>,
    onVerifieVisuellement: () -> Unit,
    onRepasserPhoto: () -> Unit,
    message: String,
    onRetour: () -> Unit
) {
    AppScaffold(ipAddress = "", numeroTournoi = null, equipeChoisie = null, nomEcran = "Vérification finale") {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Les 4 lots ont été triés", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Cible reconstituée dans l'ordre où les cartes ont été posées — comparez à vos 4 tas.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Black
            )
            if (message.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(message, color = Color(0xFFC62828), fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(8.dp))

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                RepartitionCartes.positions.forEach { position ->
                    Text(
                        nomPosition(position),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = couleurPosition(position)
                    )
                    LigneMain(codes = ordreDePose[position].orEmpty())
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            Button(
                onClick = onVerifieVisuellement,
                modifier = Modifier.fillMaxWidth()
            ) { Text("👁️ Vérifié visuellement — enregistrer") }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRepasserPhoto,
                modifier = Modifier.fillMaxWidth()
            ) { Text("📷 Repasser une photo de vérification") }
            Spacer(modifier = Modifier.height(8.dp))
            BoutonRetourRouge(onRetour)
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun EcranVerifResultat(
    ordreDePose: Map<String, List<String>>,
    mainsVerif: List<List<Carte>>,
    message: String,
    onValiderEtEnregistrer: () -> Unit,
    onRetour: () -> Unit
) {
    AppScaffold(ipAddress = "", numeroTournoi = null, equipeChoisie = null, nomEcran = "Résultat de la vérification") {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Comparaison avec la donne cible", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(10.dp))

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                RepartitionCartes.positions.forEachIndexed { i, position ->
                    val cible = ordreDePose[position].orEmpty()
                    val obtenu = mainsVerif[i].map { it.code }
                    val ok = obtenu.toSet() == cible.toSet()
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (ok) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "${if (ok) "✅" else "⚠️"} ${nomPosition(position)}",
                                fontWeight = FontWeight.Bold, fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Cible (ordre de pose)", fontSize = 12.sp, color = Color.DarkGray)
                            LigneMain(codes = cible)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Photo", fontSize = 12.sp, color = Color.DarkGray)
                            LigneMain(codes = obtenu)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (message.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(message, color = Color(0xFFC62828), fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BoutonRetourRouge(onRetour)
                Button(
                    onClick = onValiderEtEnregistrer,
                    modifier = Modifier.weight(1f)
                ) { Text("💾 Valider et enregistrer") }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

// Même composant que l'écran officiel de relecture d'une donne (AffichageMainsLectureScreen) :
// CardSlotPhoto — pas un composant différent (HandPreview/CarteUnifiee a un rendu visuel
// distinct, source de confusion). Pas de tri : les cartes restent dans l'ordre reçu (ordre de
// pose / ordre de la photo), pour pouvoir vérifier en retraçant ce qui a été fait physiquement.
@Composable
private fun LigneMain(codes: List<String>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardW = maxWidth.value / 13f
        val cardH = cardW * 2.2f
        Row(Modifier.fillMaxWidth()) {
            for (j in 0 until 13) {
                CardSlotPhoto(code = codes.getOrNull(j), isSelected = false, onClick = {}, widthDp = cardW, heightDp = cardH)
            }
        }
    }
}

// Reconstitue, pour chaque position, l'ordre dans lequel les cartes auraient été posées si le
// tri suit exactement ce que montrait l'écran de chaque lot (CarteJoueur : ordre inverse
// d'apparition sur la photo, lot 1 puis 2 puis 3 puis 4) — pour comparer la cible à un tas
// physique qui n'est jamais trié par couleur/valeur.
private fun ordreDePoseParPosition(mainsValidees: List<List<Carte>>, donneCible: DonneAvantTournoi): Map<String, List<String>> {
    val resultat = RepartitionCartes.positions.associateWith { mutableListOf<String>() }
    mainsValidees.forEach { lot ->
        val r = RepartitionCartes.calculer(donneCible, lot.map { it.code })
        RepartitionCartes.positions.forEach { position ->
            resultat.getValue(position).addAll(r.parJoueur[position].orEmpty().reversed())
        }
    }
    return resultat
}

// Même convention que le reste de l'appli (flèche blanche sur cercle rouge, voir
// AffichageMainsScreen.kt) : bouton retour explicite, distinct du retour système.
@Composable
private fun BoutonRetourRouge(onRetour: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onRetour() }.padding(4.dp)
    ) {
        Box(Modifier.size(48.dp).background(Color.Red, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Text("Retour", color = Color.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CarteJoueur(position: String, resultat: RepartitionCartes.Resultat, modifier: Modifier = Modifier) {
    val cartes = resultat.parJoueur[position].orEmpty()
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(nomPosition(position), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = couleurPosition(position))
            Spacer(modifier = Modifier.height(4.dp))
            ordreCouleurs.forEach { couleur ->
                // Ordre inverse d'apparition sur la photo (dernière carte du tas d'abord)
                val valeurs = cartes.filter { it.endsWith(couleur) }.map { it.dropLast(1) }.reversed()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SuiteSymbol(couleur = couleur, color = SuiteStyle.couleurCode(couleur), sizeDp = 18f)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(valeurs.joinToString("  "), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
                }
                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}

// Ordre d'affichage des couleurs (une ligne par couleur, même vide)
private val ordreCouleurs = listOf("P", "C", "K", "T")

// Le lot tel que photographié (ordre d'origine), avec la destination (N/E/S/O) au-dessus
// de chaque carte — variante d'affichage à comparer en rapidité avec les 4 cartes ci-dessus.
@Composable
private fun LotAvecDestinations(lot: List<Carte>, resultat: RepartitionCartes.Resultat) {
    val proprietaire = remember(resultat) {
        resultat.parJoueur.entries.flatMap { (position, cartes) -> cartes.map { it to position } }.toMap()
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Lot photographié — destination de chaque carte", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val spacing = 2.dp
            val largeurCase = (maxWidth - spacing * 12) / 13
            val hauteurCase = largeurCase * 2.2f
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                for (i in 0 until 13) {
                    val carte = lot.getOrNull(i)
                    val destination = carte?.let { proprietaire[it.code] } ?: "?"
                    Column(modifier = Modifier.width(largeurCase), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFE3F2FD), RoundedCornerShape(4.dp))
                                .padding(vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                destination,
                                fontWeight = FontWeight.Bold,
                                fontSize = (largeurCase.value * 0.75f).coerceIn(16f, 26f).sp,
                                color = if (carte != null) couleurPosition(destination) else Color.Black
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        CardSlotPhoto(
                            code = carte?.code,
                            isSelected = false,
                            onClick = {},
                            widthDp = largeurCase.value,
                            heightDp = hauteurCase.value
                        )
                    }
                }
            }
        }
    }
}

private fun nomPosition(position: String): String = when (position) {
    "N" -> "Nord"; "E" -> "Est"; "S" -> "Sud"; "O" -> "Ouest"; else -> position
}

// Une couleur par position, pour relier d'un coup d'œil la lettre au-dessus d'une carte
// et le pavé du joueur correspondant.
private fun couleurPosition(position: String): Color = when (position) {
    "N" -> Color.Black
    "E" -> Color(0xFF2E7D32) // vert
    "S" -> Color(0xFF1565C0) // bleu
    "O" -> Color(0xFFC62828) // rouge
    else -> Color.Black
}
