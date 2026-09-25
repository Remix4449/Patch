manuel({
  id: "distance",
  titre: "Distance",
  type: "Convertisseur",
  resume: "Unités de longueur et conversions rapides (métrique, pouces, pieds).",
  reperes: [
    ["1 po", "2,54 cm"],
    ["1 pied", "30,48 cm"]
  ],
  texte: `
## Unités
| Unité | Symbole | En mètres |
|---|---|---|
| Millimètre | mm | 0,001 |
| Centimètre | cm | 0,01 |
| Mètre | m | 1 |
| Kilomètre | km | 1000 |
| Pouce | po (in) | 0,0254 |
| Pied | ft | 0,3048 |
| Yard | yd | 0,9144 |
| Mile | mi | 1609,34 |

## Conversions rapides
- 1 m = 100 cm = 1000 mm
- 1 pouce = 2,54 cm
- 1 pied = 12 pouces = 30,48 cm
- 1 yard = 3 pieds = 0,9144 m

> La diagonale d'un écran donnée en pouces : largeur et hauteur s'en déduisent avec le format (16:9, 4:3…), voir l'outil Projection.
`
});
