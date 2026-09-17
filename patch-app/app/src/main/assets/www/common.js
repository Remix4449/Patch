/* ---------------------------------------------------------------------------
   Régie — socle commun aux 3 canevas
   Données : extraites des bases Notion (Projecteurs, Machinerie,
   Hauteurs de Passerelles & Plateformes Élévatrices, MDG).
   Les blocs marqués DEMO sont des jeux de démonstration : réseau, NDI,
   Art-Net/sACN ne peuvent pas être lus depuis une page web seule.
--------------------------------------------------------------------------- */

const PROJECTEURS = [
  { nom:"Titan tube", marque:"Astera", w:"48 W", kg:"1,35 kg", nb:null, fam:"Tube LED" },
  { nom:"POWERBOX", marque:"Astera", w:"690 W", kg:"3,8 kg", nb:null, fam:"Accessoire" },
  { nom:"Hyperion", marque:"Astera", w:"144 W", kg:"4,80 kg", nb:null, fam:"Tube LED" },
  { nom:"Ghibli", marque:"Ayrton", w:"800 W", kg:"35,6 kg", nb:null, fam:"Lyre découpe" },
  { nom:"Khamsin", marque:"Ayrton", w:"1150 W", kg:"39,8 kg", nb:null, fam:"Lyre découpe" },
  { nom:"Diablo", marque:"Ayrton", w:"550 W", kg:"22 kg", nb:null, fam:"Lyre découpe" },
  { nom:"Evo W3", marque:"Caméo", w:"325 W", kg:"8 kg", nb:null, fam:"Wash LED" },
  { nom:"Instant Hazer 1500T Pro", marque:"Caméo", w:"1,5 kW", kg:"18 kg", nb:null, fam:"Machine à brume" },
  { nom:"Zenit Z180 G2", marque:"Caméo", w:"220 W", kg:"8 kg", nb:null, fam:"Wash LED IP" },
  { nom:"PIXBAR 600 Short", marque:"Caméo", w:"92 W", kg:"7,5 kg", nb:null, fam:"Barre LED" },
  { nom:"F2 FC", marque:"Caméo", w:"250 W", kg:"9 kg", nb:null, fam:"Fresnel LED" },
  { nom:"F4 FC", marque:"Caméo", w:"355 W", kg:"17 kg", nb:null, fam:"Fresnel LED" },
  { nom:"Ovation E-2FC", marque:"Chauvet", w:"220 W", kg:"6 kg", nb:null, fam:"Découpe LED" },
  { nom:"Epix Strip IP", marque:"Chauvet", w:"44 W", kg:"3 kg", nb:null, fam:"Barre LED" },
  { nom:"K20 HCR", marque:"Claypaky", w:"650 W", kg:"22,5 kg", nb:null, fam:"Lyre wash" },
  { nom:"Lustr 2", marque:"ETC", w:"167 W", kg:"8,3 kg", nb:null, fam:"Découpe LED" },
  { nom:"Lustr 3", marque:"ETC", w:"305 W", kg:"8,9 kg", nb:null, fam:"Découpe LED" },
  { nom:"SolaFrame 750", marque:"ETC", w:"600 W", kg:"28 kg", nb:null, fam:"Lyre découpe" },
  { nom:"ColorSource CYC", marque:"ETC", w:"133 W", kg:"11 kg", nb:null, fam:"Cyclo LED" },
  { nom:"SolaFrame 3000", marque:"ETC", w:"1500 W", kg:"49 kg", nb:"3", fam:"Lyre découpe" },
  { nom:"Lonestar", marque:"ETC", w:"615 W", kg:"25 kg", nb:null, fam:"Lyre découpe" },
  { nom:"Spectra", marque:"LDDE", w:"160 W", kg:"8,6 kg", nb:null, fam:"Wash LED" },
  { nom:"Viper", marque:"Martin", w:"1040 W", kg:"37 kg", nb:null, fam:"Lyre découpe" },
  { nom:"MAC Quantum Profile", marque:"Martin", w:"750 W", kg:"23,2 kg", nb:null, fam:"Lyre découpe" },
  { nom:"MAC Aura", marque:"Martin", w:"340 W", kg:"9 kg", nb:null, fam:"Lyre wash" },
  { nom:"Glaciator", marque:"Martin", w:"3500 W", kg:"110 kg", nb:null, fam:"Machine à fumée lourde" },
  { nom:"Spiider", marque:"Robe", w:"660 W", kg:"13,3 kg", nb:null, fam:"Lyre wash" },
  { nom:"LEDBeam 350", marque:"Robe", w:"450 W", kg:"9,9 kg", nb:null, fam:"Lyre beam" },
  { nom:"Esprite", marque:"Robe", w:"950 W", kg:"28,2 kg", nb:null, fam:"Lyre découpe" },
  { nom:"LEDBeam 150", marque:"Robe", w:"200 W", kg:"6 kg", nb:"10", fam:"Lyre beam", modes:[
      { mode:"Mode 1 (standard)", ch:20 }, { mode:"Mode 2 (réduit)", ch:13 }, { mode:"Mode 3 (étendu)", ch:25 }
    ], modesDemo:true },
  { nom:"Tarantula", marque:"Robe", w:"1000 W", kg:"21 kg", nb:null, fam:"Lyre hybride" },
  { nom:"Sully 4C", marque:"Robert Juliat", w:"250 W", kg:"14 kg", nb:null, fam:"Découpe LED" },
  { nom:"2,5 HMI", marque:"Robert Juliat", w:"2500 W", kg:"45 kg", nb:null, fam:"Poursuite" },
  { nom:"614 SX", marque:"Robert Juliat", w:"1000 W", kg:"13 kg", nb:"12", fam:"Découpe" },
  { nom:"713 SX", marque:"Robert Juliat", w:"2000 W", kg:"24 kg", nb:"4", fam:"Découpe" },
  { nom:"614 S", marque:"Robert Juliat", w:"1000 W", kg:"13 kg", nb:"24", fam:"Découpe" },
  { nom:"714 S", marque:"Robert Juliat", w:"2000 W", kg:"24 kg", nb:"16", fam:"Découpe" },
  { nom:"613 SX", marque:"Robert Juliat", w:"1000 W", kg:"11 kg", nb:"10", fam:"Découpe" },
  { nom:"329 HPC", marque:"Robert Juliat", w:"2000 W", kg:"14 kg", nb:"48", fam:"PC" },
  { nom:"310 HPC", marque:"Robert Juliat", w:"1000 W", kg:"9 kg", nb:"86", fam:"PC" },
  { nom:"CYC Q6", marque:"Showtec", w:"180 W", kg:"6 kg", nb:null, fam:"Cyclo LED" }
];

