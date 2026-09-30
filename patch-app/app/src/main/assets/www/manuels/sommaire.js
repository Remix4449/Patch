/* ---------------------------------------------------------------------------
   Bibliothèque de manuels — le sommaire
   Une fiche par appareil, dans ce dossier. Pour en ajouter une : créer le
   fichier (copier `mdg.js`), puis écrire son nom ci-dessous. Le mode d'emploi
   complet est dans `README.md`, à côté.

   L'application est une page ouverte depuis les fichiers de l'APK : elle ne
   peut pas lister un dossier, d'où ce sommaire. Chaque fiche est un script à
   part : une virgule oubliée dans l'une n'emporte pas les autres.
--------------------------------------------------------------------------- */

const FICHES_MANUELS = [
  "mdg"
];

const MANUELS = [];
function manuel(m){
  if(!m || !m.id || !m.titre){ console.warn("Manuel sans id ou sans titre", m); return; }
  if(MANUELS.some(x => x.id === m.id)){ console.warn("Manuel en double : " + m.id); return; }
  MANUELS.push(m);
}

/* Écrits pendant la lecture de la page : les fiches passent avant le script
   principal, qui les trouve toutes au premier rendu. */
FICHES_MANUELS.forEach(f =>
  document.write('<script src="manuels/' + encodeURI(f) + '.js"><\/script>'));
