/* ---------------------------------------------------------------------------
   Socle commun aux quatre canevas de refonte : données de démonstration,
   calculs DMX, et les quelques aides dont chaque maquette a besoin.

   Rien ici ne parle réseau : les canevas simulent l'émission. Dans
   l'application, ces mêmes gestes passent par NET.emission() / net.js.
--------------------------------------------------------------------------- */

/* ------------------------------ appareils -------------------------------- */
/* Extrait du parc, avec une bibliothèque de modes plausible pour que le
   calcul d'adresses ait de quoi travailler. */
const APPAREILS = [
  { marque:"Robe",          nom:"LEDBeam 150",   fam:"Lyre beam",     w:200,
    modes:[{ nom:"Mode 1 (standard)", ch:20 }, { nom:"Mode 2 (réduit)", ch:13 }, { nom:"Mode 3 (étendu)", ch:25 }] },
  { marque:"Robe",          nom:"Spiider",       fam:"Lyre wash",     w:660,
    modes:[{ nom:"Mode 1", ch:47 }, { nom:"Mode 2", ch:33 }] },
  { marque:"Robe",          nom:"Esprite",       fam:"Lyre découpe",  w:950,
    modes:[{ nom:"Mode 1", ch:44 }, { nom:"Mode 2", ch:38 }] },
  { marque:"Ayrton",        nom:"Ghibli",        fam:"Lyre découpe",  w:800,
    modes:[{ nom:"Standard", ch:41 }, { nom:"Étendu", ch:57 }] },
  { marque:"Ayrton",        nom:"Diablo",        fam:"Lyre découpe",  w:550,
    modes:[{ nom:"Standard", ch:39 }] },
  { marque:"Martin",        nom:"MAC Aura",      fam:"Lyre wash",     w:340,
    modes:[{ nom:"Basique", ch:14 }, { nom:"Étendu", ch:23 }] },
  { marque:"ETC",           nom:"Lustr 3",       fam:"Découpe LED",   w:305,
    modes:[{ nom:"Direct", ch:7 }, { nom:"HSI", ch:5 }] },
  { marque:"ETC",           nom:"ColorSource CYC", fam:"Cyclo LED",   w:133,
    modes:[{ nom:"Direct", ch:5 }] },
  { marque:"Caméo",         nom:"F2 FC",         fam:"Fresnel LED",   w:250,
    modes:[{ nom:"6 canaux", ch:6 }, { nom:"11 canaux", ch:11 }] },
  { marque:"Caméo",         nom:"PIXBAR 600 Short", fam:"Barre LED",  w:92,
    modes:[{ nom:"8 canaux", ch:8 }, { nom:"Pixel", ch:36 }] },
  { marque:"Astera",        nom:"Titan tube",    fam:"Tube LED",      w:48,
    modes:[{ nom:"6 canaux", ch:6 }, { nom:"Pixel 16", ch:52 }] },
  { marque:"Chauvet",       nom:"Ovation E-2FC", fam:"Découpe LED",   w:220,
    modes:[{ nom:"Direct", ch:6 }] },
  { marque:"Robert Juliat", nom:"614 SX",        fam:"Découpe",       w:1000,
    modes:[{ nom:"Gradateur", ch:1 }] },
  { marque:"Robert Juliat", nom:"329 HPC",       fam:"PC",            w:2000,
    modes:[{ nom:"Gradateur", ch:1 }] },
  { marque:"Robert Juliat", nom:"310 HPC",       fam:"PC",            w:1000,
    modes:[{ nom:"Gradateur", ch:1 }] }
];

const cleApp = a => a.marque + " " + a.nom;
const trouverApp = cle => APPAREILS.find(a => cleApp(a) === cle) || APPAREILS[0];
const MARQUES = [...new Set(APPAREILS.map(a => a.marque))].sort();

/* ------------------------------ calculs DMX ------------------------------ */

/* Enchaîne les adresses d'une liste de lignes. Une ligne « suite » repart là
   où la précédente s'est arrêtée ; sinon elle impose son univers et son
   adresse. Un appareil ne chevauche jamais deux univers. */
function adresser(lignes){
  const out = [];
  let u = 1, ch = 1, n = 0;
  lignes.forEach(l => {
    if(!l.suite){ u = Math.max(1, l.u || 1); ch = Math.min(512, Math.max(1, l.adr || 1)); }
    const foot = Math.min(512, Math.max(1, l.ch || 1));
    for(let i = 0; i < Math.max(0, l.nb || 0); i++){
      if(ch + foot - 1 > 512){ u++; ch = 1; }
      n++;
      out.push({ n, ligne:l.id, i, app:l.app, mode:l.mode, ch:foot,
                 u, adr:ch, fin:ch + foot - 1 });
      ch += foot;
    }
  });
  return out;
}

/* Chevauchements : deux appareils qui se marchent dessus dans le même univers.
   Le canevas C en fait son sujet, les autres s'en servent pour alerter. */
function collisions(liste){
  const occ = new Map();          // "u:canal" -> n° d'appareil
  const ko = new Set();
  liste.forEach(x => {
    for(let c = x.adr; c <= x.fin; c++){
      const k = x.u + ":" + c;
      if(occ.has(k)){ ko.add(x.n); ko.add(occ.get(k)); }
      else occ.set(k, x.n);
    }
  });
  return ko;
}