const MACHINERIE = [
  { nom:"Cyclorama", type:"Cyclorama", nb:1, h:"8,5 m", l:"12 m" },
  { nom:"Fond", type:"Fond", nb:1, h:"7,50 m", l:"13 m" },
  { nom:"Demi fond", type:"Demi fond", nb:1, h:"8,50 m", l:"9 m" },
  { nom:"Inter", type:"Inter", nb:null, h:"8 m", l:"12,5 m" },
  { nom:"Pendrillon", type:"Pendrillon", nb:5, h:"8 m", l:"4 m" },
  { nom:"Frises 2 m", type:"Frises", nb:2, h:"2 m", l:"17 m" },
  { nom:"Frises 3 m", type:"Frises", nb:4, h:"3 m", l:"17 m" },
  { nom:"Frises 4 m", type:"Frises", nb:1, h:"4 m", l:"17 m" },
  { nom:"Tapis de danse", type:"Tapis de danse", nb:9, h:"1,5 m", l:"15 m" },
  { nom:"Moteur 1 t — 4 m/min", type:"Moteurs", nb:2, charge:"1000 kg", vit:"4 m/min" },
  { nom:"Moteur 1 t — 8 m/min", type:"Moteurs", nb:4, charge:"1000 kg", vit:"8 m/min" },
  { nom:"Palan case décor", type:"Moteurs", nb:null, charge:"500 kg" },
  { nom:"ASD 0,5 m", type:"Structure", nb:4, l:"0,5 m", trust:"Carrée" },
  { nom:"ASD 1 m", type:"Structure", nb:4, l:"1 m", trust:"Carrée" },
  { nom:"ASD 2 m", type:"Structure", nb:19, l:"2 m", trust:"Carrée" },
  { nom:"ASD 3 m", type:"Structure", nb:2, l:"3 m", trust:"Carrée" },
  { nom:"Angle modulable", type:"Structure", nb:4, trust:"Angle modulable" },
  { nom:"Angle 3D", type:"Structure", nb:2, trust:"Angle 3D" },
  { nom:"Embase", type:"Structure", nb:4, trust:"Embase" },
  { nom:"Collier simple anneau Doughty", type:"Accroche", nb:19 },
  { nom:"Collier double Doughty fixe", type:"Accroche", nb:23, charge:"750 kg" },
  { nom:"Collier double Doughty rotatif", type:"Accroche", nb:12, charge:"750 kg" },
  { nom:"Mono tube 0,20 m", type:"Mono tube", nb:2 },
  { nom:"Mono tube 45 cm", type:"Mono tube", nb:8, l:"0,45 m" },
  { nom:"Mono tube 0,50 m", type:"Mono tube", nb:2, l:"0,50 m" },
  { nom:"Mono tube 2 m", type:"Mono tube", nb:4, l:"2 m" },
  { nom:"Mono tube 3 m", type:"Mono tube", nb:2, l:"3 m" },
  { nom:"Angle 2D", type:"Mono tube", nb:6 }
];

