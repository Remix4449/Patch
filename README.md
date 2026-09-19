# Patch

Application de plateau pour la régie : l'inventaire du matériel, la construction
du patch DMX, et les outils réseau qui vont avec. Tout fonctionne sur un réseau
100 % local, sans internet, sans ordinateur et sans compte.

Le code vient du dépôt `ws-timeline`, où il avait été écrit ; il vit ici
désormais, avec son historique.

## Ce que contient le dépôt

| Dossier | Contenu |
| --- | --- |
| `patch-app/` | L'application Android : une `WebView` plein écran, l'interface en HTML/JS dans `app/src/main/assets/www`, la couche réseau en Java dans `app/src/main/java/fr/regie/patch` |
| `inventaire/` | Les trois canevas d'interface qui ont précédé l'application, ouvrables tels quels dans un navigateur |
| `canevas/` | L'atelier de refonte du patch et de la télécommande : huit maquettes manipulables, dont le canevas H retenu |
| `.github/workflows/apk.yml` | La construction de l'APK et sa publication dans la release `apk` |

`patch-app/README.md` détaille les écrans, les formats de trame Art-Net et sACN,
et les deux précautions de terrain côté Android. `inventaire/README.md` décrit
les canevas d'origine, `canevas/README.md` ceux de la refonte en cours.

## Installer l'application

L'APK est construit par GitHub Actions à chaque modification de `patch-app/` et
déposé dans la release `apk` du dépôt. La première fois, depuis le téléphone :

1. ouvrir la page des releases, section **Patch — dernière version** ;
2. télécharger `patch-regie.apk` ;
3. l'ouvrir — Android demande d'autoriser l'installation depuis cette source.

Ensuite, l'application se met à jour toute seule : à chaque lancement elle
compare sa version à celle publiée dans la release, rapatrie l'APK si elle est en
retard, et propose l'installation d'une touche. Seule la confirmation reste
manuelle — Android n'autorise pas une application hors magasin à en installer une
autre en silence. `patch-app/README.md` détaille le mécanisme.

## Lancer l'interface dans un navigateur

L'interface se met au point sans Android. `net.js` rejoue des jeux de
démonstration dès que `window.Regie` n'existe pas, c'est-à-dire hors de
l'application :

```
cd patch-app/app/src/main/assets/www
python3 -m http.server 8000
```

puis `http://localhost:8000` dans un navigateur. Le patch, les gélatines et
l'export PDF fonctionnent — l'export passe par l'impression du navigateur au
lieu de la boîte d'impression d'Android. Le réseau, l'Art-Net, le sACN et le NDI
affichent les jeux de démonstration, puisqu'une page web seule ne sait pas ouvrir
de socket UDP. L'import GDTF, lui, demande le sélecteur de fichiers d'Android et
n'est disponible que dans l'application.

Les canevas de `inventaire/` s'ouvrent directement, sans serveur.

## Construire l'APK

Il faut un JDK 17 et le SDK Android (plateforme 34, build-tools 34.0.0) :

```
cd patch-app
gradle assembleDebug
```

L'APK sort dans `patch-app/app/build/outputs/apk/debug/app-debug.apk`.

Le projet est volontairement nu : pas d'AndroidX, pas de npm, aucune dépendance
externe, les polices sont embarquées. Le seul téléchargement est celui du plugin
Android Gradle, la première fois.

Il n'y a pas de wrapper Gradle dans le dépôt : `gradle` doit être installé
(la version 8.7 est celle utilisée par la chaîne de construction). Pour le
poser une fois pour toutes :

```
cd patch-app
gradle wrapper --gradle-version 8.7
```

## Construction automatique

Le workflow `apk.yml` se déclenche sur toute modification de `patch-app/` ou du
workflow lui-même, et peut aussi être lancé à la main depuis l'onglet Actions.
Il installe Java 17 et les composants Android, construit l'APK en `debug`, le
dépose comme artefact pour 90 jours, et met à jour la release fixe `apk` pour
qu'un téléphone puisse la télécharger directement.
