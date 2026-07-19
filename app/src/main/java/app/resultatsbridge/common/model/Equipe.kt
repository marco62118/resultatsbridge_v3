package app.resultatsbridge.common.model

import android.os.Parcel
import android.os.Parcelable
/**
 * Représente une équipe dans la base de données
 * Table : equipes (ID, equipe_numero, id_joueur1, id_joueur2, id_tournoi)
 */
data class Equipe(
    val equipeNumero: Int,//numéro d'une équipe dans le tournoi
    val joueur1: Joueur,
    val joueur2: Joueur,
    val idTournoi: Int,// ID du tournoi dans la table "tournois"
    val defaillante: Boolean = false // équipe déclarée défaillante par l'organisateur (equipes.defaillante)
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readParcelable(Joueur::class.java.classLoader)!!,
        parcel.readParcelable(Joueur::class.java.classLoader)!!,
        parcel.readInt(),
        parcel.readInt() != 0
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(equipeNumero)
        parcel.writeParcelable(joueur1, flags)
        parcel.writeParcelable(joueur2, flags)
        parcel.writeInt(idTournoi)
        parcel.writeInt(if (defaillante) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Equipe> {
        override fun createFromParcel(parcel: Parcel): Equipe = Equipe(parcel)
        override fun newArray(size: Int): Array<Equipe?> = arrayOfNulls(size)
    }
}
