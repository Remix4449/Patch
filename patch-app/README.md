# Patch — inventaire régie (Android)

Application de plateau : l'inventaire du matériel, les calculs DMX, et trois
outils réseau qui fonctionnent sur un réseau 100 % local, sans internet et sans
ordinateur.

## Récupérer l'application

L'APK est construit par GitHub Actions à chaque modification et déposé dans la
release `apk` du dépôt. Depuis le téléphone :

1. ouvrir la page des releases du dépôt, section **Patch — dernière version** ;
2. télécharger `patch-regie.apk` ;
3. l'ouvrir — Android demande d'autoriser l'installation depuis cette source.

Aucun compte, aucun store, aucun ordinateur. Une fois installée, l'application
ne demande plus jamais internet.

## Ce que fait l'application

| Écran | Fonctionnement |
| --- | --- |
| Projecteurs, machinerie, hauteurs, MDG | Données embarquées, hors ligne |
| Patch | Lignes d'appareils, adressage enchaîné, circuits et numéros de patch, export PDF |
| Télécommande | Clavier de gradateurs en sACN ou Art-Net, feuille de circuits, export PDF |
| Gélatines | Lee → RGBWA, teintes approchées à recaler |
| Réseau | Balayage du /24 : ICMP quand le système l'autorise, sinon TCP |
| Art-Net / sACN | Découverte des nœuds, recensement des univers, niveaux en direct |
| Flux NDI | Découverte mDNS `_ndi._tcp` : nom, machine, adresse, port |
| Testeur d'adresse | Émission d'une trame Art-Net sur un canal, sans console |

## Le patch

On pose une ligne par type d'appareil : l'appareil, son mode, la quantité, et
l'adresse de départ — ou « à la suite » de la ligne précédente. L'application
enchaîne les adresses en respectant l'empreinte du mode, passe à l'univers
suivant quand un appareil ne tient plus dans les 512 canaux, et affiche la liste
appareil par appareil. Chaque ligne porte deux champs libres, **circuit** et
**numéro de patch**, qui sont à toi et qui partent dans le PDF.

Le patch est enregistré sur le téléphone et survit au redémarrage.

### Export PDF

Le bouton *Exporter en PDF* ouvre la boîte d'impression d'Android, qui sait
enregistrer en PDF ou envoyer à une imprimante. La feuille tient en A4 : en-tête
avec le nom du patch et les totaux, tableau N° / circuit / patch / appareil /
mode / canaux / univers / adresse / fin, récapitulatif par type et
correspondances réseau par univers. La puissance totale est calculée depuis
l'inventaire, avec un astérisque si un appareil n'a pas de puissance renseignée.

## La télécommande de gradateurs

Le geste du plateau, sans monter en régie : on appelle un gradateur au clavier,
il s'allume, on regarde quel circuit vient de s'éclairer, et on l'écrit dans la
feuille — le tout depuis la scène, téléphone en main.

L'écran tient en trois morceaux :

- **le clavier** : chiffres, `C` pour effacer la frappe, `✓` pour appeler le
  gradateur, `−` et `+` pour le niveau par pas réglable, *Noir* et *Full*, et
  précédent / suivant pour balayer le gradateur voisin. En mode « un seul
  gradateur à la fois », appeler le suivant éteint le précédent ;
- **la feuille de patch** : une ligne par gradateur — son numéro, son adresse
  DMX calculée, le circuit qu'il alimente et un repère libre. Toucher le numéro
  appelle le gradateur. Plusieurs gradateurs peuvent porter le même circuit :
  c'est le cas courant d'un circuit doublé ;
- **la vue par circuit** : chaque circuit avec la liste de ses gradateurs.
  Toucher un circuit allume tous ses gradateurs d'un coup, ce qui vérifie un
  doublage en une touche.

La feuille s'exporte en PDF par la boîte d'impression d'Android : tableau
gradateur / univers / adresse / circuit / repère, puis le récapitulatif par
circuit. Elle est enregistrée sur le téléphone et survit au redémarrage.

Les réglages tiennent dans le volet *Réglages du flux* : protocole (sACN ou
Art-Net), univers de départ, numéro du premier gradateur et son adresse DMX,
nombre de gradateurs, pas du `+` / `−`, priorité sACN et destination. Un
gradateur au-delà du canal 512 passe tout seul à l'univers suivant. Sans
destination, sACN part en multicast et Art-Net en diffusion ; une adresse IP
force l'envoi vers ce seul nœud.

**L'émission est tenue, pas envoyée une fois.** Un récepteur sACN relâche un
univers après quelques secondes sans trame, et un nœud Art-Net fait de même :
`Emetteur.java` répète donc les univers posés à 30 Hz tant que l'écran est
ouvert. Quitter l'écran relâche proprement — trois trames à zéro, marquées fin
de flux en sACN — plutôt que de laisser le plateau allumé sur la dernière
valeur reçue.

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
