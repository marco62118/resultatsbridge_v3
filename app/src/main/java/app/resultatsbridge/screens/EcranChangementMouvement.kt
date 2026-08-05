// ÉCRAN : Changement de mouvement — affiche la prochaine table + adversaires, gère la table relais
package app.resultatsbridge.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resultatsbridge.common.model.ChangementDeMouvement
import app.resultatsbridge.common.model.TournoiConfig
import kotlinx.coroutines.launch

private const val TYPE_PAR4EQU2T21D      = "par4equ2t21d"
private const val TYPE_MITCHELL           = "Mitchell"
private const val TYPE_MITCHELL_GUERIDON  = "MitchellGueridon"

// ── Composables locaux ────────────────────────────────────────
@Composable
private fun TexteNormal(texte: String, color: Color = Color.Unspecified) {
    Text(
        text  = texte,
        style = MaterialTheme.typography.bodyLarge,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onBackground else color
    )
}

@Composable
private fun TexteGrand(texte: String, couleur: Color = Color.Unspecified) {
    Text(
        text       = texte,
        fontSize   = 22.sp,
        fontWeight = FontWeight.ExtraBold,
        color      = couleur
    )
}

// Phrase qui peut revenir à la ligne proprement (contrairement à un Row, dont les
// éléments se retrouvaient empilés à la verticale dès que la phrase devenait longue -
// signalé par Marco le 05/08/2026 après l'ajout du message de circulation des donnes).
// Chaque segment est soit du texte normal (couleur = null), soit un mot-clé en gros/gras
// coloré (couleur non-null) - mélangés dans un seul Text, qui sait retourner à la ligne.
@Composable
private fun TextePhrase(vararg segments: Pair<String, Color?>) {
    Text(
        text = buildAnnotatedString {
            segments.forEachIndexed { index, (texte, couleur) ->
                if (couleur != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = couleur)) {
                        append(texte)
                    }
                } else {
                    append(texte)
                }
                if (index < segments.lastIndex) append(" ")
            }
        },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground
    )
}