const HAUTEURS = [
  { nom:"Lisse passerelle élec", type:"Passerelle", pos:"Plateau", h:7.75 },
  { nom:"Bas passerelle élec", type:"Passerelle", pos:"Plateau", h:7.75, note:"Hauteur réglage tour" },
  { nom:"Bas passerelle commande", type:"Passerelle", pos:"Plateau", h:10.8 },
  { nom:"Lisse passerelle commande", type:"Passerelle", pos:"Plateau", h:11.8 },
  { nom:"Bas passerelle charge", type:"Passerelle", pos:"Plateau", h:14.8 },
  { nom:"Lisse passerelle charge", type:"Passerelle", pos:"Plateau", h:15.8 },
  { nom:"Grill rail", type:"Passerelle", pos:"Plateau", h:16.5 },
  { nom:"Grill IPN", type:"Passerelle", pos:"Plateau", h:16.8 },
  { nom:"Grill", type:"Passerelle", pos:"Plateau", h:17 },
  { nom:"Genie + FT", type:"Plateforme élévatrice", pos:"Plateau", h:8.24, charge:136 }
];

const MDG = {
  titre:"MDG — machine à brume",
  allumage:[
    "Bouteille : manomètre à 3,5 bar",
    "Unit sur ON",
    "Haze sur ON",
    "Régler la pression suivant la quantité de fumée souhaitée"
  ],
  extinction:[
    "Unit sur OFF",
    "Haze sur OFF",
    "Pression à zéro",
    "Vérifier sur les statuts que la purge est terminée"
  ],
  alertes:[ "Écran qui clignote = bouteille vide" ]
};

/* Gélatines Lee — teintes approchées (rendu écran), à recaler sur mesure.
   Sert de point de départ au calage RGBWA, pas de référence colorimétrique. */
