package app.resultatsbridge.common.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resultatsbridge.R

private fun drawableForCode(code: String): Int = when (code) {
    "P" -> R.drawable.ic_pique
    "C" -> R.drawable.ic_coeur
    "K" -> R.drawable.ic_carreau
    else -> R.drawable.ic_trefle   // "T"
}

// SuiteSymbol : reçoit un CODE couleur ("P" "C" "K" "T")
@Composable
fun SuiteSymbol(couleur: String, color: Color, sizeDp: Float) {
    Image(
        painter = painterResource(drawableForCode(couleur)),
        contentDescription = null,
        modifier = Modifier.size(sizeDp.dp),
        colorFilter = ColorFilter.tint(color)
    )
}

// SymboleSuite : reçoit un SYMBOLE Unicode ("♣" "♠" "♥" "♦") ou du texte ("SA", "")
// Les 4 couleurs → Image vectorielle. Tout le reste (SA, vide) → Text.
@Composable
fun SymboleSuite(symbole: String, color: Color, sizeDp: Float) {
    val code = SuiteStyle.code(symbole)
    if (code.isNotEmpty()) {
        Image(
            painter = painterResource(drawableForCode(code)),
            contentDescription = null,
            modifier = Modifier.size(sizeDp.dp),
            colorFilter = ColorFilter.tint(color)
        )
    } else {
        Text(text = symbole, color = color, fontSize = sizeDp.sp)
    }
}

// TextSuites : affiche un texte avec les 4 symboles de suite sous forme de drawables vectoriels.
//   ♠ → ic_pique  (noir)    ♣ → ic_trefle (noir)
//   ♥ → ic_coeur  (rouge)   ♦ → ic_carreau (rouge)
// Les autres caractères héritent de la couleur passée en paramètre.
private const val INLINE_PIQUE   = "suite_pique"
private const val INLINE_TREFLE  = "suite_trefle"
private const val INLINE_COEUR   = "suite_coeur"
private const val INLINE_CARREAU = "suite_carreau"

@Composable
fun TextSuites(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE
) {
    val annotated = buildAnnotatedString {
        for (char in text) {
            when (char) {
                '♠' -> appendInlineContent(INLINE_PIQUE,   "♠")
                '♣' -> appendInlineContent(INLINE_TREFLE,  "♣")
                '♥' -> appendInlineContent(INLINE_COEUR,   "♥")
                '♦' -> appendInlineContent(INLINE_CARREAU, "♦")
                else -> append(char)
            }
        }
    }

    val inlineContent = mapOf(
        INLINE_PIQUE to InlineTextContent(
            Placeholder(fontSize, fontSize, PlaceholderVerticalAlign.TextCenter)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_pique),
                contentDescription = null,
                colorFilter = ColorFilter.tint(SuiteStyle.noir),
                modifier = Modifier.fillMaxSize()
            )
        },
        INLINE_TREFLE to InlineTextContent(
            Placeholder(fontSize, fontSize, PlaceholderVerticalAlign.TextCenter)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_trefle),
                contentDescription = null,
                colorFilter = ColorFilter.tint(SuiteStyle.noir),
                modifier = Modifier.fillMaxSize()
            )
        },
        INLINE_COEUR to InlineTextContent(
            Placeholder(fontSize, fontSize, PlaceholderVerticalAlign.TextCenter)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_coeur),
                contentDescription = null,
                colorFilter = ColorFilter.tint(SuiteStyle.rouge),
                modifier = Modifier.fillMaxSize()
            )
        },
        INLINE_CARREAU to InlineTextContent(
            Placeholder(fontSize, fontSize, PlaceholderVerticalAlign.TextCenter)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_carreau),
                contentDescription = null,
                colorFilter = ColorFilter.tint(SuiteStyle.rouge),
                modifier = Modifier.fillMaxSize()
            )
        }
    )

    Text(
        text = annotated,
        inlineContent = inlineContent,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = maxLines
    )
}
