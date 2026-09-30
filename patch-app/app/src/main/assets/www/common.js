/* ---------------------------------------------------------------------------
   Régie — socle commun aux 3 canevas
   Le parc (projecteurs, machinerie, hauteurs) est livré vide : chaque
   salle saisit ou importe le sien depuis l'écran Parc, et il reste dans le
   téléphone. La procédure MDG est une fiche de la bibliothèque : voir
   `manuels/`.
   Les blocs marqués DEMO sont des jeux de démonstration : réseau, NDI,
   Art-Net/sACN ne peuvent pas être lus depuis une page web seule.
--------------------------------------------------------------------------- */

const PROJECTEURS = [];

const MACHINERIE = [];

const HAUTEURS = [];


/* ---------------------------- Calculs DMX ------------------------------- */

function hexToRgb(hex){
  const n = parseInt(hex.slice(1), 16);
  return { r:(n>>16)&255, g:(n>>8)&255, b:n&255 };
}

/* Décomposition d'une teinte en sources LED RGBWA.
   blanc = composante commune, ambre ≈ (255,126,0) retirée de ce qui reste. */
function toRGBWA(hex){
  const { r, g, b } = hexToRgb(hex);
  const w = Math.min(r, g, b);
  let R = r - w, G = g - w, B = b - w;
  const AR = 255, AG = 126;
  let k = Math.min(R / AR, AG ? G / AG : 1);
  if (!isFinite(k) || k < 0) k = 0;
  k = Math.min(k, 1);
  const a = k * 255;
  R -= k * AR; G -= k * AG;
  const pct = v => Math.round(Math.max(0, Math.min(255, v)) / 255 * 100);
  const dmx = v => Math.round(Math.max(0, Math.min(255, v)));
  return {
    pct:{ r:pct(R), g:pct(G), b:pct(B), w:pct(w), a:pct(a) },
    dmx:{ r:dmx(R), g:dmx(G), b:dmx(B), w:dmx(w), a:dmx(a) }
  };
}

/* Les autres mélanges proposés à l'écran Gélatines, pour les projecteurs qui
   n'ont pas d'ambre, ni de blanc, ou qui soustraient la couleur (lyres CMY).
   RGBW : blanc = composante commune, comme en RGBWA mais sans ambre.
   CMY : chaque drapeau retire son primaire ; 0 = faisceau blanc ouvert. */
const MELANGES = {
  RGBWA:["r", "g", "b", "w", "a"], RGBW:["r", "g", "b", "w"],
  RGB:["r", "g", "b"], CMY:["c", "m", "y"]
};
function toMelange(hex, mode){
  if(mode === "RGBWA") return toRGBWA(hex);
  const { r, g, b } = hexToRgb(hex);
  let v;
  if(mode === "CMY") v = { c:255 - r, m:255 - g, y:255 - b };
  else if(mode === "RGBW"){ const w = Math.min(r, g, b); v = { r:r - w, g:g - w, b:b - w, w }; }
  else v = { r, g, b };
  const pct = {}, dmx = {};
  for(const k in v){ pct[k] = Math.round(v[k] / 255 * 100); dmx[k] = v[k]; }
  return { pct, dmx };
}

/* Adresse absolue (1 = U1/C1) -> univers + canal */
function splitAddress(abs){
  const u = Math.floor((abs - 1) / 512) + 1;
  const ch = ((abs - 1) % 512) + 1;
  return { u, ch };
}

/* Port-Address Art-Net : net (0-127) / subnet (0-15) / universe (0-15) */
function artnet(universe1based){
  const p = universe1based - 1;          // Art-Net compte à partir de 0
  return { port:p, net:(p >> 8) & 127, sub:(p >> 4) & 15, uni:p & 15 };
}

/* sACN : univers 1-63999, multicast 239.255.<hi>.<lo> */
function sacn(universe1based){
  const u = universe1based;
  return { uni:u, ip:`239.255.${(u >> 8) & 255}.${u & 255}`, valide:u >= 1 && u <= 63999 };
}

