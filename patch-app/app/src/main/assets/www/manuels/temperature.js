manuel({
  id: "temperature",
  titre: "Température",
  type: "Convertisseur",
  resume: "Conversion °C, °F, K et quelques repères usuels.",
  reperes: [
    ["0 °C", "32 °F"],
    ["100 °C", "212 °F"]
  ],
  texte: `
## Formules
- °F = °C × 9/5 + 32
- °C = (°F − 32) × 5/9
- K = °C + 273,15

## Repères
| °C | °F | Contexte |
|---|---|---|
| 0 | 32 | Gel de l'eau |
| 20 | 68 | Température de salle |
| 37 | 98,6 | Corps humain |
| 100 | 212 | Ébullition de l'eau |

! Une lampe à décharge ou un laser donnent leur température de couleur en kelvins (K), pas en °C : ne pas confondre avec la chaleur dégagée. Voir l'outil Gélatines pour les équivalences en kelvins.
`
});