const LEE = [
  { ref:"L007", nom:"Pale Yellow", hex:"#FDF0A8" },
  { ref:"L010", nom:"Medium Yellow", hex:"#FDE23B" },
  { ref:"L015", nom:"Deep Straw", hex:"#F9B233" },
  { ref:"L019", nom:"Fire", hex:"#F14A1B" },
  { ref:"L021", nom:"Gold Amber", hex:"#F58A22" },
  { ref:"L022", nom:"Dark Amber", hex:"#E4571B" },
  { ref:"L026", nom:"Bright Red", hex:"#D9241F" },
  { ref:"L027", nom:"Medium Red", hex:"#C81C24" },
  { ref:"L036", nom:"Medium Pink", hex:"#F2A0B4" },
  { ref:"L048", nom:"Rose Purple", hex:"#B9469B" },
  { ref:"L058", nom:"Lavender", hex:"#BBA8D6" },
  { ref:"L071", nom:"Tokyo Blue", hex:"#12266E" },
  { ref:"L079", nom:"Just Blue", hex:"#0C4EA2" },
  { ref:"L088", nom:"Lime Green", hex:"#B9D437" },
  { ref:"L089", nom:"Moss Green", hex:"#7FB04A" },
  { ref:"L101", nom:"Yellow", hex:"#FBE22B" },
  { ref:"L104", nom:"Deep Amber", hex:"#F5901E" },
  { ref:"L105", nom:"Orange", hex:"#F2701B" },
  { ref:"L106", nom:"Primary Red", hex:"#C8102E" },
  { ref:"L111", nom:"Dark Pink", hex:"#D64E8E" },
  { ref:"L113", nom:"Magenta", hex:"#B02A8F" },
  { ref:"L116", nom:"Medium Blue Green", hex:"#3FA89B" },
  { ref:"L119", nom:"Dark Blue", hex:"#123E8C" },
  { ref:"L124", nom:"Dark Green", hex:"#1C8A4B" },
  { ref:"L126", nom:"Mauve", hex:"#7A3E93" },
  { ref:"L132", nom:"Medium Blue", hex:"#1462A8" },
  { ref:"L139", nom:"Primary Green", hex:"#00A14B" },
  { ref:"L152", nom:"Pale Gold", hex:"#F6D9A3" },
  { ref:"L156", nom:"Chocolate", hex:"#D9A46B" },
  { ref:"L161", nom:"Slate Blue", hex:"#6E9BC4" },
  { ref:"L181", nom:"Congo Blue", hex:"#2B1470" },
  { ref:"L195", nom:"Zenith Blue", hex:"#1B4FA0" },
  { ref:"L201", nom:"Full CT Blue", hex:"#BFD9F2" },
  { ref:"L202", nom:"Half CT Blue", hex:"#D6E6F5" },
  { ref:"L203", nom:"Quarter CT Blue", hex:"#E6F0F9" },
  { ref:"L204", nom:"Full CT Orange", hex:"#F5C489" },
  { ref:"L205", nom:"Half CT Orange", hex:"#F7D7AE" },
  { ref:"L206", nom:"Quarter CT Orange", hex:"#F9E5CB" }
];

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
  { ip:"192.168.0.1", nom:"switch-regie", role:"Switch PoE 24p", ms:0.6, ok:true },
  { ip:"192.168.0.10", nom:"console-lumiere", role:"Pupitre", ms:0.9, ok:true },
  { ip:"192.168.0.21", nom:"node-plateau-1", role:"Node Art-Net 4p", ms:1.2, ok:true },
  { ip:"192.168.0.22", nom:"node-passerelle", role:"Node Art-Net 4p", ms:1.4, ok:true },
  { ip:"192.168.0.31", nom:"media-01", role:"Serveur vidéo", ms:0.8, ok:true },
  { ip:"192.168.0.32", nom:"media-02", role:"Serveur vidéo", ms:2.1, ok:true },
  { ip:"192.168.0.44", nom:"wifi-plateau", role:"Borne Wi-Fi", ms:4.7, ok:true },
  { ip:"192.168.0.51", nom:"node-salle", role:"Node Art-Net 2p", ms:null, ok:false }
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
