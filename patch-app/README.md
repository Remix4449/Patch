# Patch — inventaire régie (Android)

Application de plateau : l'inventaire du matériel, les calculs DMX, et trois
outils réseau qui fonctionnent sur un réseau 100 % local, sans internet et sans
ordinateur.

## Récupérer l'application

L'APK est construit par GitHub Actions à chaque modification et déposé dans la
release `apk` du dépôt. La première fois, depuis le téléphone :

1. ouvrir la page des releases du dépôt, section **Patch — dernière version** ;
2. télécharger `patch-regie.apk` ;
3. l'ouvrir — Android demande d'autoriser l'installation depuis cette source.

Aucun compte, aucun store, aucun ordinateur. Ensuite l'application se met à jour
toute seule, et n'a besoin d'internet que pour ça : sur le plateau, elle continue
de tourner sans.

## La mise à jour

À chaque lancement, l'application regarde la release du dépôt, compare le numéro
de version publié au sien, et rapatrie l'APK sans rien demander si elle est en
retard. Un bandeau apparaît alors sur l'accueil : *Installer*. L'écran **Mise à
jour**, dans les outils, dit la version installée, la version publiée et d'où
elle a été construite, et permet de vérifier à la main.

**Ce qui reste manuel, et le restera.** Android interdit à une application
installée hors magasin d'en installer une autre en silence : le dernier geste
passe forcément par l'écran d'installation du système, qu'il faut confirmer. La
toute première fois, Android demande en plus d'autoriser Patch à installer des
applications — une case à cocher, une seule fois. Tout le reste — vérifier,
télécharger, savoir qu'il y a du nouveau — se fait sans rien toucher.

Quelques détails qui ont leur importance :

- **le numéro de version avance tout seul.** Le numéro de construction de GitHub
  Actions devient le `versionCode` de l'APK et le `versionName` affiché
  (`1.0.42`). Sans ça, comparer deux versions n'aurait aucun sens ;
- **la fiche `version.json`** est déposée dans la release à côté de l'APK, et
  porte le numéro, l'empreinte du commit, la branche et la taille. C'est un
  fichier public servi par GitHub : aucun compte, aucun jeton, aucune limite
  d'appels ;
- **la vérification sort par un réseau qui a vraiment internet.**
  `Reseau.java` épingle le processus sur le Wi-Fi du plateau, qui n'a souvent
  aucun accès extérieur ; `Maj.java` demande explicitement à
  `ConnectivityManager` un réseau validé, ce qui fait passer la vérification par
  la 4G le cas échéant. Sans ce détour, elle échouerait là où elle sert le plus ;
- **l'APK est servi par un `content://`.** Depuis Android 7, passer un `file://`
  à une autre application lève une exception. `FournisseurApk.java` est un
  fournisseur minuscule qui ne sert que ce fichier-là — le projet n'ayant pas
  d'AndroidX, il n'a pas de `FileProvider` à sa disposition ;
- **la clé de signature est dans le dépôt, exprès.** Android refuse une mise à
  jour signée par une autre clé que la version en place, et une construction sans
  clé fixe en fabrique une nouvelle à chaque passage. Voir `cle/README.md` ;
- **toute branche poussée remplace l'APK de la release.** Le workflow se
  déclenche sur n'importe quelle poussée touchant `patch-app/` : une correction
  en cours de relecture arrive donc sur le téléphone. C'est pratique pour
  essayer un correctif, et c'est à savoir. La branche d'origine est écrite dans
  l'écran *Mise à jour* quand ce n'est pas `main`.

## Ce que fait l'application

| Écran | Fonctionnement |
| --- | --- |
| Projecteurs, machinerie, hauteurs | Recherche, filtres, et l'inventaire se modifie depuis l'application |
| MDG | Procédure embarquée, hors ligne |
| Patch | Un seul écran : la feuille du spectacle, la télécommande en barre, le relevé, l'ajout d'appareils et la télécommande DMX en volets, l'impression |
| Gélatines | Lee → RGBWA, RGBW, RGB ou CMY, teintes approchées à recaler |
| Réseau | Balayage du /24 : ICMP quand le système l'autorise, sinon TCP |
| Art-Net / sACN | Découverte des nœuds, recensement des univers, niveaux en direct |
| Flux NDI | Découverte mDNS `_ndi._tcp` : nom, machine, adresse, port |
| Testeur d'adresse | Émission d'une trame Art-Net sur un canal, sans console |