/* Patch séquentiel : n appareils de `foot` canaux, sans chevauchement d'univers */
function patch(startU, startCh, count, foot){
  const out = [];
  let u = startU, ch = startCh;
  for (let i = 0; i < count; i++){
    if (ch + foot - 1 > 512){ u += 1; ch = 1; }
    out.push({ n:i + 1, u, ch, fin:ch + foot - 1, saut:(ch === 1 && i > 0) });
    ch += foot;
  }
  return out;
}

/* Appareils tenant dans un univers pour une empreinte donnée */
function parUnivers(foot){ return Math.floor(512 / foot); }

/* ------------------- Jeux de démonstration (DEMO) ------------------------ */

const DEMO_NDI = [
  { nom:"CAM-1 Face", machine:"MEDIA-01", fmt:"1920×1080p50", mbps:118, tally:"PGM", ok:true },
  { nom:"CAM-2 Latérale cour", machine:"MEDIA-01", fmt:"1920×1080p50", mbps:112, tally:"PVW", ok:true },
  { nom:"CAM-3 Plateau jardin", machine:"MEDIA-02", fmt:"1280×720p50", mbps:62, tally:null, ok:true },
  { nom:"Resolume — Sortie A", machine:"VJ-BOOTH", fmt:"1920×1080p60", mbps:134, tally:null, ok:true },
  { nom:"Retour régie son", machine:"SON-01", fmt:"1280×720p25", mbps:0, tally:null, ok:false }
];

const DEMO_RESEAU = [
  { ip:"192.168.0.1", nom:"", role:"", ms:0.6, ok:true },
  { ip:"192.168.0.10", nom:"ETC-EOS-GIO", origine:"NetBIOS", role:"", ms:0.9, ok:true },
  { ip:"192.168.0.21", nom:"Net3 Plateau jardin", origine:"nœud Art-Net", role:"nœud Art-Net", ms:1.2, ok:true },
  { ip:"192.168.0.22", nom:"GN10 passerelle", origine:"nœud Art-Net", role:"nœud Art-Net", ms:1.4, ok:true },
  { ip:"192.168.0.31", nom:"media-01", origine:"mDNS", role:"", ms:0.8, ok:true },
  { ip:"192.168.0.32", nom:"", role:"", ms:2.1, ok:true },
  { ip:"192.168.0.44", nom:"UniFi AP", origine:"page web", role:"", ms:4.7, ok:true },
  { ip:"192.168.0.51", nom:"node-salle", origine:"nœud Art-Net", role:"nœud Art-Net", ms:null, ok:false }
];

const DEMO_SOURCES_DMX = [
  { src:"192.168.0.10", nom:"Pupitre lumière", proto:"sACN", univ:[1,2,3,4], prio:100, hz:44 },
  { src:"192.168.0.31", nom:"Serveur vidéo", proto:"sACN", univ:[10,11], prio:90, hz:40 },
  { src:"192.168.0.21", nom:"Node plateau 1", proto:"Art-Net", univ:[1,2], prio:null, hz:30 }
];

/* Niveaux de démonstration pour un univers : quelques blocs cohérents */
function demoUniverse(seed){
  const v = new Array(512).fill(0);
  for (let i = 0; i < 512; i++){
    const wave = Math.sin((i + seed) / 17) * 0.5 + 0.5;
    if (i < 240) v[i] = Math.round(wave * 255 * (i % 20 < 12 ? 1 : 0.15));
    else if (i < 320) v[i] = (i % 4 === 0) ? 255 : Math.round(wave * 90);
    else v[i] = 0;
  }
  return v;
}

const FAMILLES = [...new Set(PROJECTEURS.map(p => p.fam))].sort();
const MARQUES = [...new Set(PROJECTEURS.map(p => p.marque))].sort();