// =====================================================
//  ECRAN CHANGEMENT DE MOUVEMENT
// =====================================================
@Composable
fun EcranChangementMouvement(
    mvntTermineNumero: Int?,
    equipes: Pair<Int, Int>?,
    changement: ChangementDeMouvement?,
    equipeChoisie: Int?,
    typeTournoi: String = "",
    onVerifierFinMouvement: suspend () -> Boolean,
    onOk: () -> Unit = {},
    onClassementProvisoire: () -> Unit = {}
) {
    Log.i("EcranChangementMouvement", "APPEL écran changement de mouvement")
    Log.i("EcranChangementMouvement", "équipeChoisie=$equipeChoisie equipes=$equipes typeTournoi=$typeTournoi")

    val estPar4Equ2T21D     = (typeTournoi == TYPE_PAR4EQU2T21D)
    val estMitchell         = (typeTournoi == TYPE_MITCHELL)
    val estMitchellGueridon = (typeTournoi == TYPE_MITCHELL_GUERIDON)

    var enVerification by remember { mutableStateOf(false) }
    var messageAttente by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val couleurNS    = MaterialTheme.colorScheme.primary
    val couleurEO    = MaterialTheme.colorScheme.error
    val couleurTable = MaterialTheme.colorScheme.secondary

    val adversaireDirecte = if (equipeChoisie != null && equipes != null) {
        if (equipes.first == equipeChoisie) equipes.second else equipes.first
    } else null

    Log.i("EcranChangementMouvement", "adversaireDirecte=$adversaireDirecte")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text       = "Changement de mouvement",
            style      = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (changement == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(4.dp))
                Text("Récupération des nouvelles tables en cours...")
            }
        } else {

            if (messageAttente != null) {
                Text(
                    text       = messageAttente!!,
                    style      = MaterialTheme.typography.bodyMedium,
                    textAlign  = TextAlign.Center,
                    color      = Color.Red,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    text      = "Attendez le feu vert de l'Organisateur avant de changer de table",
                    style     = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color     = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            // Mitchell / MitchellGuéridon : NS ne bouge jamais, son numéro identifie
            // directement la table PHYSIQUE ou se trouve ce telephone (equipes.first =
            // le NS qu'on vient de jouer = notre table actuelle). Chercher par
            // equipeChoisie a la place se trompe de table des que equipeChoisie est le
            // camp EO (qui bouge) : on retombe alors sur l'entree de la table de
            // DESTINATION (ou EO apparait comme "adversaire" du mouvement suivant),
            // pas celle ou le telephone se trouve reellement - bug signale par Marco le
            // 05/08/2026 ("eq8 restez T4" affiche alors qu'on est physiquement a la
            // table 3, avec eq7/eq3). Le Howell n'a pas cette propriete (NS n'y est pas
            // fixe par table), donc garde l'ancienne recherche par equipeChoisie.
            val entriesTries = when {
                (estMitchell || estMitchellGueridon) && equipes != null ->
                    listOfNotNull(changement.entries.firstOrNull { it.equipe.equipeNumero == equipes.first })
                equipeChoisie != null -> {
                    val principale = changement.entries.firstOrNull { entry ->
                        entry.equipe.equipeNumero == equipeChoisie ||
                                entry.adversaire.equipeNumero == equipeChoisie
                    }
                    listOfNotNull(principale)
                }
                else -> changement.entries
            }
            entriesTries.forEachIndexed { index, entry ->

                val equipeEnPremier = when (index) {
                    0    -> equipeChoisie
                    else -> adversaireDirecte
                }

                val estRelais =
                    entry.adversaire.joueur1.nom.trim().lowercase() == "relais" ||
                            entry.adversaire.joueur2.nom.trim().lowercase() == "relais" ||
                            entry.equipe.joueur1.nom.trim().lowercase() == "relais" ||
                            entry.equipe.joueur2.nom.trim().lowercase() == "relais"

                val equipeEnPremierEstNS = equipeEnPremier == null ||
                        entry.equipe.equipeNumero == equipeEnPremier

                val equipeAffichee   = if (equipeEnPremierEstNS) entry.equipe    else entry.adversaire
                val equipeAdversaire = if (equipeEnPremierEstNS) entry.adversaire else entry.equipe
                val positionChoisie  = if (equipeEnPremierEstNS) "NS" else "EO"
                val positionAdverse  = if (equipeEnPremierEstNS) "EO" else "NS"
                val couleurChoisie   = if (equipeEnPremierEstNS) couleurNS else couleurEO
                val couleurAdv       = if (equipeEnPremierEstNS) couleurEO else couleurNS

                Card(
                    modifier  = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    colors    = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text  = "Mouvement ${changement.mvntSuivant}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        if (estRelais) {
                            // ── Équipe relais : message spécial ──────────────
                            val equipeReelle = if (
                                entry.equipe.joueur1.nom.trim().lowercase() != "relais" &&
                                entry.equipe.joueur2.nom.trim().lowercase() != "relais"
                            ) entry.equipe else entry.adversaire

                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TexteNormal("Équipe N°")
                                TexteGrand("${equipeReelle.equipeNumero}", couleurNS)
                                TexteNormal("allez à la")
                                TexteGrand("Table ${entry.tableNumero} Relais", couleurTable)
                            }

                        } else if (estMitchell || estMitchellGueridon) {
                            // ── Affichage spécifique Mitchell / Mitchell Guéridon ──────────────
                            // Écran partagé par les 2 équipes assises à la même table (1 seul
                            // téléphone par table) : on affiche toujours NS ET EO ensemble, plus
                            // besoin de choisir une "perspective" côté equipeChoisie. entry.equipe
                            // = NS et entry.adversaire = EO, toujours dans cet ordre pour Mitchell
                            // et MitchellGuéridon (voir getFuturMouvement, DatabaseManager.kt).
                            // Skip uniquement pour Mitchell standard (tables paires), jamais pour Guéridon
                            val aSaute = estMitchell && !estMitchellGueridon &&
                                    TournoiConfig.NBRE_TABLES > 0 &&
                                    TournoiConfig.NBRE_TABLES % 2 == 0 &&
                                    changement.mvntSuivant > TournoiConfig.NBRE_TABLES / 2
                            val couleurTableMitchell = if (aSaute) Color.Red else couleurTable

                            // NS reste sur place. Message des donnes (Guéridon uniquement)
                            // annoncé D'ABORD, suivi de "et restez Table X en NS" - demande
                            // explicite de Marco le 05/08/2026 (une seule phrase continue,
                            // au lieu de "vous restez" en premier puis le message des donnes
                            // sur une ligne séparée en dessous). Voir positionChaineGueridon
                            // côté DatabaseManager.kt pour la règle (vérifiée à la main sur
                            // 4 et 6 tables) :
                            //   - cas général : table (T+1)
                            //   - table nbreTables/2 : sur le Guéridon
                            //   - table nbreTables/2+1 : dépose EN PLUS son jeu joué
                            //     sur le Guéridon (c'est elle qui l'alimente)
                            //   - dernière table (nbreTables) : récupère à la table 1
                            //     (elle rejoue toujours le même jeu que la table 1)
                            val nbreTables    = TournoiConfig.NBRE_TABLES
                            val tableActuelle = entry.tableNumero
                            val texteAvantRestez = if (estMitchellGueridon && nbreTables > 0) {
                                when (tableActuelle) {
                                    nbreTables ->
                                        "Récupérez les donnes de la Table 1 et restez"
                                    nbreTables / 2 ->
                                        "Prenez votre set de donnes sur le Guéridon et restez"
                                    nbreTables / 2 + 1 ->
                                        "Déposez votre set de donnes sur le Guéridon, " +
                                                "puis prenez votre prochain set à la Table ${tableActuelle + 1} et restez"
                                    else ->
                                        "Prenez votre set de donnes à la Table ${tableActuelle + 1} et restez"
                                }
                            } else "Vous restez"

                            TextePhrase(
                                "Équipe" to null,
                                "${entry.equipe.equipeNumero}" to couleurNS,
                                texteAvantRestez to null,
                                "Table ${entry.tableNumero}" to couleurTableMitchell,
                                "en NS" to null
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // EO se déplace : ATTENTION, "entry.adversaire" est l'équipe EO
                            // qui ARRIVE à notre table (celle qui rejoint NS ci-dessus) - PAS
                            // l'équipe EO qui vient de jouer ICI et qui doit partir ! Ce n'est
                            // pas la même équipe des que EO ne reste pas sur place (le cas
                            // normal). Il faut chercher la propre destination de l'équipe EO
                            // partante (equipes.second), dans SA future entrée à elle, pas
                            // dans celle de notre table. Bug signale par Marco le 05/08/2026 :
                            // "eq4 allez T1" affiche (l'EO qui arrive) alors que l'equipe
                            // presente a l'ecran (eq1) doit en fait aller Table 2.
                            val entreePourEO = equipes?.let { (_, eoActuel) ->
                                changement.entries.firstOrNull { it.adversaire.equipeNumero == eoActuel }
                            }
                            val equipeEODepart = entreePourEO?.adversaire ?: entry.adversaire
                            val tableEODepart  = entreePourEO?.tableNumero ?: entry.tableNumero
                            if (aSaute) {
                                TextePhrase(
                                    "⚠️ Équipe" to null,
                                    "${equipeEODepart.equipeNumero} sauté à la Table $tableEODepart" to couleurTableMitchell,
                                    "en EO" to null
                                )
                            } else {
                                TextePhrase(
                                    "Équipe" to null,
                                    "${equipeEODepart.equipeNumero}" to couleurEO,
                                    "allez à la" to null,
                                    "Table $tableEODepart" to couleurTableMitchell,
                                    "en EO" to null
                                )
                            }

                        } else {
                            // ── Affichage Howell : comportement existant inchangé ──
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TexteNormal("Équipe N°")
                                TexteGrand("${equipeAffichee.equipeNumero}", couleurChoisie)
                                TexteNormal("allez table")
                                TexteGrand("${entry.tableNumero}", couleurTable)
                                TexteNormal("en")
                                TexteGrand(positionChoisie, couleurChoisie)
                            }
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TexteNormal("contre Équipe N°")
                                TexteGrand("${equipeAdversaire.equipeNumero}", couleurAdv)
                                TexteNormal("en")
                                TexteGrand(positionAdverse, couleurAdv)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Bouton classement provisoire (uniquement par4equ2t21d) ─────────
            if (estPar4Equ2T21D && mvntTermineNumero != null) {
                OutlinedButton(
                    onClick  = { onClassementProvisoire() },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("📊 Classement provisoire — après mouvement $mvntTermineNumero")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Bouton OK — aller au mouvement suivant ────────────────────────
            Button(
                onClick  = {
                    coroutineScope.launch {
                        enVerification = true
                        messageAttente = null
                        val toutesTerminees = onVerifierFinMouvement()
                        enVerification = false
                        if (toutesTerminees) {
                            onOk()
                        } else {
                            messageAttente = "⏳ Patientez — toutes les équipes n'ont pas encore terminé ce mouvement.\nAttendez le feu vert de l'Organisateur."
                        }
                    }
                },
                enabled  = !enVerification,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (enVerification) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("OK — Aller au mouvement suivant")
                }
            }
        }
    }
}