## L'inventaire se tient depuis l'application

Le parc livré avec l'APK vient des bases Notion : c'est un point de départ, pas
une vérité. Un projecteur arrive, un moteur part en réparation, une passerelle
est remesurée — les trois listes s'ajoutent, se corrigent et se vident depuis
l'application.

- Le crayon au bout d'une rangée ouvre sa fiche ; le bouton en pied de liste en
  ouvre une vide. Le retrait demande deux touches : la première prévient, la
  seconde exécute.
- Ce qui est modifié est gardé dans la clé locale `inventaire.v1` et remplace la
  liste livrée au chargement suivant. Rien ne part sur le réseau. Vider les
  données de l'application ramène le parc d'origine.
- Renommer un projecteur déplace ce qui le désignait : ses modes DMX et les
  lignes d'appareils des patchs, qui le nomment « marque + nom ».

Chaque liste a sa barre de recherche — elle traverse tous les champs de la
fiche, « 1000 W » ou « 8 m/min » trouvent aussi bien qu'un nom — et son bandeau
de filtres : les marques pour les projecteurs, les types pour la machinerie. Le
bandeau garde sa position quand on appuie dessus, et la page ne remonte plus en
haut.

## Le patch et le plateau, sur un seul écran

Le patch et la télécommande faisaient le même travail — savoir ce qui est
branché où — sans jamais se parler : le circuit s'écrivait deux fois, et le
clavier disparaissait dès qu'on descendait dans la feuille pour le noter. Ils
n'en font plus qu'un.

**La liste est au centre**, en trois onglets : tout, les appareils, les
gradateurs. La rangée est la même pour les deux — badge, désignation, adresse
DMX à droite, une pastille. Toucher la pastille ouvre la fiche de l'élément :
changer son numéro de circuit, corriger son repère, ou le retirer de la
feuille. Toucher la rangée l'allume : un gradateur à son canal, un appareil à
son adresse — le premier canal à fond, ce qui suffit à la plupart des
projecteurs, et « tous les canaux » dans sa fiche pour les autres.

Sur deux cents gradateurs, la liste n'affiche que ceux qui portent déjà un
circuit ; les autres vivent dans le relevé, et un bouton de l'en-tête les rend
visibles le temps d'une vérification.

**La télécommande tient en une barre**, au-dessus de la liste : le gradateur
appelé, son adresse, son circuit, le niveau au doigt, Noir, Full, précédent,
suivant, Zéro. Un chevron la replie sur une seule ligne — le gradateur, son
niveau, et de quoi éteindre — quand c'est la liste qui compte. **Le petit logo
télécommande** qui ouvre la barre, replié ou non, mène à la télécommande DMX ;
il s'allume tant qu'elle tient un canal.

**La télécommande DMX**, elle, ne parle pas gradateurs. La barre appelle un
numéro de gradateur et passe par la plage du patch : elle ne sait donc rien dire
d'un canal qui n'en fait pas partie — une lyre, un nœud à vérifier, un circuit
pas encore relevé. Le volet **Télécommande DMX** émet en clair : un univers, un
canal, une valeur de 0 à 255. L'univers et le canal se tapent au pavé, le
niveau se tient, et l'écran dit ce que la feuille a posé à cette adresse —
« Gradateur 12 · circuit 5 », « PC 1000 n° 3 · canal 1 sur 6 », ou rien. Les
canaux tenus se rappellent d'une pastille, « Canaux à zéro » les relâche tous,
et quitter l'écran relâche aussi. Là où un canal croise un gradateur de la
feuille, c'est le plus haut des deux niveaux qui sort, comme sur un pupitre. Le
protocole, la priorité et la destination sont ceux du patch.

**Deux volets** s'ouvrent depuis le bas et recouvrent la liste à moitié, sans
la remplacer : on voit la rangée se remplir pendant qu'on écrit.