/* Port-Address Art-Net : net (0-127) / subnet (0-15) / universe (0-15) */
function artnet(u1){
  const p = u1 - 1;
  return { port:p, net:(p >> 8) & 127, sub:(p >> 4) & 15, uni:p & 15 };
}
/* sACN : multicast 239.255.<hi>.<lo> */
function sacn(u1){
  return { uni:u1, ip:`239.255.${(u1 >> 8) & 255}.${u1 & 255}` };
}

/* Adresse DMX d'un gradateur, d'après le premier gradateur et son adresse. */
function adrGrada(n, reg){
  const i = (reg.adr - 1) + (n - reg.premier);
  if(i < 0) return null;
  return { u:reg.u + Math.floor(i / 512), canal:(i % 512) + 1 };
}

function puissance(liste){
  let w = 0;
  liste.forEach(x => { const a = APPAREILS.find(y => cleApp(y) === x.app); if(a) w += a.w; });
  if(!liste.length) return "—";
  return w >= 1000 ? (w / 1000).toFixed(1).replace(".", ",") + " kW" : w + " W";
}

/* ------------------------- jeux de démonstration ------------------------- */
/* Un plan de feu court mais réaliste : trois familles, trois départs. */
const DEMO_LIGNES = [
  { id:"l1", app:"Robe LEDBeam 150",   mode:"Mode 1 (standard)", ch:20, nb:10, suite:false, u:1, adr:1 },
  { id:"l2", app:"Ayrton Ghibli",      mode:"Standard",          ch:41, nb:6,  suite:true,  u:1, adr:1 },
  { id:"l3", app:"ETC Lustr 3",        mode:"Direct",            ch:7,  nb:12, suite:false, u:2, adr:1 },
  { id:"l4", app:"Caméo F2 FC",        mode:"6 canaux",          ch:6,  nb:8,  suite:true,  u:2, adr:1 }
];

/* Feuille de gradateurs : quelques circuits déjà relevés, le reste à faire —
   c'est l'état réel au bout de dix minutes de relevé. */
const DEMO_CIRCUITS = {
  1:{ circuit:"101", note:"face jardin" },   2:{ circuit:"101", note:"face jardin" },
  3:{ circuit:"102", note:"face cour" },     4:{ circuit:"103", note:"contre 1" },
  5:{ circuit:"103", note:"contre 1" },      6:{ circuit:"104", note:"" },
  7:{ circuit:"105", note:"douche milieu" }, 9:{ circuit:"107", note:"latéral cour" },
  12:{ circuit:"110", note:"cyclo" }
};

const REGLAGES_DEFAUT = { proto:"sACN", u:1, adr:1, premier:1, nb:48,
                          prio:100, cible:"", pas:5, solo:true };

/* ------------------------------- aides UI -------------------------------- */

const $  = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const el = (t, c, h) => { const n = document.createElement(t);
  if(c) n.className = c; if(h != null) n.innerHTML = h; return n; };
const esc = s => String(s ?? "").replace(/[&<>"]/g,
  c => ({ "&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;" }[c]));

/* Retour haptique quand le téléphone le permet : sur un plateau, on ne
   regarde pas toujours l'écran en appuyant. */
const vibrer = ms => { try { navigator.vibrate && navigator.vibrate(ms); } catch(e){} };

/* Émission simulée. Dans l'application, NET.emission() tient les trames à
   30 Hz ; ici on se contente de compter ce qui partirait, pour que les
   maquettes affichent un état de flux honnête. */
const FLUX = {
  actif:false, univers:[], canaux:0,
  poser(niveaux, reg){
    const u = new Set();
    let n = 0;
    Object.keys(niveaux).forEach(k => {
      if((niveaux[k] | 0) <= 0) return;
      const a = adrGrada(+k, reg);
      if(a){ u.add(a.u); n++; }
    });
    this.univers = [...u].sort((a, b) => a - b);
    this.canaux = n;
    this.actif = true;
  },
  arret(){ this.actif = false; this.univers = []; this.canaux = 0; }
};

/* Amener un élément dans la vue. Le défilement doux est coupé quand le
   système demande des animations réduites — et il ne se déclenche pas du tout
   dans un rendu hors écran, d'où le repli sur un saut direct. */
function defiler(n, block = "center"){
  if(!n) return;
  const doux = !matchMedia("(prefers-reduced-motion: reduce)").matches;
  n.scrollIntoView({ block, behavior:doux ? "smooth" : "auto" });
}

/* Bandeau de navigation entre maquettes, posé en haut de chaque canevas.
   Encadré — dans l'atelier, qui présente les quatre côte à côte — il ne sert
   à rien : la page hôte porte déjà la navigation. */
function bandeau(courant){
  if(window.top !== window.self) return;
  const pages = [["a", "A · Pupitre", "a-pupitre.html"], ["b", "B · Relevé", "b-releve.html"],
                 ["c", "C · Plan 512", "c-plan.html"], ["d", "D · Feuille", "d-feuille.html"]];
  const d = el("div", "maq");
  d.innerHTML = `<a href="index.html">↤ les quatre</a><span class="sp"></span>` +
    pages.map(([k, t, h]) =>
      `<a href="${h}" aria-current="${k === courant}">${t}</a>`).join("");
  document.body.prepend(d);
}
