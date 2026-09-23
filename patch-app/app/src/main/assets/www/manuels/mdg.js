manuel({
  id: "mdg",
  titre: "MDG",
  marque: "MDG",
  type: "Machine à brume",
  resume: "Mise en service, extinction, et ce que dit l’écran quand la bouteille est vide.",
  reperes: [
    ["3,5 bar", "bouteille"]
  ],
  texte: `
## Mise en service
1. Bouteille : manomètre à 3,5 bar
2. Unit sur ON
3. Haze sur ON
4. Régler la pression suivant la quantité de fumée souhaitée

## Extinction
1. Unit sur OFF
2. Haze sur OFF
3. Pression à zéro
4. Vérifier sur les statuts que la purge est terminée

## Alerte
! Écran qui clignote = bouteille vide
`
});