- **Relever** : le gradateur s'allume, on regarde la scène, on écrit le
  circuit, et « Noté » saute au suivant *non relevé* en l'allumant. La liste se
  déplace toute seule dessus. Toucher le grand numéro ouvre un pavé pour
  appeler un gradateur précis — le 147 se tape, il ne se cherche pas ; un numéro
  au-delà de la plage l'allonge. « + un gradateur » et « Supprimer » sont là
  aussi.
- **Ajouter** : une ligne par type d'appareil — l'appareil, son mode ou son
  empreinte, la quantité, **l'univers et la première adresse**, chaque champ
  sous son libellé — avec l'étendue calculée en regard. Écrire un univers ou une
  adresse impose le départ de la ligne ; l'appui sur l'étendue bascule entre
  « à la suite » et départ imposé. Une ligne neuve arrive d'emblée sur la
  **première adresse libre** : la première plage assez large qui ne croise ni un
  appareil déjà posé, ni un gradateur de la feuille. Les chevauchements sont
  comptés, la ligne fautive signalée, et « adresse libre » la repose ailleurs
  d'une touche.

Toucher une rangée ouvre sa **fiche**. Celle d'un appareil porte son circuit,
son **type**, son **mode** et son **adresse** — univers et canal de départ, qui
se changent là, appareil par appareil : la ligne se scinde toute seule pour que
les autres exemplaires gardent le leur et leur adresse. Celle d'un gradateur ne
porte pas d'adresse à écrire — elle se déduit de la plage, univers et première
adresse en tête — mais mène d'une touche aux réglages où cette plage se règle. Comme la nouvelle empreinte peut être plus large que l'ancienne,
la fiche dit alors en clair quels appareils sont chevauchés, et propose de
décaler celui-ci à la première adresse libre.

L'émission est tenue, pas envoyée une fois : un récepteur sACN relâche un
univers après quelques secondes sans trame, et un nœud Art-Net fait de même.
`Emetteur.java` répète donc les univers posés à 30 Hz tant que l'écran est
ouvert. Quitter l'écran relâche proprement — trois trames à zéro, marquées fin
de flux en sACN — plutôt que de laisser le plateau allumé sur la dernière
valeur reçue.

## Un patch par spectacle

Chaque création a le sien. Le nom en haut de l'écran ouvre le menu : ouvrir un
autre patch, en créer un, dupliquer celui-ci, le renommer, le supprimer. Un
patch garde ses lignes d'appareils, les circuits relevés, les repères, les
gradateurs retirés et ses **réglages de flux** — protocole, univers, première
adresse, premier numéro, nombre de gradateurs, priorité sACN, destination. Une
reprise dans une autre salle n'a ni le même univers ni le même nombre de
gradateurs. Changer de patch éteint le plateau.

Tout est enregistré sur le téléphone, sous la clé `patchs.v2`, et n'en sort
jamais. Les niveaux, eux, ne sont pas enregistrés : rien ne doit rallumer un
plateau au lancement.

**Reprise de l'ancien format.** Les patchs faits avant cette refonte vivaient
dans deux clés séparées, `patch.v1` et `gradateurs.v1`, où le circuit était
écrit deux fois sans que l'une sache ce que l'autre contenait. Au premier
lancement, l'application en fait un premier spectacle : les lignes d'appareils,
leurs circuits — recalés du couple « ligne:rang » vers le numéro d'appareil que
la liste affiche —, la plage de gradateurs et leurs relevés. Les anciennes clés
sont laissées en place, au cas où il faudrait revenir en arrière.

### Impression

Une seule feuille là où il en fallait deux. Le bouton **PDF** ouvre la boîte
d'impression d'Android, qui sait enregistrer en PDF ou envoyer à une
imprimante. La feuille A4 porte le nom du patch, la date, les totaux, puis
trois tableaux : les gradateurs relevés (numéro, univers, adresse, circuit,
repère), le récapitulatif **par circuit** — avec ses gradateurs *et* les
appareils qui y sont branchés, ce qu'aucune des deux feuilles d'avant ne
pouvait dire —, et le patch appareil par appareil. Les correspondances Art-Net
et sACN de chaque univers ferment la page.

