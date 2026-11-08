# Prelude Combiné

Combiné numérique pour la Prelude F20A, sur un téléphone dédié (OnePlus 7) fixé dans la
casquette à la place du combiné d'origine. Le combiné d'origine reste branché ailleurs et
garde le vrai kilométrage.

Projet dérivé de [Prelude Dash](https://github.com/Libaios/Prelude-dash) (même lecture du
datalog HTS en Bluetooth, même rendu 3D), mais pensé pour tourner seul :

- plein écran en paysage, la Prelude en 3D, régime, vitesse et AFR aux bords de l'écran ;
- s'allume tout seul quand l'alim arrive (alim USB sur un + après contact) ;
- se met en veille 15 s après la coupure (le démarreur coupe souvent l'alim une seconde) ;
- se reconnecte tout seul au module Bluetooth de l'ECU.

Appli distincte de Prelude Dash (identifiant `fr.preludeoneplus`) : elle ne remplace pas
celle du téléphone principal.

## Récupérer l'APK

Chaque modification poussée sur `main` est compilée par GitHub Actions. L'APK est dans
l'onglet **Releases** : `PreludeCombine.apk`. Il s'installe normalement (pas besoin
d'UnknownAuto) : ouvre-le et autorise l'installation d'applis inconnues.

## Installation sur le OnePlus (une fois)

1. Paramètres Android : verrouillage de l'écran sur **Aucun**, mise en veille de l'écran
   après **15 ou 30 s**.
2. Ouvre **Prelude Combiné**, autorise le Bluetooth, **Choisir le module** (ou **Mode démo**
   pour un essai sans la voiture).
3. Section **Combiné** : **Affichage par-dessus** et **Batterie sans limite**. Toutes les lignes
   doivent passer au ✓.

Sur le combiné, un appui change de page, un appui long ouvre les réglages.

Un seul appareil peut parler au module Bluetooth : quand le combiné est connecté, Prelude Dash
sur le téléphone principal n'a pas de données.

## Pourquoi il ne reste pas bloqué sur l'écran de verrouillage

Le combiné s'affiche par-dessus l'écran de verrouillage et rallume l'écran lui-même. Avec le
verrouillage sur « Aucun », il n'y a de toute façon aucun écran de verrouillage, même après un
redémarrage. Un code PIN, lui, bloquerait l'appli après un redémarrage tant qu'on ne l'a pas
tapé.

## Technique

- Veille : un service au premier plan (notification discrète) attend le branchement de l'alim,
  réveille l'écran et ouvre le combiné. Il redémarre avec le téléphone.
- Ouverture du combiné : directe grâce à l'autorisation « affichage par-dessus », avec une
  notification plein écran en secours.
- Liaison ECU : on envoie `0x20` sur le CN2, l'ECU répond une trame de 52 octets (protocole
  ISR V3), comme dans Prelude Dash.
