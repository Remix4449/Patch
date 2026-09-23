# Bibliothèque de manuels

Une fiche par appareil, dans ce dossier. L'écran **Manuels** (onglet Parc) les
range par type, les cherche en plein texte, et la recherche de l'accueil les
trouve aussi. Les fiches sont livrées dans l'APK : elles se lisent sans réseau,
et une fiche nouvelle arrive sur le téléphone avec la mise à jour suivante.

## Ajouter un manuel

1. Copier `mdg.js` sous un nouveau nom, en minuscules, sans espace ni accent :
   `robe-ledbeam-150.js`.
2. Remplir la fiche (voir plus bas). L'`id` reprend le nom du fichier.
3. Ajouter ce nom, sans `.js`, à la liste de `sommaire.js`.

C'est tout : pas d'autre fichier à toucher. Pour corriger une fiche, on modifie
son fichier ; pour la retirer, on le supprime et on enlève son nom du sommaire.

Pourquoi un sommaire : l'application est une page ouverte depuis les fichiers
de l'APK, elle ne sait pas lister un dossier. Et pourquoi un fichier par fiche :
une virgule oubliée dans l'une n'empêche pas les autres de s'afficher.

## La fiche

```js
manuel({
  id: "robe-ledbeam-150",          // obligatoire, = nom du fichier
  titre: "LEDBeam 150",            // obligatoire
  marque: "Robe",
  type: "Lyre beam",               // range et filtre la bibliothèque
  resume: "Une phrase qui dit ce qu'on trouve dans la fiche.",
  reperes: [                       // jusqu'à trois chiffres clés, en tête de fiche
    ["13 ch", "mode réduit"],
    ["200 W", "conso"]
  ],
  source: "Note de Rémi",          // facultatif, affiché en pied de fiche
  maj: "23 septembre 2026",        // facultatif, idem
  texte: `
## Adressage
1. Menu **Address**, régler l'adresse
2. Valider par Enter
> Choisir le mode avant l'adresse.

## Canaux, mode réduit
| Canal | Fonction |
|---|---|
| 1 | Pan |
| 2 | Tilt |

## Entretien
- Nettoyer la lentille
! Ne jamais ouvrir sous tension
`
});
```

Seuls `id` et `titre` sont obligatoires. Quand le `type` est celui d'une
famille de projecteurs (« Lyre beam », « Machine à brume »…), la rangée prend
son pictogramme ; sinon, celui du livre.

## Le texte

| On écrit | On obtient |
| --- | --- |
| `## Titre` | une nouvelle carte, avec ce titre |
| `1.` `2.` … | une étape numérotée |
| `- ` | une puce |
| `! ` | une alerte, filet rouge |
| `> ` | une remarque, filet violet |
| `\| a \| b \|` | une ligne de tableau ; la première est l'en-tête, la ligne `\|---\|` est ignorée |
| `**mot**` | du gras |
| autre chose | un paragraphe ; les lignes qui se suivent sont réunies, une ligne vide les sépare |

Le texte est entre deux accents graves (`` ` ``) : pour en écrire un dans la
fiche, il faut l'échapper (`` \` ``), de même que `${`. Le HTML n'est pas
interprété, il s'affiche tel quel.
