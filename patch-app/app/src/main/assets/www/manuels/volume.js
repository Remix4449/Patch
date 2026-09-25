manuel({
  id: "volume",
  titre: "Volume",
  type: "Convertisseur",
  resume: "Unités de volume et formules des formes courantes (caisse, fût, bouteille).",
  reperes: [
    ["1 m³", "1000 L"]
  ],
  texte: `
## Unités
| Unité | En litres |
|---|---|
| Millilitre (mL) | 0,001 |
| Litre (L) | 1 |
| Mètre cube (m³) | 1000 |
| Gallon US | 3,785 |

## Formes courantes
1. Pavé (caisse, flight-case) : longueur × largeur × hauteur
2. Cylindre (bouteille, fût) : π × rayon² × hauteur
3. Sphère : 4/3 × π × rayon³

> Pour une bouteille de brume ou de CO2, le volume utile se lit sur l'étiquette : le calcul géométrique donne le contenant, pas la charge.
`
});