### Modes DMX

La fiche de chaque appareil porte sa bibliothèque de modes. Deux façons de la
remplir :

- **à la main** : nom du mode et nombre de canaux, dix secondes par appareil ;
- **par import GDTF** : le bouton ouvre le sélecteur de fichiers, l'application
  décompresse l'archive, lit `description.xml` et récupère tous les modes avec
  leur empreinte exacte — l'empreinte d'un mode étant le plus grand décalage
  déclaré par ses canaux, comme le veut le format.

Les fichiers GDTF se téléchargent sur gdtf-share.com (compte gratuit, connexion
requise). Une fois importés, ils restent dans l'application : le plateau n'a
jamais besoin d'internet. Aucun mode n'est livré d'avance — les données de
modes ne sont pas dans les bases Notion et ne seront pas inventées.

## Détails de protocole

**Art-Net** (UDP 6454). `ArtPoll` en diffusion, puis lecture des `ArtPollReply` :
nom court et long, MAC, univers de chaque port. Les `ArtDmx` reçus alimentent à
la fois le recensement des univers et l'affichage des 512 niveaux. Les univers
sont manipulés en adresse de port (base 0) et affichés en base 1, comme sur les
pupitres.

**sACN / E1.31** (UDP 5568). Écoute multicast sur `239.255.<hi>.<lo>`. Un univers
n'est reçu que si son groupe a été rejoint : le recensement passe donc par
l'univers de découverte 64214, que les sources annoncent toutes les dix secondes.
Priorité et nom de source sont lus dans la couche de trame. À l'émission,
l'application compose la trame de données complète — 638 octets, couche racine,
couche de trame et couche DMP — avec un CID tiré au sort au lancement, une
numérotation de séquence par univers, et le bit de fin de flux quand elle
relâche un univers. Le retour de boucle multicast est coupé : nos propres
trames ne viennent pas se recenser comme une source de plus.

**NDI**. La découverte mDNS suffit à lister les sources et n'a besoin d'aucune
bibliothèque. La vignette en direct demanderait le NDI Advanced SDK et un
décodage SpeedHQ — c'est le seul morceau non couvert.

## Deux précautions de terrain

`Reseau.java` épingle le processus sur le Wi-Fi (`bindProcessToNetwork`) et garde
un `MulticastLock`. Sans le premier, Android route les paquets vers la 4G dès que
le Wi-Fi n'a pas d'accès internet — exactement le cas d'un réseau de plateau.
Sans le second, le multicast sACN n'arrive jamais jusqu'à l'application.

Reste un point hors de portée du code : si la borne Wi-Fi filtre le multicast ou
isole les clients entre eux, rien ne passera. C'est à vérifier sur le point
d'accès du lieu.

## Reconstruire

```
cd patch-app
gradle assembleDebug        # ou ./gradlew si le wrapper est présent
```

Une construction à la main garde le numéro de version 1 et s'affiche
`1.0.1-local` : elle se croira donc toujours en retard sur la release. La chaîne
de montage passe les vrais numéros avec `-PversionCode` et `-PversionName`.

Projet Android nu : une `WebView` plein écran, l'interface dans
`app/src/main/assets/www`, et la couche réseau en Java dans
`app/src/main/java/fr/regie/patch`. Pas d'AndroidX, pas de npm, aucune
dépendance externe. Les polices sont embarquées.

`net.js` fait le lien entre les deux : quand `window.Regie` n'existe pas —
c'est-à-dire dans un navigateur — il rejoue les jeux de démonstration. La même
interface tourne donc en web pour la mise au point et en natif sur le terrain.

## Non testé sur matériel

Les analyseurs Art-Net et sACN, et l'émission de la télécommande, sont écrits
d'après les spécifications et n'ont pas encore vu de vrai gradateur. La trame
sACN émise a été vérifiée octet par octet contre la norme et relue par
l'analyseur de l'application, ce qui ne remplace pas un essai au plateau : le
premier branchement dira si les gradateurs répondent.
