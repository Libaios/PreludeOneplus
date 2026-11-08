package fr.preludedash.shared

import android.net.Uri

/** Le canal entre Prelude Dash (qui parle à l'ECU) et Prelude Carte (la carte à côté de Waze). */
object ShareContract {
    const val AUTHORITY = "fr.preludedash.share"
    val URI: Uri = Uri.parse("content://$AUTHORITY")

    /** Renvoie la carte à afficher, et garde la liaison ECU allumée tant qu'on la demande. */
    const val M_CARD = "card"

    /** Commande musique : arg = une des valeurs ci-dessous. */
    const val M_MUSIC = "music"
    const val MUSIC_TOGGLE = "toggle"
    const val MUSIC_PLAY = "play"
    const val MUSIC_NEXT = "next"
    const val MUSIC_PREVIOUS = "previous"
}
