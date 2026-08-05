package app.resultatsbridge.organisateur.bws

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/** Chemin partagé du fichier .bws (test ou futur réel) d'un tournoi. */
object BwsPaths {
    fun fichier(context: Context, idTournoi: Int): File {
        val dossier = File(context.getExternalFilesDir(null), "bws")
        return File(dossier, "tournoi_$idTournoi.bws")
    }

    // Copie un vrai fichier .bws fourni par FFBClubNet (choisi par l'organisateur
    // via un sélecteur de fichier) à l'emplacement attendu pour ce tournoi, à la
    // place du gabarit de test généré par BwsTestGenerator. BwsWriter peut ensuite
    // écrire les scores dedans sans distinguer les deux cas (même chemin de fichier).
    fun importerBwsExterne(context: Context, idTournoi: Int, source: Uri): File? {
        return try {
            val destination = fichier(context, idTournoi).apply { parentFile?.mkdirs() }
            context.contentResolver.openInputStream(source)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            Log.i("BwsPaths", "✅ .bws FFBClubNet importé : ${destination.absolutePath}")
            destination
        } catch (e: Exception) {
            Log.e("BwsPaths", "❌ Erreur import .bws externe", e)
            null
        }
    }
}
