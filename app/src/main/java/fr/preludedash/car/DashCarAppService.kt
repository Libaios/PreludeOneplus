package fr.preludedash.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Point d'entrée Android Auto. */
class DashCarAppService : CarAppService() {

    // Appli perso, non publiée : on accepte l'hôte Android Auto quel qu'il soit.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = DashSession()
}

class DashSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = DashScreen(carContext)
}
