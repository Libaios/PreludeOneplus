package fr.preludedash.music

import android.service.notification.NotificationListenerService

/**
 * Service vide : il n'existe que pour que l'utilisateur donne l'accès aux notifications,
 * condition imposée par Android pour piloter l'appli de musique en cours.
 */
class MusicListenerService : NotificationListenerService()
