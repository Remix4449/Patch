/* ------------------------------ logistique ------------------------------- */
/* Trois carnets qui vivaient sur des bouts de papier et dans des messages :
   les événements logistiques (livraisons, montages, transports), le matériel
   prêté ou emprunté, et les stagiaires. Une seule liste les range par urgence
   — en retard, en cours, aujourd'hui, la semaine, plus tard — parce que la
   question qu'on se pose en arrivant est « qu'est-ce qui m'attend », pas
   « dans quel carnet l'ai-je noté ».

   Les fiches se gardent en local et s'échangent par fichier : JSON (complet,
   c'est le format de synchronisation), CSV (tableur) et iCal (.ics, que tous
   les agendas lisent). L'import fusionne par identifiant et garde la version
   la plus récente ; une fiche retirée laisse une trace datée pour que le
   retrait voyage aussi.

   Ce fichier est chargé avant le script principal : il n'en utilise les
   fonctions (el, esc, go, render…) qu'au moment où l'écran s'affiche. */

const CLE_LOG = "logistique.v1";
let LOG_DATA = null;
function logTout(){
  if(!LOG_DATA){
    const d = lireLocal(CLE_LOG, null);
    LOG_DATA = Array.isArray(d && d.elements) ? d.elements : [];
  }
  return LOG_DATA;
}
const logVivants = () => logTout().filter(x => !x.supprime);
const sauverLog = () => ecrireLocal(CLE_LOG, { version:1, elements:logTout() });
const logId = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);

const LOG_TYPES = {
  ev:   { t:"Événement", pl:"Événements", un:"un événement", i:"camion", c:"var(--log)",
          debut:"Date", fin:"Jusqu'au", sup:"Retirer cet événement" },
  /* Un spectacle est un événement comme un autre : même nom, même couleur,
     même bouton. Le carnet reste pour les fiches déjà notées avec leurs
     heures de jeu (`ferme` : on n'en crée plus). */
  spec: { t:"Événement", pl:"Événements", un:"un événement", i:"camion", c:"var(--log)", ferme:true, comme:"ev",
          debut:"Première", fin:"Dernière", sup:"Retirer cet événement" },
  pret: { t:"Prêt", pl:"Prêts", un:"un prêt", i:"pret", c:"var(--pret)",
          debut:"Sortie", fin:"Retour prévu", sup:"Retirer ce prêt" },
  stag: { t:"Stagiaire", pl:"Stagiaires", un:"un stagiaire", i:"stag", c:"var(--stag)",
          debut:"Arrivée", fin:"Départ", sup:"Retirer ce stagiaire" }
};
const LOG_CATS = ["Livraison", "Enlèvement", "Montage", "Démontage", "Transport",
                  "Réunion", "Répétition", "Visite", "Autre"];

/* Les champs de chaque carnet. `rang` met deux champs côte à côte. Les dates
   s'appellent debut/fin partout : l'agenda, le tri et l'export n'ont qu'une
   règle à connaître. */
const LOG_CHAMPS = {
  ev: [
    { c:"titre", l:"Quoi", ph:"Livraison des pendrillons", req:true },
    { c:"cat", l:"Catégorie", sel:LOG_CATS, seg:true },
    { rang:[{ c:"debut", l:"Date", type:"date", req:true }, { c:"hdebut", l:"Heure", type:"time" }] },
    { rang:[{ c:"fin", l:"Jusqu'au", type:"date" }, { c:"hfin", l:"Heure de fin", type:"time" }] },
    { c:"lieu", l:"Lieu", ph:"Quai de déchargement" },
    { c:"spectacle", l:"Spectacle", ph:"Nom du spectacle", liste:"spectacles", plus:true },
    { c:"contact", l:"Contact", ph:"Nom, téléphone", plus:true },
    { c:"note", l:"Note", zone:true, ph:"Ce qu'il faut savoir", plus:true },
    { c:"fait", l:"Fait", coche:true }
  ],
  /* Un spectacle court de sa première à sa dernière ; il se joue chaque jour
     entre les deux, sauf les jours de relâche, aux heures de jeu. */
  spec: [
    { c:"titre", l:"Spectacle", ph:"Titre du spectacle", req:true, liste:"spectacles" },
    { c:"tiers", l:"Compagnie", ph:"Compagnie, production" },
    { c:"lieu", l:"Salle", ph:"Grande salle" },
    { rang:[{ c:"debut", l:"Première", type:"date", req:true }, { c:"fin", l:"Dernière", type:"date" }] },
    { c:"seances", l:"Heures de jeu", ph:"20:30, dim 16:00", req:true,
      aide:"Une heure vaut pour tous les jours de jeu. Un jour devant une heure fait une exception : "
         + "« 20:30, sam 15:00 20:30, dim 16:00 »." },
    { c:"relache", l:"Relâche", jours:true },
    { rang:[{ c:"duree", l:"Durée", ph:"1h30", mode:"text" }, { c:"contact", l:"Contact", ph:"Régie, production" }] },
    { c:"note", l:"Note", zone:true, ph:"Service, montage, équipe…" }
  ],
  pret: [
    /* Le matériel s'écrit comme on le dit, quantités comprises : « 2x 613sx
       4x F1 ». Ce que le parc connaît est reconnu sous le champ. */
    { c:"titre", l:"Matériel", ph:"2x 613sx, 4x F1", req:true, materiel:true },
    { c:"sens", l:"Sens", sel:["Prêté à", "Emprunté à"], seg:true },
    { c:"tiers", l:"Qui", ph:"Théâtre, compagnie, personne", req:true },
    { rang:[{ c:"debut", l:"Sortie", type:"date", req:true }, { c:"fin", l:"Retour prévu", type:"date" }] },
    { c:"contact", l:"Contact", ph:"Nom, téléphone", plus:true },
    { c:"spectacle", l:"Pour", ph:"Spectacle, événement", liste:"spectacles", plus:true },
    { c:"note", l:"Note", zone:true, ph:"État au départ, numéros de série…", plus:true },
    { c:"fait", l:"Rendu", coche:true }
  ],
  stag: [
    { c:"titre", l:"Nom", ph:"Prénom Nom", req:true },
    { c:"formation", l:"École ou formation", ph:"BTS, CFPTS, licence pro…" },
    { rang:[{ c:"service", l:"Service", ph:"Lumière" }, { c:"tuteur", l:"Tuteur", ph:"Qui l'encadre" }] },
    { rang:[{ c:"debut", l:"Arrivée", type:"date", req:true }, { c:"fin", l:"Départ", type:"date" }] },
    { c:"contact", l:"Contact", ph:"Téléphone, mail", plus:true },
    { c:"note", l:"Note", zone:true, ph:"Horaires, convention, objectifs…", plus:true }
  ]
};
const champsPlats = t => LOG_CHAMPS[t].flatMap(f => f.rang || [f]);

/* ------------------------------- les dates ------------------------------ */
const iso = d => d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0")
               + "-" + String(d.getDate()).padStart(2, "0");
const logAuj = () => iso(new Date());
const plusJours = (j, n) => { const [a, m, d] = j.split("-").map(Number); return iso(new Date(a, m - 1, d + n)); };
const ecartJours = (a, b) => {
  const [y1, m1, d1] = a.split("-").map(Number), [y2, m2, d2] = b.split("-").map(Number);
  return Math.round((new Date(y2, m2 - 1, d2) - new Date(y1, m1 - 1, d1)) / 864e5);
};
function jourCourt(j, avecJour){
  if(!j) return "";
  const [a, m, d] = j.split("-").map(Number);
  const dt = new Date(a, m - 1, d);
  const o = { day:"numeric", month:"short" };
  if(avecJour) o.weekday = "short";
  if(a !== new Date().getFullYear()) o.year = "numeric";
  return dt.toLocaleDateString("fr-FR", o);
}

/* « 09:00 » se dit « 9 h », « 14:30 » « 14 h 30 ». */
const heureDite = h => { if(!h) return ""; const [a, b] = h.split(":");
  return +a + " h" + (b && b !== "00" ? " " + b : ""); };

/* ------------------------------ les séances ---------------------------- */
/* Les heures de jeu s'écrivent comme on les dit : « 20:30 » pour tous les
   soirs, « dim 16:00 » pour une exception, « sam 15:00 20:30 » pour deux
   séances. Les jours se comptent de 1 (lundi) à 7 (dimanche). */
const JOURS_ABR = ["lun", "mar", "mer", "jeu", "ven", "sam", "dim"];
const jourSem = j => { const [a, m, d] = j.split("-").map(Number); return (new Date(a, m - 1, d).getDay() + 6) % 7 + 1; };
function seancesLues(s){
  const r = { defaut:[], jours:{} };
  String(s || "").split(/[,;\n]+/).forEach(seg => {
    const p = plat(seg);
    const hs = [...p.matchAll(/(\d{1,2})\s*[:h]([0-5]\d)?/g)]
      .map(m => String(Math.min(23, +m[1])).padStart(2, "0") + ":" + (m[2] || "00"));
    if(!hs.length) return;
    const js = [...p.matchAll(/(?:^|[^a-z])(lun|mar|mer|jeu|ven|sam|dim)/g)].map(m => JOURS_ABR.indexOf(m[1]) + 1);
    if(js.length) js.forEach(d => r.jours[d] = [...new Set([...(r.jours[d] || []), ...hs])].sort());
    else r.defaut.push(...hs);
  });
  r.defaut = [...new Set(r.defaut)].sort();
  return r;
}
const finDe = x => x.fin && x.fin >= x.debut ? x.fin : x.debut;
/* Les séances d'un spectacle un jour donné : aucune hors de ses dates ou un
   jour de relâche. */
function seancesDu(x, j){
  if(x.type !== "spec" || !x.debut || j < x.debut || j > finDe(x)) return [];
  const d = jourSem(j);
  if(String(x.relache || "").includes(String(d))) return [];
  const r = seancesLues(x.seances || x.hdebut);
  return r.jours[d] || r.defaut;
}
/* La prochaine représentation à partir d'un jour, sur trois mois au plus. */
function prochaineDe(x, j){
  let k = x.debut > j ? x.debut : j;
  for(let n = 0; n < 92 && k <= finDe(x); n++, k = plusJours(k, 1)){
    const s = seancesDu(x, k);
    if(s.length) return { j:k, s };
  }
  return null;
}
const heuresDites = s => s.map(heureDite).join(" · ");
/* « 20:30 » plus « 1:45 », sans passer minuit. */
const ajouterHeure = (h, d) => { const [a, b] = h.split(":").map(Number), [c, e] = d.split(":").map(Number);
  const t = Math.min(23 * 60 + 59, a * 60 + b + c * 60 + e);
  return String(Math.floor(t / 60)).padStart(2, "0") + ":" + String(t % 60).padStart(2, "0"); };

/* Où ranger une fiche aujourd'hui, et ce que la colonne de droite en dit. */
const LOG_GROUPES = [
  { k:"retard", t:"En retard" }, { k:"auj", t:"Aujourd'hui" }, { k:"cours", t:"En cours" },
  { k:"semaine", t:"Dans les 7 jours" }, { k:"plus", t:"Plus tard" },
  { k:"sansdate", t:"Sans date" }, { k:"fini", t:"Terminés" }
];
function logEtat(x, j){
  const fin = x.fin || x.debut;
  const aVenir = () => x.debut === j ? "auj" : x.debut <= plusJours(j, 7) ? "semaine" : "plus";
  if(x.type === "pret"){
    if(x.fait) return { g:"fini", cle:x.fin || x.debut, r:"rendu", r2:jourCourt(x.fin) };
    if(!x.debut) return { g:"sansdate", cle:"", r:"", r2:"" };
    if(x.fin && x.fin < j){
      const n = ecartJours(x.fin, j);
      return { g:"retard", cle:x.fin, r:n + " j de retard", r2:"retour " + jourCourt(x.fin) };
    }
    if(x.debut <= j)
      return { g:x.fin === j ? "auj" : "cours", cle:x.fin || "9999",
               r:x.fin ? (x.fin === j ? "retour ce jour" : "retour " + jourCourt(x.fin)) : "sans retour prévu",
               r2:"sorti " + jourCourt(x.debut) };
    return { g:aVenir(), cle:x.debut, r:jourCourt(x.debut, true), r2:x.fin ? "→ " + jourCourt(x.fin) : "" };
  }
  if(x.type === "spec"){
    if(!x.debut) return { g:"sansdate", cle:"", r:"", r2:"" };
    const fin = finDe(x);
    if(fin < j) return { g:"fini", cle:fin, r:"terminé", r2:jourCourt(fin) };
    const s = seancesDu(x, j);
    if(s.length) return { g:"auj", cle:j + s[0], r:s.length > 1 ? "séances " + heuresDites(s) : heureDite(s[0]),
                          r2:fin === x.debut ? "" : fin === j ? "dernière" : "jusqu'au " + jourCourt(fin) };
    const pro = prochaineDe(x, j);
    if(x.debut <= j) return { g:"cours", cle:fin, r:"relâche",
                              r2:pro ? "reprise " + jourCourt(pro.j, true) : "jusqu'au " + jourCourt(fin) };
    return { g:aVenir(), cle:x.debut + (pro ? pro.s[0] : ""), r:jourCourt(x.debut, true),
             r2:[pro && pro.j === x.debut ? heuresDites(pro.s) : "", x.fin && x.fin !== x.debut ? "→ " + jourCourt(x.fin) : ""]
               .filter(Boolean).join(" ") };
  }
  if(x.type === "stag"){
    if(!x.debut) return { g:"sansdate", cle:"", r:"", r2:"" };
    if(fin < j) return { g:"fini", cle:fin, r:"parti", r2:jourCourt(fin) };
    if(x.debut <= j)
      return { g:x.debut === j ? "auj" : "cours", cle:fin, r:x.debut === j ? "arrive" : "présent",
               r2:x.fin ? "jusqu'au " + jourCourt(x.fin) : "" };
    return { g:aVenir(), cle:x.debut, r:jourCourt(x.debut, true), r2:x.fin ? "→ " + jourCourt(x.fin) : "" };
  }
  if(!x.debut) return { g:x.fait ? "fini" : "sansdate", cle:"", r:x.fait ? "fait" : "", r2:"" };
  if(x.fait || fin < j) return { g:"fini", cle:fin, r:x.fait ? "fait" : "passé", r2:jourCourt(x.debut) };
  const h = heureDite(x.hdebut);
  if(x.debut <= j && fin >= j)
    return { g:x.debut === j || fin === j ? "auj" : "cours", cle:x.debut + (x.hdebut || ""),
             r:x.debut === j ? (h || "ce jour") : "jusqu'au " + jourCourt(fin), r2:x.hfin && fin === j ? "fin " + heureDite(x.hfin) : "" };
  return { g:aVenir(), cle:x.debut + (x.hdebut || ""), r:jourCourt(x.debut, true),
           r2:[h, x.fin && x.fin !== x.debut ? "→ " + jourCourt(x.fin) : ""].filter(Boolean).join(" ") };
}

/* Ce qui concerne la journée, pour qui veut l'afficher ailleurs (un bloc
   « Aujourd'hui » à l'accueil) : les retards d'abord, puis ce qui a lieu ou
   court aujourd'hui. Chaque entrée garde la fiche, son état et ses libellés. */
function logDuJour(){
  const j = logAuj(), ordre = ["retard", "auj", "cours"];
  return logVivants().map(x => ({ x, e:logEtat(x, j) }))
    .filter(o => ordre.includes(o.e.g))
    .sort((a, b) => ordre.indexOf(a.e.g) - ordre.indexOf(b.e.g) || String(a.e.cle).localeCompare(b.e.cle))
    .map(({ x, e }) => ({ fiche:x.type === "spec" ? Object.assign({}, x, { hdebut:seancesDu(x, j)[0] || "" }) : x, groupe:e.g, titre:logTitre(x), sous:logSous(x), quand:e.r, detail:e.r2 }));
}

function logSous(x){
  if(x.type === "pret")
    return [(x.sens || "Prêté à") + " " + (x.tiers || "?"), x.spectacle].filter(Boolean).join(" · ");
  if(x.type === "spec")
    return [x.tiers, x.lieu].filter(Boolean).join(" · ") || "Spectacle";
  if(x.type === "stag")
    return [x.formation, x.service, x.tuteur && "avec " + x.tuteur].filter(Boolean).join(" · ") || "Stagiaire";
  return [x.cat, x.lieu, x.spectacle].filter(Boolean).join(" · ") || "Événement";
}
/* Un prêt se lit comme le parc l'appelle : « 2 × 613 SX · 4 × F1 ». Les
   fiches d'avant gardent leur champ quantité. */
function logTitre(x){
  if(x.type !== "pret") return x.titre;
  if(x.qte && x.qte !== "1") return x.qte + " × " + x.titre;
  const l = materielLu(x.titre);
  if(!l.length || (l.length === 1 && l[0].q === 1)) return l.length && l[0].court ? l[0].court : x.titre;
  return l.map(o => o.q + " × " + (o.court || o.texte)).join(" · ");
}

/* --------------------------- le matériel écrit -------------------------- */
/* « 2x 613sx 4xf1 », « 3 × PC 1 kW, pendrillons » : chaque morceau garde sa
   quantité (1 sans chiffre) et se cherche dans le parc, sans tenir compte des
   espaces ni de la casse. Un morceau que le parc ne connaît pas reste tel
   qu'écrit. */
const compact = t => plat(t).replace(/[^a-z0-9]/g, "");
function materielLu(texte){
  const parc = [
    ...PROJECTEURS.map(p => ({ nom:p.marque + " " + p.nom, court:p.nom, cles:[p.nom, p.marque + p.nom], nb:p.nb, ref:p })),
    ...MACHINERIE.map(m => ({ nom:m.nom, court:m.nom, cles:[m.nom], nb:m.nb, ref:m })),
    ...HAUTEURS.map(h => ({ nom:h.nom, court:h.nom, cles:[h.nom], nb:h.nb, ref:h })),
    ...(typeof DIVERS !== "undefined" ? DIVERS : []).map(x => ({ nom:x.nom, court:x.nom,
        cles:[x.nom, (x.marque || "") + x.nom], nb:x.nb, ref:x }))
  ].sort((a, b) => b.cles[0].length - a.cles[0].length);    // « 614 SX » avant « 614 S »
  const trouver = q => {
    const c = compact(q);
    if(c.length < 2) return null;
    return parc.find(p => p.cles.some(k => compact(k) === c))
        || parc.find(p => p.cles.some(k => { const k2 = compact(k); return k2.length > 2 && (k2.includes(c) || c.includes(k2)); }));
  };
  const out = [];
  String(texte || "").split(/[,;+\n]|\s(?:et|avec)\s/i).forEach(bloc => {
    /* Une quantité en tête de morceau : « 2x », « 2 x », « 2× », « x2 » à la fin. */
    const morceaux = bloc.split(/(?=(?:^|\s)\d{1,3}\s*[x×*]\s*[^\s\d])/i);
    morceaux.forEach(m => {
      let t = m.trim(), q = 1, r;
      if(!t) return;
      if((r = t.match(/^(\d{1,3})\s*[x×*]\s*(.+)$/i))){ q = +r[1]; t = r[2]; }
      else if((r = t.match(/^(.+?)\s*[x×*]\s*(\d{1,3})$/i))){ q = +r[2]; t = r[1]; }
      else if((r = t.match(/^(\d{1,3})\s+(\D.*)$/))){ q = +r[1]; t = r[2]; }
      const p = trouver(t);
      out.push({ q, texte:t.trim(), nom:p ? p.nom : "", court:p ? p.court : "", nb:p && p.nb !== "" && p.nb != null ? +p.nb : null,
                ref:p ? p.ref : null });
    });
  });
  return out.filter(o => o.texte);
}

/* Ce qui manque au parc aujourd'hui : le matériel des prêts sortis et pas
   encore rendus, retards compris. Un prêt à venir ne compte pas encore, un
   emprunt jamais. Le compte se fait une fois par rendu (`render` le remet à
   zéro) et se range par fiche du parc. */
let SORTIS = null;
function sortisDuParc(){
  if(SORTIS) return SORTIS;
  const j = logAuj(), m = new Map();
  logVivants().forEach(x => {
    if(x.type !== "pret" || x.fait || !x.debut || x.debut > j) return;
    if((x.sens || "Prêté à") !== "Prêté à") return;
    const fois = x.qte ? Math.max(1, +x.qte || 1) : 1;      // fiches d'avant, champ quantité
    materielLu(x.titre).forEach(o => {
      if(!o.ref) return;
      const s = m.get(o.ref) || { q:0, prets:[] };
      s.q += o.q * fois;
      if(!s.prets.includes(x)) s.prets.push(x);
      m.set(o.ref, s);
    });
  });
  return SORTIS = m;
}
/* Le nombre restant d'une fiche du parc, ou null si rien n'est sorti. */
function parcActuel(obj){
  const s = sortisDuParc().get(obj);
  if(!s || obj.nb === "" || obj.nb == null) return null;
  return Math.max(0, +obj.nb - s.q);
}

/* ------------------------------- la liste ------------------------------- */
const LOGV = { type:null, fini:false, msg:"", mois:null, jour:null, plus:false };

function rowLog(x, e){
  const def = LOG_TYPES[x.type];
  const b = el("button", "row lg-row" + (e.g === "retard" ? " lg-retard" : "") + (e.g === "fini" ? " lg-fini" : ""));
  b.style.setProperty("--c", def.c);
  b.innerHTML = `<span class="av">${ic(def.i)}</span>
    <span class="g"><b>${esc(logTitre(x))}</b><span>${esc(logSous(x))}</span></span>
    <span class="r">${esc(e.r)}${e.r2 ? `<i>${esc(e.r2)}</i>` : ""}</span>`;
  b.onclick = () => { toucher(); go({ v:"logf", i:x.id }); };
  return b;
}

/* L'écran s'ouvre sur le mois, comme l'agenda du téléphone ; la liste par
   urgence reste à un geste. Le choix est gardé d'une fois sur l'autre. */
const CLE_LOG_VUE = "logistique.vue.v1";
function vLog(){
  agendaAuto();
  if(!LOGV.mode) LOGV.mode = lireLocal(CLE_LOG_VUE, "mois") === "liste" ? "liste" : "mois";
  return LOGV.mode === "liste" ? vLogListe() : vLogMois();
}

/* En-tête commun aux deux vues, sur une seule ligne pour laisser la hauteur
   au mois : le mois entre ses flèches ou le titre, aujourd'hui, la bascule
   vers l'autre vue, la synchronisation. */
function teteLog(titre, nav){
  const t = el("div", "cal-tete");
  if(nav){
    const pr = el("button", "cal-nav", ic("back"));
    pr.setAttribute("aria-label", "Mois précédent");
    pr.onclick = () => { toucher(); nav(-1); };
    const sv = el("button", "cal-nav cal-suiv", ic("back"));
    sv.setAttribute("aria-label", "Mois suivant");
    sv.onclick = () => { toucher(); nav(1); };
    t.append(pr, el("h2", null, esc(titre)), sv);
  } else t.append(el("h2", null, esc(titre)));
  t.append(el("span", "cal-esp"));
  if(nav){
    const auj = el("button", "cal-auj", `<span>${new Date().getDate()}</span>`);
    auj.setAttribute("aria-label", "Revenir à aujourd'hui");
    auj.onclick = () => { toucher(); LOGV.mois = null; LOGV.jour = logAuj(); render(); };
    t.append(auj);
  }
  const autre = LOGV.mode === "liste" ? "mois" : "liste";
  const bv = el("button", "cal-reg", ic(autre === "mois" ? "agenda" : "liste"));
  bv.setAttribute("aria-label", autre === "mois" ? "Voir le mois" : "Voir la liste par urgence");
  bv.onclick = () => { toucher(); LOGV.mode = autre; ecrireLocal(CLE_LOG_VUE, autre); window.scrollTo(0, 0); render(); };
  const reg = el("button", "cal-reg", ic("sync"));
  reg.setAttribute("aria-label", "Synchroniser : agenda du téléphone et fichiers");
  reg.onclick = () => { toucher(); go({ v:"logr" }); };
  t.append(bv, reg);
  const w = el("div", "cal-haut");
  w.append(t);
  return w;
}

/* ------------------------------- le mois -------------------------------- */
/* Sur le modèle de l'agenda Google en vue mois : une rangée par semaine avec
   son numéro, sept colonnes, les fiches de plusieurs jours en barres qui
   courent sur les jours, les autres en pastilles, aujourd'hui dans un rond.
   Le mois tient sur l'écran : la hauteur des semaines se calcule sur la place
   qui reste, et le nombre de pastilles visibles avec. Toucher un jour le
   choisit ; ce qu'il contient et les ajouts sont dans le volet du bas. */
const MOIS = ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août",
              "septembre", "octobre", "novembre", "décembre"];
const CAL_LIGNES = 3;                          // pastilles par semaine avant la mesure de l'écran
const CAL_T = 26, CAL_L = 17, CAL_E = 2;       // numéro du jour, ligne de pastille, écart (px)
const CLE_LOG_VOLET = "logistique.volet.v1";

const numSemaine = j => {
  const [a, m, d] = j.split("-").map(Number);
  const t = new Date(Date.UTC(a, m - 1, d));
  const n = (t.getUTCDay() + 6) % 7;
  t.setUTCDate(t.getUTCDate() - n + 3);
  const p = new Date(Date.UTC(t.getUTCFullYear(), 0, 4));
  return 1 + Math.round(((t - p) / 864e5 - 3 + ((p.getUTCDay() + 6) % 7)) / 7);
};
const lundiDe = j => { const [a, m, d] = j.split("-").map(Number);
  const n = (new Date(a, m - 1, d).getDay() + 6) % 7; return plusJours(j, -n); };
const heureCourte = h => { if(!h) return ""; const [a, b] = h.split(":"); return +a + "h" + (b && b !== "00" ? b : ""); };

/* La couleur d'une fiche : celle de son agenda si elle en vient, sinon celle
   de son carnet ; un prêt en retard passe au rouge. */
function couleurLog(x, e){
  if(e && e.g === "retard") return "var(--ko)";
  const src = String(x.source || "");
  if(src.startsWith("agenda:")){
    const c = (logCal().couleurs || {})[src.slice(7)];
    if(c) return c;
  }
  return (LOG_TYPES[x.type] || LOG_TYPES.ev).c;
}
const etiquette = x => (x.type === "ev" && x.hdebut ? heureCourte(x.hdebut) + " " : "") + logTitre(x);
/* Un spectacle n'occupe que ses jours de jeu ; le reste, tout son intervalle. */
const surJour = (x, j) => x.type === "spec" ? seancesDu(x, j).length > 0
  : x.debut && x.debut <= j && finDe(x) >= j;

function vLogMois(){
  const j = logAuj();
  if(!LOGV.jour) LOGV.jour = j;
  if(LOGV.volet == null) LOGV.volet = lireLocal(CLE_LOG_VOLET, false) === true;
  const mois = LOGV.mois || LOGV.jour.slice(0, 7);
  const [a, m] = mois.split("-").map(Number);
  const d = el("div", "lg cal");
  /* Changer de mois choisit aussi un jour de ce mois : aujourd'hui s'il y
     est, sinon le premier. */
  const aller = n => { const t = iso(new Date(a, m - 1 + n, 1)); LOGV.mois = t.slice(0, 7);
    LOGV.jour = j.slice(0, 7) === LOGV.mois ? j : t; render(); };
  /* Les flèches font glisser la piste comme le doigt (voir `glisser` plus bas). */
  function nav(n){ glisser(n); }
  d.append(teteLog(MOIS[m - 1].replace(/^./, c => c.toUpperCase()) + (a !== new Date().getFullYear() ? " " + a : ""), nav));

  const tous = logVivants().filter(x => x.debut);
  const etats = new Map(tous.map(x => [x.id, logEtat(x, j)]));
  const retards = tous.filter(x => etats.get(x.id).g === "retard");
  if(retards.length){
    const b = el("button", "cal-alerte", `<b>${retards.length}</b> ${retards.length > 1 ? "prêts" : "prêt"} en retard`
      + `<span>${esc(retards.map(logTitre).slice(0, 2).join(", "))}${retards.length > 2 ? "…" : ""}</span>`);
    b.onclick = () => { toucher(); LOGV.mode = "liste"; ecrireLocal(CLE_LOG_VUE, "liste"); render(); };
    d.append(b);
  }

  const grille = el("div", "cal-grille");
  const tete = el("div", "cal-jours");
  tete.innerHTML = `<span></span>` + ["lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim."]
    .map((n, i) => `<span${i === (new Date().getDay() + 6) % 7 && mois === j.slice(0, 7) ? ' class="auj"' : ""}>${n}</span>`).join("");
  /* Trois mois côte à côte sur une piste : le précédent, celui qu'on regarde,
     le suivant. Le doigt fait glisser la piste, on voit arriver le mois
     d'à côté, et elle s'accroche au plus proche quand on lâche. */
  const piste = el("div", "cal-piste");
  const corps = el("div", "cal-corps");
  corps.append(piste);
  grille.append(tete, corps);

  const moisVoisin = n => iso(new Date(a, m - 1 + n, 1)).slice(0, 7);
  const lundisDe = mo => {
    const [y, k] = mo.split("-").map(Number);
    const out = [], dern = iso(new Date(y, k, 0));
    for(let l = lundiDe(mo + "-01"); l <= dern; l = plusJours(l, 7)) out.push(l);
    return out;
  };

  /* Une semaine du mois `mo`, avec au plus `L` lignes de pastilles et, si elle
     a une hauteur imposée, étirée jusqu'à elle. */
  const semaine = (l, L, h, mo) => {
    const jours = [...Array(7)].map((_, i) => plusJours(l, i));
    const dim = jours[6];
    const sem = el("div", "cal-sem");
    const num = el("span", "cal-num", String(numSemaine(l)));
    sem.append(num);
    /* Le fond des jours d'abord : c'est lui qu'on touche. */
    jours.forEach((jj, i) => {
      const f = el("button", "cal-fond" + (jj.slice(0, 7) !== mo ? " hors" : "")
        + (jj === LOGV.jour ? " choisi" : ""));
      f.style.gridColumn = String(i + 2);
      f.setAttribute("aria-label", jourCourt(jj, true));
      /* Toucher un jour l'ouvre : le volet se déplie sur son contenu. */
      f.onclick = () => { toucher();
        LOGV.jour = jj; LOGV.volet = true; ecrireLocal(CLE_LOG_VOLET, true);
        if(jj.slice(0, 7) !== mois) LOGV.mois = jj.slice(0, 7);
        render(); };
      f.append(el("span", "cal-n" + (jj === j ? " auj" : ""), String(+jj.slice(8))));
      sem.append(f);
    });

    /* Les fiches de la semaine. Un spectacle donne une pastille par jour de
       jeu, avec ses heures ; les autres fiches, une barre de leur premier à
       leur dernier jour. */
    const items = [];
    tous.forEach(x => {
      if(x.type === "spec"){
        if(x.debut > dim || finDe(x) < l) return;
        jours.forEach((jj, i) => { const s = seancesDu(x, jj);
          if(s.length) items.push({ x, cs:i, ce:i, h:s[0], lab:s.map(heureCourte).join(" ") + " " + x.titre }); });
        return;
      }
      const fin = finDe(x);
      if(x.debut > dim || fin < l) return;
      items.push({ x, cs:x.debut < l ? 0 : ecartJours(l, x.debut), ce:fin > dim ? 6 : ecartJours(l, fin),
                   long:fin !== x.debut, avant:x.debut < l, apres:fin > dim, h:x.hdebut || "", lab:etiquette(x) });
    });
    /* Les plus longues d'abord, chacune sur la première ligne libre. */
    items.sort((p, q) => (q.ce - q.cs) - (p.ce - p.cs) || p.cs - q.cs
      || p.h.localeCompare(q.h) || p.x.titre.localeCompare(q.x.titre));
    const placer = n => {
      const lignes = [], cache = Array(7).fill(0), pos = [];
      items.forEach(it => {
        let li = 0;
        while(lignes[li] && lignes[li].slice(it.cs, it.ce + 1).some(Boolean)) li++;
        if(li >= n){ for(let c = it.cs; c <= it.ce; c++) cache[c]++; return; }
        (lignes[li] = lignes[li] || Array(7).fill(false)).fill(true, it.cs, it.ce + 1);
        pos.push([it, li]);
      });
      return { pos, cache };
    };
    /* Ce qui déborde laisse sa dernière ligne au « +n ». */
    let pl = placer(L);
    if(pl.cache.some(Boolean)) pl = placer(L - 1);
    pl.pos.forEach(([it, li]) => {
      const p = el("span", "cal-ev" + (it.long ? " long" : "") + (it.avant ? " avant" : "") + (it.apres ? " apres" : "")
        + (it.x.fait ? " fait" : ""), esc(it.lab));
      p.style.gridColumn = (it.cs + 2) + " / " + (it.ce + 3);
      p.style.gridRow = String(li + 2);
      p.style.setProperty("--c", couleurLog(it.x, etats.get(it.x.id)));
      /* Toucher une pastille ouvre sa fiche, pour la modifier ou la retirer. */
      p.setAttribute("role", "button");
      p.setAttribute("aria-label", "Ouvrir : " + logTitre(it.x));
      p.onclick = () => { toucher(); go({ v:"logf", i:it.x.id }); };
      sem.append(p);
    });
    pl.cache.forEach((n, i) => {
      if(!n) return;
      const p = el("span", "cal-plus", "+" + n);
      /* Le « +n » montre le jour dans le volet, déplié. */
      p.setAttribute("role", "button");
      p.onclick = () => { toucher(); LOGV.jour = plusJours(l, i); LOGV.volet = true; ecrireLocal(CLE_LOG_VOLET, true); render(); };
      p.style.gridColumn = String(i + 2);
      p.style.gridRow = String(L + 1);
      sem.append(p);
    });
    sem.style.gridTemplateRows = `${CAL_T}px repeat(${L}, ${CAL_L}px)` + (h ? " 1fr" : "");
    if(h) sem.style.height = h + "px";
    num.style.gridRow = "1 / -1";
    sem.querySelectorAll(".cal-fond").forEach(f => f.style.gridRow = "1 / -1");
    return sem;
  };

  /* Chaque mois remplit la hauteur disponible avec ses propres semaines : un
     mois de cinq lignes n'a pas de rang vide sous lui. */
  const volets = [-1, 0, 1].map(moisVoisin);
  const dessiner = place => piste.replaceChildren(...volets.map(mo => {
    const ls = lundisDe(mo);
    const h = place ? Math.max(58, Math.floor(place / ls.length)) : 0;
    const L = h ? Math.max(1, Math.floor((h - 3 - CAL_T - CAL_E) / (CAL_L + CAL_E))) : CAL_LIGNES;
    const v = el("div", "cal-vue");
    v.append(...ls.map(l => semaine(l, L, h && h - 3, mo)));
    return v;
  }));
  dessiner(0);

  /* La piste est posée sur le mois du milieu. Un pourcentage de translation
     se mesure sur l'élément déplacé : la piste fait trois largeurs, donc un
     mois vaut un tiers. */
  const caler = (dx, anim) => {
    piste.style.transition = anim ? "transform .3s cubic-bezier(.22,.72,.26,1)" : "none";
    piste.style.transform = `translate3d(calc(-33.3333% + ${dx}px), 0, 0)`;
  };
  caler(0, false);
  let occupe = false;
  const glisser = n => {
    if(occupe) return;
    occupe = true;
    caler(-n * grille.clientWidth, true);
    setTimeout(() => aller(n), 300);
  };

  /* Le doigt : la piste suit tant que le geste est horizontal, et on ne
     retient le mois d'à côté qu'au-delà du quart de l'écran. */
  let x0 = null, y0 = null, pris = false;
  grille.addEventListener("touchstart", e => {
    if(occupe) return;
    x0 = e.touches[0].clientX; y0 = e.touches[0].clientY; pris = false;
  }, { passive:true });
  grille.addEventListener("touchmove", e => {
    if(x0 == null || occupe) return;
    const dx = e.touches[0].clientX - x0, dy = e.touches[0].clientY - y0;
    if(!pris){
      if(Math.abs(dx) < 10 && Math.abs(dy) < 10) return;
      if(Math.abs(dx) < Math.abs(dy)){ x0 = null; return; }   // c'est la page qui défile
      pris = true;
    }
    e.preventDefault();
    caler(dx, false);
  }, { passive:false });
  const lacher = e => {
    if(x0 == null) return;
    const dx = (e.changedTouches ? e.changedTouches[0].clientX : x0) - x0;
    x0 = null;
    if(!pris) return;
    pris = false;
    if(Math.abs(dx) > Math.min(90, grille.clientWidth / 4)) glisser(dx < 0 ? 1 : -1);
    else caler(0, true);
  };
  grille.addEventListener("touchend", lacher, { passive:true });
  grille.addEventListener("touchcancel", lacher, { passive:true });
  d.append(grille);

  const volet = voletJour(tous, etats);
  d.append(volet);

  /* Une fois la page posée, on mesure la place entre le haut de la grille et
     le volet replié, au-dessus de la barre d'onglets, et on la partage. */
  const ajuster = () => {
    if(!corps.isConnected) return;
    const tb = document.querySelector("nav.tabbar");
    const bas = tb ? tb.getBoundingClientRect().height : 0;     // 0 si la barre est cachée
    volet.style.bottom = bas + "px";
    const poi = volet.querySelector(".cal-poignee");
    const place = window.innerHeight - bas - (poi ? poi.getBoundingClientRect().height + 10 : 60)
                - (corps.getBoundingClientRect().top + window.scrollY) - 6;
    dessiner(place);
  };
  requestAnimationFrame(ajuster);
  const surTaille = () => requestAnimationFrame(ajuster);
  window.addEventListener("resize", surTaille);
  poser(() => window.removeEventListener("resize", surTaille));
  return d;
}

/* Le volet du jour choisi, collé au-dessus des onglets : replié, une ligne
   avec la date et le compte ; déplié, les fiches du jour et les ajouts, qui
   partent de cette date. Replié ou non, le choix est gardé. */
function voletJour(tous, etats){
  const j = logAuj(), jj = LOGV.jour;
  const v = el("div", "cal-volet" + (LOGV.volet ? " ouvert" : ""));
  const du = tous.filter(x => surJour(x, jj))
    .map(x => ({ x, h:x.type === "spec" ? seancesDu(x, jj)[0] : x.hdebut || "" }))
    .sort((p, q) => p.h.localeCompare(q.h) || p.x.titre.localeCompare(q.x.titre));
  const titreJour = new Date(...jj.split("-").map((n, i) => i === 1 ? n - 1 : +n))
    .toLocaleDateString("fr-FR", { weekday:"long", day:"numeric", month:"long" });
  const poi = el("button", "cal-poignee",
    `<span class="g"><b>${esc(titreJour)}</b><span>${jj === j ? "aujourd'hui · " : ""}${
      du.length ? du.length + (du.length > 1 ? " fiches" : " fiche") : "rien de prévu"}</span></span>
     <span class="cal-ouvrir">${ic("plus")}</span>`);
  const montrer = o => { v.classList.toggle("ouvert", o); poi.setAttribute("aria-expanded", String(o));
    poi.setAttribute("aria-label", (o ? "Replier" : "Déplier") + " : " + titreJour + ", ajouter une fiche"); };
  montrer(LOGV.volet);
  poi.onclick = () => { toucher(); LOGV.volet = !LOGV.volet; ecrireLocal(CLE_LOG_VOLET, LOGV.volet); montrer(LOGV.volet); };
  v.append(poi);

  /* Les ajouts d'abord : c'est ce qu'on vient chercher en dépliant. */
  const c = el("div", "cal-contenu");
  const aj = el("div", "lg-ajouts");
  Object.entries(LOG_TYPES).filter(([, t]) => !t.ferme).forEach(([k, t]) => {
    const b = el("button", "ajout", ic("plus") + "<span>" + esc(t.t) + "</span>");
    b.style.setProperty("--c", t.c);
    b.onclick = () => { toucher(); go({ v:"logf", i:null, k, p:jj }); };
    aj.append(b);
  });
  c.append(aj);
  if(du.length){
    const l = el("div", "rowlist");
    du.forEach(({ x }) => {
      /* Pour un spectacle, les heures de ce jour-là plutôt que l'état du jour. */
      const s = seancesDu(x, jj);
      const e = x.type === "spec" ? { g:"auj", r:heuresDites(s), r2:finDe(x) === x.debut ? "" : finDe(x) === jj ? "dernière" : x.debut === jj ? "première" : "" }
                                  : etats.get(x.id);
      const r = rowLog(x, e);
      r.style.setProperty("--c", couleurLog(x, etats.get(x.id)));
      l.append(r);
    });
    c.append(l);
  }
  v.append(c);
  return v;
}

/* ------------------------------ les réglages ---------------------------- */
function vLogReglages(){
  const d = el("div", "lg");
  d.append(bar("Synchroniser", null, () => retour({ v:"log" })));
  /* Trois cartes, une à la fois : la liste des agendas est longue, et ce qui
     vient après elle ne se trouvait qu'en descendant. */
  const ongl = [["lire", "Lire", carteAgenda], ["ecrire", "Écrire", carteSortie], ["fichier", "Fichiers", carteEchange]];
  if(!LOGV.reg || !ongl.some(o => o[0] === LOGV.reg)) LOGV.reg = "lire";
  const seg = el("div", "lg-seg lg-onglets");
  seg.style.setProperty("--c", "var(--log)");
  ongl.forEach(([k, lab]) => {
    const b = el("button", null, esc(lab));
    b.setAttribute("aria-pressed", LOGV.reg === k ? "true" : "false");
    b.onclick = () => { toucher(); LOGV.reg = k; render(); };
    seg.append(b);
  });
  d.append(seg, ongl.find(o => o[0] === LOGV.reg)[2]());
  return d;
}

/* ------------------------------- la liste ------------------------------- */
function vLogListe(){
  const d = el("div", "lg");
  const j = logAuj();
  const tous = logVivants();
  d.append(teteLog("Logistique"));

  const etats = tous.map(x => ({ x, e:logEtat(x, j) }));
  /* Le point du jour, en tête : ce qui presse se lit sans descendre. */
  const nb = g => etats.filter(o => o.e.g === g).length;
  const presents = etats.filter(o => o.x.type === "stag" && ["auj", "cours"].includes(o.e.g)).length;
  const point = el("div", "lg-point");
  point.innerHTML = [
    [nb("retard"), "en retard", nb("retard") ? "ko" : ""],
    [nb("auj"), "aujourd'hui", ""],
    [nb("semaine"), "sous 7 jours", ""],
    [presents, "stagiaire" + (presents > 1 ? "s" : "") + " présent" + (presents > 1 ? "s" : ""), ""]
  ].map(([v, l, cl]) => `<div class="${cl}"><b>${v}</b><span>${l}</span></div>`).join("");
  d.append(point);

  /* Les trois ajouts restent en haut : c'est la moitié de ce qu'on vient faire. */
  const aj = el("div", "lg-ajouts");
  Object.entries(LOG_TYPES).filter(([, t]) => !t.ferme).forEach(([k, t]) => {
    const b = el("button", "ajout", ic("plus") + "<span>" + esc(t.t) + "</span>");
    b.style.setProperty("--c", t.c);
    b.onclick = () => { toucher(); go({ v:"logf", i:null, k }); };
    aj.append(b);
  });
  d.append(aj);

  d.append(recherche("log", "Nom, matériel, lieu, école…"));
  const p = el("div", "pills");
  const carnet = x => LOG_TYPES[x.type].comme || x.type;
  [[null, "Tout", tous.length], ...Object.entries(LOG_TYPES).filter(([, t]) => !t.ferme).map(([k, t]) =>
      [k, t.pl, tous.filter(x => carnet(x) === k).length])].forEach(([k, lab, n]) => {
    const b = el("button", "pill", esc(lab) + (n ? ` <i>${n}</i>` : ""));
    b.setAttribute("aria-pressed", String(LOGV.type === k));
    b.onclick = () => { toucher(); LOGV.type = k; render(); };
    p.append(b);
  });
  d.append(p);

  const q = plat(S.qs.log || "");
  const vus = etats.filter(({ x }) => (!LOGV.type || carnet(x) === LOGV.type)
    && (!q || plat([x.titre, x.lieu, x.tiers, x.tuteur, x.formation, x.service, x.spectacle,
                    x.cat, x.contact, x.note].join(" ")).includes(q)));

  if(!tous.length){
    d.append(el("p", "vide", "Rien de noté pour l'instant. Ajoutez un événement, un prêt ou un stagiaire, "
      + "ou reliez l'agenda du téléphone avec le bouton en haut à droite."));
  } else if(!vus.length){
    d.append(el("p", "vide", "Aucune fiche ne correspond."));
  }

  LOG_GROUPES.forEach(g => {
    const items = vus.filter(o => o.e.g === g.k)
      .sort((a, b) => g.k === "fini" ? String(b.e.cle).localeCompare(a.e.cle)
                                     : String(a.e.cle).localeCompare(b.e.cle));
    if(!items.length) return;
    d.insertAdjacentHTML("beforeend",
      `<h2 class="sec${g.k === "retard" ? " lg-ko" : ""}">${g.t} · ${items.length}</h2>`);
    const l = el("div", "rowlist");
    /* Les terminés s'accumulent : repliés par défaut, derrière une ligne. */
    if(g.k === "fini" && !LOGV.fini && !q){
      const b = el("button", "voir", `Afficher les ${items.length} fiches terminées`);
      b.onclick = () => { toucher(); LOGV.fini = true; render(); };
      l.append(b);
    } else items.forEach(({ x, e }) => l.append(rowLog(x, e)));
    d.append(l);
  });

  return d;
}

/* ------------------------- l'agenda du téléphone ------------------------ */
/* Today, Google Agenda et les autres applications d'agenda d'Android écrivent
   dans un même agenda du système. Patch le lit directement : on choisit les
   agendas à suivre, et leurs rendez-vous arrivent dans la liste comme des
   événements, sans fichier à passer. La lecture se refait à l'ouverture de
   l'écran (au plus toutes les cinq minutes) et au bouton « Actualiser ».

   Une fiche venue de l'agenda garde l'identifiant de son rendez-vous. Tant
   qu'on ne l'a pas modifiée ici, elle suit l'agenda : changée là-bas, elle
   change ici ; supprimée là-bas, elle disparaît. Modifiée ici, elle devient
   une fiche de Patch et l'agenda ne la touche plus. */
const CLE_LOG_CAL = "logistique.agendas.v1";
let LOG_CAL = null;
function logCal(){
  if(!LOG_CAL) LOG_CAL = Object.assign({ ids:[], noms:{}, derniere:0 }, lireLocal(CLE_LOG_CAL, {}));
  return LOG_CAL;
}
const sauverCal = () => ecrireLocal(CLE_LOG_CAL, logCal());
const CAL_AVANT = 30, CAL_APRES = 180;         // jours lus de part et d'autre d'aujourd'hui


/* Le rendez-vous que vaut une fiche dans l'agenda du téléphone. Un spectacle
   part avec sa prochaine représentation ; un prêt ou un stagiaire tient la
   journée entière, qu'Android attend posée à minuit UTC, fin exclue. */
function momentTel(x){
  const type = x.type;
  const pro = type === "spec" ? prochaineDe(x, logAuj()) || prochaineDe(x, x.debut) : null;
  const dj = pro ? pro.j : x.debut;
  if(!dj) return null;
  const [a, m, j] = dj.split("-").map(Number);
  const fj = (pro ? pro.j : x.fin || x.debut).split("-").map(Number);
  const hd = pro ? pro.s[0] : x.hdebut;
  const dur = heureLue(x.duree);
  const hf = pro ? (dur ? ajouterHeure(hd, dur) : ajouterHeure(hd, "02:00")) : x.hfin;
  const journee = !((type === "ev" || type === "spec") && hd);
  const h = (s, def) => (s || def).split(":").map(Number);
  const [h1, m1] = h(hd, "00:00");
  const [h2, m2] = h(hf, hd ? String(Math.min(23, h1 + 1)) + ":" + String(m1) : "00:00");
  const debut = journee ? Date.UTC(a, m - 1, j) : new Date(a, m - 1, j, h1, m1).getTime();
  const fin = journee ? Date.UTC(fj[0], fj[1] - 1, fj[2] + 1)
                      : new Date(fj[0], fj[1] - 1, fj[2], h2, m2).getTime();
  const titre = type === "pret" ? "Prêt : " + logTitre(x)
              : type === "stag" ? "Stagiaire : " + x.titre : x.titre;
  return { titre, lieu:x.lieu || "", note:[logSous(x), x.contact, x.note].filter(Boolean).join("\n"),
           debut, fin, journee };
}

/* ------------------ écriture dans l'agenda du téléphone ------------------ */
/* Chaque genre de fiche peut aller dans un agenda du téléphone, choisi dans
   Synchroniser. La fiche garde l'identifiant du rendez-vous écrit (`tel`),
   pour le remplacer au lieu d'en faire un deuxième, et le retirer avec elle.
   Une fiche venue de l'agenda ne repart jamais : elle y est déjà. */
const sortieDe = x => (!x || /^cal-/.test(x.id) ? "" : (logCal().sortie || {})[x.type === "spec" ? "ev" : x.type] || "");

function pousserTel(x){
  const cal = sortieDe(x);
  if(!cal || !NET.agendaTel.dispo || !NET.agendaTel.ecritAutorise()) return;
  const r = momentTel(x);
  if(!r) return;
  /* L'agenda de sortie a changé : l'ancien rendez-vous s'en va. */
  if(x.tel && x.telCal && String(x.telCal) !== String(cal)){ NET.agendaTel.retirer(x.tel); x.tel = 0; }
  const ev = NET.agendaTel.ecrire(cal, x.tel, r);
  if(ev){ x.tel = ev; x.telCal = String(cal); } else { delete x.tel; delete x.telCal; }
}

function retirerTel(x){
  if(!x || !x.tel || !NET.agendaTel.dispo || !NET.agendaTel.ecritAutorise()) return;
  NET.agendaTel.retirer(x.tel);
}

/* Les rendez-vous que Patch a écrits lui-même : on ne les relit pas comme des
   fiches, sinon chaque fiche apparaîtrait deux fois. */
function evenementsEcrits(){
  const s = new Set();
  logTout().forEach(x => { if(x.tel && !x.supprime) s.add(String(x.tel)); });
  return s;
}

function syncAgenda(){
  if(!NET.agendaTel.dispo || !NET.agendaTel.autorise()) return { erreur:"autorisation refusée" };
  const cal = logCal(), ids = cal.ids.map(String);
  const j = logAuj(), [a, m, d] = j.split("-").map(Number);
  const debut = new Date(a, m - 1, d - CAL_AVANT).getTime(), fin = new Date(a, m - 1, d + CAL_APRES).getTime();
  const lus = ids.length ? NET.agendaTel.lire(ids, debut, fin) : [];
  const agendas = NET.agendaTel.liste();
  if(Array.isArray(agendas)) cal.couleurs = Object.fromEntries(agendas.map(x => [String(x.id), x.couleur]));
  if(!Array.isArray(lus)) return { erreur:(lus && lus.erreur) || "agenda illisible" };
  const vus = new Set(), miens = evenementsEcrits();
  const fiches = lus.filter(o => o.titre && o.debut && !miens.has(String(o.id))).map(o => {
    const id = "cal-" + o.id + "-" + o.debut;
    vus.add(id);
    const x = { id, type:"ev", titre:o.titre, debut:o.debut, cat:o.nomAgenda || "Agenda",
                source:"agenda:" + o.agenda, maj:1 };
    if(o.fin && o.fin !== o.debut) x.fin = o.fin;
    ["hdebut", "hfin", "lieu", "note"].forEach(k => { if(o[k]) x[k] = o[k]; });
    return x;
  });
  const r = fusionner(fiches);
  /* Ce que l'agenda ne rend plus, dans la période lue ou dans un agenda qu'on
     ne suit plus, et qu'on n'a pas retouché ici. */
  const bas = plusJours(j, -CAL_AVANT), haut = plusJours(j, CAL_APRES);
  logTout().forEach((x, i, t) => {
    if(x.supprime || !/^cal-/.test(x.id) || x.maj !== 1 || vus.has(x.id)) return;
    const suivi = ids.includes(String(x.source || "").replace("agenda:", ""));
    if(!suivi || (x.debut >= bas && x.debut <= haut)){ t[i] = { id:x.id, type:x.type, supprime:true, maj:1 }; r.suppr++; }
  });
  sauverLog();
  cal.derniere = Date.now();
  sauverCal();
  return { ...r, lus:fiches.length };
}

/* À l'ouverture de l'écran : une lecture si la dernière date de plus de cinq
   minutes. Hors du rendu en cours, qu'on refait si quelque chose a changé. */
function agendaAuto(){
  const cal = logCal();
  if(!cal.ids.length || !NET.agendaTel.dispo || Date.now() - cal.derniere < 5 * 60e3) return;
  cal.derniere = Date.now();
  setTimeout(() => {
    if(!NET.agendaTel.autorise()) return;
    const r = syncAgenda();
    if(!r.erreur && (r.nouveaux || r.maj || r.suppr) && S.route.v === "log") render();
  }, 50);
}

function carteAgenda(){
  const cal = logCal();
  const c = el("div", "card lg-cal");
  c.innerHTML = `<h4>Agenda du téléphone</h4>`;
  if(!NET.agendaTel.dispo){
    c.insertAdjacentHTML("beforeend", `<p class="muted">Dans l'application Android, Patch lit directement
      les agendas du téléphone (Today, Google Agenda…) : leurs rendez-vous arrivent ici tout seuls.</p>`);
    return c;
  }
  const msg = el("p", "lg-msg", esc(LOGV.calMsg || ""));
  msg.hidden = !LOGV.calMsg;
  const dire = t => { LOGV.calMsg = t; msg.textContent = t; msg.hidden = !t; };
  const pl = (n, un, x) => n + " " + un + (n > 1 ? x || "s" : "");
  const actualiser = () => {
    const r = syncAgenda();
    dire(r.erreur ? "Lecture impossible : " + r.erreur + "."
      : r.lus + " rendez-vous lu" + (r.lus > 1 ? "s" : "") + (r.nouveaux || r.maj || r.suppr
        ? ` : ${pl(r.nouveaux, "nouveau", "x")}, ${pl(r.maj, "modifié")}, ${pl(r.suppr, "retiré")}.`
        : ", rien de neuf."));
    render();
  };
  const g = el("div", "lg-btns");
  const btn = (lab, fn, prim) => {
    const b = el("button", prim ? "prim" : "", esc(lab));
    b.onclick = () => { toucher(); fn(); };
    g.append(b);
  };

  if(!NET.agendaTel.autorise()){
    c.insertAdjacentHTML("beforeend", `<p class="muted">Patch peut lire les agendas du téléphone (Today, Google
      Agenda…) et en montrer les rendez-vous ici, sans fichier à échanger. Android demandera l'autorisation une fois.</p>`);
    btn("Relier l'agenda", () => NET.agendaTel.demander(ok => {
      if(ok){ LOGV.calChoix = true; dire(""); render(); }
      else dire("Autorisation refusée. Elle se rétablit dans les réglages d'Android : Applications, Patch, Autorisations.");
    }), true);
    c.append(g, msg);
    return c;
  }

  if(!LOGV.calChoix && cal.ids.length){
    const noms = cal.ids.map(i => cal.noms[i] || "agenda " + i);
    const quand = cal.derniere ? new Date(cal.derniere).toLocaleTimeString("fr-FR", { hour:"2-digit", minute:"2-digit" }) : "";
    c.insertAdjacentHTML("beforeend", `<p class="muted">Suivi : <b>${esc(noms.join(", "))}</b>${
      quand ? ` · lu à ${esc(quand)}` : ""}. Les rendez-vous arrivent dans l'agenda comme des événements.</p>`);
    btn("Actualiser", actualiser, true);
    btn("Changer d'agendas", () => { LOGV.calChoix = true; render(); });
    c.append(g, msg);
    return c;
  }

  /* Choix des agendas : une case par agenda, cochées d'après ce qu'on suit. */
  const liste = NET.agendaTel.liste();
  if(!Array.isArray(liste) || !liste.length){
    c.insertAdjacentHTML("beforeend", `<p class="muted">${Array.isArray(liste)
      ? "Aucun agenda sur ce téléphone." : "Les agendas ne se lisent pas : " + esc((liste && liste.erreur) || "erreur") + "."}</p>`);
    return c;
  }
  c.insertAdjacentHTML("beforeend", `<p class="muted">Cochez les agendas à suivre, celui des
    spectacles compris.</p>`);
  const cases = el("div", "lg-agendas");
  const suivis = cal.ids.map(String);
  liste.forEach(a => {
    const l = el("label", "lg-coche");
    l.style.setProperty("--c", a.couleur || "var(--log)");
    l.innerHTML = `<input type="checkbox" value="${esc(a.id)}"${suivis.includes(String(a.id)) ? " checked" : ""}>
      <span class="pt"></span><span class="nm"><b>${esc(a.nom || "Sans nom")}</b>${
      a.compte && a.compte !== a.nom ? `<i>${esc(a.compte)}</i>` : ""}</span>`;
    cases.append(l);
  });
  c.append(cases);
  btn("Suivre ces agendas", () => {
    const ch = [...cases.querySelectorAll("input:checked")].map(i => i.value);
    cal.ids = ch;
    cal.noms = Object.fromEntries(liste.filter(a => ch.includes(String(a.id))).map(a => [String(a.id), a.nom]));
    cal.couleurs = Object.fromEntries(liste.map(a => [String(a.id), a.couleur]));
    sauverCal();
    LOGV.calChoix = false;
    actualiser();
  }, true);
  if(cal.ids.length) btn("Annuler", () => { LOGV.calChoix = false; render(); });
  c.append(g, msg);
  return c;
}

/* ------------------- ce que Patch écrit dans le téléphone ---------------- */
/* Un agenda du téléphone par genre de fiche : ce qu'on ajoute, modifie ou
   retire dans Patch suit tout seul, et se retrouve dans les autres agendas
   reliés au même compte. */
function carteSortie(){
  const cal = logCal();
  const c = el("div", "card lg-cal");
  c.innerHTML = `<h4>Écrire dans l'agenda du téléphone</h4>`;
  if(!NET.agendaTel.dispo){
    c.insertAdjacentHTML("beforeend", `<p class="muted">Dans l'application Android, chaque genre de fiche
      peut aller dans un agenda du téléphone, d'où les autres calendriers le reprennent.</p>`);
    return c;
  }
  if(!NET.agendaTel.autorise() || !NET.agendaTel.ecritAutorise()){
    c.insertAdjacentHTML("beforeend", `<p class="muted">Patch a besoin d'une autorisation pour écrire dans
      l'agenda du téléphone. Android ne la demande qu'une fois.</p>`);
    const g = el("div", "lg-btns"), m = el("p", "lg-msg");
    m.hidden = true;
    const b = el("button", "prim", "Autoriser l'écriture");
    b.onclick = () => { toucher(); NET.agendaTel.demander(ok => {
      if(ok) render();
      else { m.textContent = "Autorisation refusée. Elle se rétablit dans les réglages d'Android : Applications, Patch, Autorisations."; m.hidden = false; }
    }); };
    g.append(b);
    c.append(g, m);
    return c;
  }
  const liste = (NET.agendaTel.liste() || []).filter(a => a.ecrivable !== false);
  if(!Array.isArray(liste) || !liste.length){
    c.insertAdjacentHTML("beforeend", `<p class="muted">Aucun agenda de ce téléphone n'accepte d'écriture.</p>`);
    return c;
  }
  c.insertAdjacentHTML("beforeend", `<p class="muted">Ce que vous ajoutez, modifiez ou retirez dans Patch part
    aussitôt dans l'agenda choisi. Laissez « Nulle part » pour qu'un genre reste dans Patch seul.</p>`);
  const sortie = Object.assign({}, cal.sortie || {});
  const g = el("div", "lg-sorties");
  ["ev", "pret", "stag"].forEach(t => {
    const l = el("label", "lg-sortie");
    l.innerHTML = `<span>${esc(LOG_TYPES[t].pl)}</span><select data-t="${t}"><option value="">Nulle part</option>${
      liste.map(a => `<option value="${esc(a.id)}"${String(sortie[t] || "") === String(a.id) ? " selected" : ""}>${
        esc(a.nom || "Sans nom")}</option>`).join("")}</select>`;
    g.append(l);
  });
  c.append(g);
  const msg = el("p", "lg-msg");
  msg.hidden = true;
  const dire = t => { msg.textContent = t; msg.hidden = !t; };
  const bs = el("div", "lg-btns");
  const btn = (lab, fn, prim) => {
    const b = el("button", prim ? "prim" : "", esc(lab));
    b.onclick = () => { toucher(); fn(); };
    bs.append(b);
  };
  const relever = () => Object.fromEntries([...g.querySelectorAll("select")].map(s => [s.dataset.t, s.value]));
  btn("Enregistrer", () => {
    cal.sortie = relever();
    sauverCal();
    dire("Enregistré. Les prochaines fiches partiront dans ces agendas.");
  }, true);
  /* Les fiches déjà là ne sont jamais parties : ce bouton les rattrape. */
  btn("Envoyer les fiches d'ici", () => {
    cal.sortie = relever();
    sauverCal();
    let n = 0;
    logVivants().forEach(x => { const av = x.tel; pousserTel(x); if(x.tel && x.tel !== av) n++; });
    sauverLog();
    dire(n ? n + " fiche" + (n > 1 ? "s envoyées" : " envoyée") + " dans l'agenda."
           : "Rien de neuf à envoyer.");
  });
  c.append(bs, msg);
  return c;
}

/* --------------------------- échange de fichiers ------------------------ */
function carteEchange(){
  const c = el("div", "card lg-ech");
  c.innerHTML = `<h4>Échanger par fichier</h4>
    <p class="muted">Pour SceneFlow ou un autre téléphone : exportez le fichier, importez celui
    de l'autre côté. Les fiches déjà connues sont mises à jour, les nouvelles ajoutées.</p>`;
  const g = el("div", "lg-btns");
  const btn = (lab, fn, prim) => {
    const b = el("button", prim ? "prim" : "", esc(lab));
    b.onclick = () => { toucher(); fn(); };
    g.append(b);
  };
  const msg = el("p", "lg-msg", esc(LOGV.msg));
  msg.hidden = !LOGV.msg;
  const dire = t => { LOGV.msg = t; msg.textContent = t; msg.hidden = !t; };
  const nomF = ext => "logistique-" + logAuj() + "." + ext;
  const fin = (ok, t) => r => dire(r.erreur ? (r.erreur === "annulé" ? "" : "Échec : " + r.erreur + ".") : ok || t);

  btn("Importer un fichier", () => NET.fichier.ouvrir(r => {
    if(r.erreur){ fin()(r); return; }
    const res = importerLog(r.texte || "");
    dire(res.erreur ? "Import impossible : " + res.erreur
                    : `Import : ${res.nouveaux} ajouté${res.nouveaux > 1 ? "s" : ""}, `
                      + `${res.maj} mis à jour${res.suppr ? ", " + res.suppr + " retiré" + (res.suppr > 1 ? "s" : "") : ""}`
                      + `${res.ignores ? ", " + res.ignores + " ignoré" + (res.ignores > 1 ? "s" : "") : ""}.`);
    render();
  }), true);
  btn("JSON",  () => NET.fichier.enregistrer(nomF("json"), "application/json", exportJson(),
      fin("Fichier JSON enregistré.")));
  btn("Agenda .ics", () => NET.fichier.enregistrer(nomF("ics"), "text/calendar", exportIcs(),
      fin("Agenda enregistré : ouvrez-le avec votre application d'agenda.")));
  btn("Tableur CSV", () => NET.fichier.enregistrer(nomF("csv"), "text/csv", exportCsv(),
      fin("Fichier CSV enregistré.")));
  g.insertBefore(el("span", "lg-exp", "Exporter"), g.children[1]);
  c.append(g, msg);
  c.insertAdjacentHTML("beforeend", `<p class="muted lg-pied">Import accepté : le JSON de Patch, un CSV
    (séparateur ; ou ,) ou un agenda .ics.</p>`);
  return c;
}

const LOG_COLS = ["id", "type", "titre", "cat", "debut", "hdebut", "fin", "hfin", "lieu", "spectacle",
                  "qte", "sens", "tiers", "formation", "service", "tuteur", "contact", "note", "fait", "source",
                  "seances", "relache", "duree", "maj"];

function exportJson(){
  return JSON.stringify({ format:"patch-logistique", version:1, exporte:new Date().toISOString(),
                          elements:logTout() }, null, 1);
}

function exportCsv(){
  const cel = v => { const s = v == null ? "" : String(v === true ? "oui" : v === false ? "" : v);
                     return /[;"\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; };
  /* Le BOM fait lire l'UTF-8 à Excel ; le point-virgule est le séparateur
     qu'il attend en français. */
  return "\ufeff" + [LOG_COLS.join(";"), ...logVivants().map(x => LOG_COLS.map(k => cel(x[k])).join(";"))]
    .join("\r\n");
}

/* iCal : une ligne par propriété, repliée à 75 octets, texte échappé. Les
   heures sont « flottantes » (sans fuseau) : l'agenda les lit à l'heure locale,
   ce qu'on a saisi. */
function exportIcs(){
  const txt = s => String(s || "").replace(/\\/g, "\\\\").replace(/;/g, "\\;").replace(/,/g, "\\,")
                                   .replace(/\r?\n/g, "\\n");
  const plie = l => { const out = []; let s = l;
    while(new TextEncoder().encode(s).length > 74){
      let n = 74; while(new TextEncoder().encode(s.slice(0, n)).length > 74) n--;
      out.push(s.slice(0, n)); s = " " + s.slice(n);
    }
    out.push(s); return out.join("\r\n"); };
  const d8 = j => j.replace(/-/g, "");
  const now = new Date().toISOString().replace(/[-:]/g, "").replace(/\.\d+/, "");
  const L = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Patch//Logistique//FR", "CALSCALE:GREGORIAN",
             "X-WR-CALNAME:Patch logistique"];
  /* Un spectacle devient une série par heure de jeu : chaque jour à 20:30
     sauf relâche, puis le dimanche à 16:00, etc. Seule la première porte les
     champs de Patch ; les autres se reconnaissent à leur « ~ ». */
  const JOURS_ICS = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"];
  const specIcs = x => {
    const r = seancesLues(x.seances || x.hdebut), fin = finDe(x);
    const parHeure = {};
    for(let d = 1; d <= 7; d++){
      if(String(x.relache || "").includes(String(d))) continue;
      (r.jours[d] || r.defaut).forEach(h => (parHeure[h] = parHeure[h] || []).push(d));
    }
    const dur = heureLue(x.duree) || "02:00";
    let n = 0;
    Object.keys(parHeure).sort().forEach(h => {
      const jours = parHeure[h];
      let prem = x.debut;
      while(prem <= fin && !jours.includes(jourSem(prem))) prem = plusJours(prem, 1);
      if(prem > fin) return;
      L.push("BEGIN:VEVENT", "UID:" + x.id + (n ? "~" + h.replace(":", "") : "") + "@patch", "DTSTAMP:" + now,
             "SUMMARY:" + txt(x.titre),
             "DTSTART:" + d8(prem) + "T" + h.replace(":", "") + "00",
             "DTEND:" + d8(prem) + "T" + ajouterHeure(h, dur).replace(":", "") + "00");
      if(fin > prem) L.push("RRULE:FREQ=WEEKLY;BYDAY=" + jours.map(d => JOURS_ICS[d - 1]).join(",")
                            + ";UNTIL=" + d8(fin) + "T235959");
      if(x.lieu) L.push("LOCATION:" + txt(x.lieu));
      const desc = [logSous(x), "Heures de jeu : " + (x.seances || h), x.contact && "Contact : " + x.contact, x.note]
        .filter(Boolean).join("\n");
      L.push("DESCRIPTION:" + txt(desc), "CATEGORIES:Spectacle");
      if(!n) L.push("X-PATCH-TYPE:spec", "X-PATCH-TITRE:" + txt(x.titre), "X-PATCH-DEBUT:" + x.debut,
                    "X-PATCH-FIN:" + fin, "X-PATCH-SEANCES:" + txt(x.seances || h),
                    ...(x.relache ? ["X-PATCH-RELACHE:" + x.relache] : []), ...(x.duree ? ["X-PATCH-DUREE:" + txt(x.duree)] : []),
                    ...(x.tiers ? ["X-PATCH-TIERS:" + txt(x.tiers)] : []));
      L.push("END:VEVENT");
      n++;
    });
  };
  logVivants().filter(x => x.debut).forEach(x => {
    if(x.type === "spec") return specIcs(x);
    const fin = x.fin && x.fin >= x.debut ? x.fin : x.debut;
    const titre = x.type === "pret" ? "Prêt : " + logTitre(x) + " (" + (x.sens || "Prêté à").toLowerCase() + " " + (x.tiers || "?") + ")"
                : x.type === "stag" ? "Stagiaire : " + x.titre
                : x.titre;
    L.push("BEGIN:VEVENT", "UID:" + x.id + "@patch", "DTSTAMP:" + now, "SUMMARY:" + txt(titre));
    if(x.type === "ev" && x.hdebut){
      L.push("DTSTART:" + d8(x.debut) + "T" + x.hdebut.replace(":", "") + "00");
      const hf = x.hfin || (fin === x.debut ? String(Math.min(23, +x.hdebut.slice(0, 2) + 1)).padStart(2, "0") + x.hdebut.slice(2) : x.hdebut);
      L.push("DTEND:" + d8(fin) + "T" + hf.replace(":", "") + "00");
    } else {
      L.push("DTSTART;VALUE=DATE:" + d8(x.debut), "DTEND;VALUE=DATE:" + d8(plusJours(fin, 1)));
    }
    if(x.lieu) L.push("LOCATION:" + txt(x.lieu));
    const desc = [logSous(x), x.contact && "Contact : " + x.contact, x.note].filter(Boolean).join("\n");
    if(desc) L.push("DESCRIPTION:" + txt(desc));
    L.push("CATEGORIES:" + txt(LOG_TYPES[x.type].t), "X-PATCH-TYPE:" + x.type,
           "X-PATCH-TITRE:" + txt(x.titre), "END:VEVENT");
  });
  L.push("END:VCALENDAR");
  return L.map(plie).join("\r\n") + "\r\n";
}

/* ---------------------------------- import ------------------------------ */
/* Une date lue ailleurs arrive en 2026-10-12, 12/10/2026 ou 20261012. */
function dateLue(v){
  const s = String(v || "").trim();
  let m;
  if((m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})/))) return iso(new Date(+m[1], m[2] - 1, +m[3]));
  if((m = s.match(/^(\d{1,2})[\/.](\d{1,2})[\/.](\d{2,4})/)))
    return iso(new Date(m[3].length === 2 ? 2000 + +m[3] : +m[3], m[2] - 1, +m[1]));
  if((m = s.match(/^(\d{4})(\d{2})(\d{2})$/))) return iso(new Date(+m[1], m[2] - 1, +m[3]));
  return "";
}
const heureLue = v => { const m = String(v || "").match(/(\d{1,2})\s*[:hH]\s*(\d{2})?/);
  return m ? String(Math.min(23, +m[1])).padStart(2, "0") + ":" + (m[2] || "00") : ""; };
function typeLu(v){
  const s = plat(v);
  if(/^(pret|emprunt|materiel)/.test(s)) return "pret";
  if(/^stag/.test(s)) return "stag";
  return "ev";
}

/* Nettoie une fiche venue d'ailleurs : seules les clés connues passent. */
function ficheLue(o){
  const type = LOG_TYPES[o.type] ? o.type : typeLu(o.type);
  const x = { id:String(o.id || "").trim() || logId(), type, maj:Number(o.maj) || Date.now() };
  if(o.supprime){ x.supprime = true; return x; }
  LOG_COLS.forEach(k => {
    if(["id", "type", "maj"].includes(k) || o[k] == null || o[k] === "") return;
    x[k] = k === "debut" || k === "fin" ? dateLue(o[k])
         : k === "hdebut" || k === "hfin" ? heureLue(o[k])
         : k === "relache" ? String(o[k]).replace(/[^1-7]/g, "")
         : k === "fait" ? (o[k] === true || /^(oui|vrai|true|1|x|yes)$/i.test(String(o[k]).trim()))
         : String(o[k]);
  });
  if(!x.fait) delete x.fait;
  return x;
}

function fusionner(liste){
  const r = { nouveaux:0, maj:0, suppr:0, ignores:0 };
  const tout = logTout();
  liste.forEach(o => {
    if(!o || typeof o !== "object"){ r.ignores++; return; }
    const x = ficheLue(o);
    if(!x.supprime && !x.titre){ r.ignores++; return; }
    /* Sans identifiant commun, une même fiche se reconnaît à son type, son
       titre et sa date : réimporter le même CSV ne double rien. */
    let i = tout.findIndex(y => y.id === x.id);
    if(i < 0 && !x.supprime)
      i = tout.findIndex(y => !y.supprime && y.type === x.type && plat(y.titre) === plat(x.titre)
                              && (y.debut || "") === (x.debut || ""));
    if(i < 0){
      if(x.supprime) return;
      tout.push(x); r.nouveaux++; return;
    }
    const y = tout[i];
    if((y.maj || 0) > x.maj){ r.ignores++; return; }
    if(x.supprime){ if(!y.supprime){ tout[i] = x; r.suppr++; } return; }
    if(JSON.stringify({ ...y, maj:0, id:0 }) === JSON.stringify({ ...x, maj:0, id:0 })) return;
    tout[i] = { ...x, id:y.id }; r.maj++;
  });
  sauverLog();
  return r;
}

function lireCsv(t){
  t = t.replace(/^\ufeff/, "");
  const prem = t.split(/\r?\n/)[0] || "";
  const sep = (prem.match(/;/g) || []).length >= (prem.match(/,/g) || []).length ? ";"
            : (prem.match(/\t/g) || []).length > (prem.match(/,/g) || []).length ? "\t" : ",";
  const rangs = []; let rang = [], cel = "", guil = false;
  for(let i = 0; i < t.length; i++){
    const ch = t[i];
    if(guil){
      if(ch === '"'){ if(t[i + 1] === '"'){ cel += '"'; i++; } else guil = false; }
      else cel += ch;
    } else if(ch === '"') guil = true;
    else if(ch === sep){ rang.push(cel); cel = ""; }
    else if(ch === "\n" || ch === "\r"){
      if(ch === "\r" && t[i + 1] === "\n") i++;
      rang.push(cel); rangs.push(rang); rang = []; cel = "";
    } else cel += ch;
  }
  if(cel || rang.length){ rang.push(cel); rangs.push(rang); }
  const vrais = rangs.filter(r => r.some(c => c.trim()));
  if(vrais.length < 2) return { erreur:"le fichier CSV est vide" };
  /* Les en-têtes se reconnaissent par leur clé ou par l'étiquette de l'écran
     (« Matériel », « Retour prévu »…), avec ou sans accents. */
  const alias = { quoi:"titre", nom:"titre", materiel:"titre", intitule:"titre", evenement:"titre",
    date:"debut", sortie:"debut", arrivee:"debut", "date de debut":"debut", heure:"hdebut",
    "retour prevu":"fin", retour:"fin", depart:"fin", "date de fin":"fin", "heure de fin":"hfin",
    categorie:"cat", quantite:"qte", qui:"tiers", ecole:"formation", "ecole ou formation":"formation",
    rendu:"fait", pour:"spectacle", lieu:"lieu" };
  const tetes = vrais[0].map(h => { const p = plat(h).trim(); return LOG_COLS.includes(p) ? p : alias[p] || null; });
  if(!tetes.includes("titre")) return { erreur:"colonne « titre » (ou Quoi, Nom, Matériel) introuvable" };
  return { liste:vrais.slice(1).map(r => {
    const o = {};
    tetes.forEach((k, i) => { if(k && r[i] != null && r[i] !== "") o[k] = r[i]; });
    if(o.maj) o.maj = Number(o.maj) || 0;
    else o.maj = 1;           // une ligne de tableur ne l'emporte pas sur une fiche modifiée ici
    return o;
  }) };
}

function lireIcs(t){
  const lignes = t.replace(/\r?\n[ \t]/g, "").split(/\r?\n/);
  const liste = []; let o = null;
  const brut = s => s.replace(/\\n/gi, "\n").replace(/\\([,;\\])/g, "$1");
  const quand = (params, v) => {
    const m = v.match(/^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})\d{0,2}(Z?))?/);
    if(!m) return {};
    if(!m[4]) return { j:m[1] + "-" + m[2] + "-" + m[3], jour:true };
    const d = m[6] ? new Date(Date.UTC(+m[1], m[2] - 1, +m[3], +m[4], +m[5]))
                   : new Date(+m[1], m[2] - 1, +m[3], +m[4], +m[5]);
    return { j:iso(d), h:String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0") };
  };
  lignes.forEach(l => {
    if(l === "BEGIN:VEVENT"){ o = {}; return; }
    if(l === "END:VEVENT"){ if(o) liste.push(o); o = null; return; }
    if(!o) return;
    const i = l.indexOf(":");
    if(i < 0) return;
    const [nom, ...params] = l.slice(0, i).split(";");
    const v = l.slice(i + 1);
    const N = nom.toUpperCase();
    if(N === "SUMMARY") o.titre = brut(v);
    else if(N === "LOCATION") o.lieu = brut(v);
    else if(N === "DESCRIPTION") o.note = brut(v);
    else if(N === "UID") o.uid = v;
    else if(N === "X-PATCH-TYPE") o.type = v;
    else if(N === "X-PATCH-TITRE") o.brut = brut(v);
    else if(N === "X-PATCH-DEBUT") o.xdebut = v.trim();
    else if(N === "X-PATCH-FIN") o.xfin = v.trim();
    else if(N === "X-PATCH-SEANCES") o.seances = brut(v);
    else if(N === "X-PATCH-RELACHE") o.relache = v.trim();
    else if(N === "X-PATCH-DUREE") o.duree = brut(v);
    else if(N === "X-PATCH-TIERS") o.tiers = brut(v);
    else if(N === "LAST-MODIFIED") { const q = quand([], v); o.majIcs = q.j; }
    else if(N === "DTSTART"){ const q = quand(params, v); o.debut = q.j; if(q.h) o.hdebut = q.h; o.jour = q.jour; }
    else if(N === "DTEND"){ const q = quand(params, v); o.fin = q.jour ? plusJours(q.j, -1) : q.j; if(q.h) o.hfin = q.h; }
  });
  if(!liste.length) return { erreur:"aucun événement dans ce fichier .ics" };
  return { liste:liste.map(o => {
    /* Nos propres exports reviennent avec leur identifiant, leur type et le
       titre d'origine, sans le préfixe « Prêt : » mis pour l'agenda. */
    const nous = /@patch$/.test(o.uid || "");
    if(nous && o.uid.includes("~")) return null;   // les autres séries d'un spectacle
    const type = LOG_TYPES[o.type] ? o.type : "ev";
    if(nous){
      const loc = logTout().find(y => y.id === o.uid.replace(/@patch$/, ""));
      if(loc) return null;            // l'agenda ne sait rien de plus que nous
    }
    const x = { id:nous ? o.uid.replace(/@patch$/, "") : o.uid ? "ics-" + o.uid : "", type,
                titre:o.brut || o.titre || "", debut:o.debut, fin:o.fin !== o.debut ? o.fin : "",
                hdebut:o.hdebut, hfin:o.hfin, lieu:o.lieu, note:o.note, maj:1 };
    if(type === "spec"){
      Object.assign(x, { debut:o.xdebut || o.debut, fin:o.xfin && o.xfin !== (o.xdebut || o.debut) ? o.xfin : "",
                         seances:o.seances, relache:o.relache, duree:o.duree, tiers:o.tiers, hfin:"" });
      x.note = "";                    // la description répète les champs
    }
    return x;
  }).filter(Boolean) };
}

function importerLog(texte){
  const t = String(texte || "").trim();
  if(!t) return { erreur:"le fichier est vide" };
  let lu;
  if(/^BEGIN:VCALENDAR/i.test(t.replace(/^\ufeff/, ""))) lu = lireIcs(t);
  else if(/^[\[{]/.test(t)){
    try {
      const j = JSON.parse(t);
      const l = Array.isArray(j) ? j : j.elements || j.items || j.events || j.evenements;
      lu = Array.isArray(l) ? { liste:l } : { erreur:"ce JSON ne contient pas de liste de fiches" };
    } catch(e){ lu = { erreur:"JSON illisible" }; }
  } else lu = lireCsv(t);
  if(lu.erreur) return lu;
  return fusionner(lu.liste);
}

/* ---------------------------------- la fiche ---------------------------- */
function vLogFiche(){
  const r = S.route;
  const src = r.i ? logTout().find(x => x.id === r.i && !x.supprime) : null;
  const type = src ? src.type : r.k || "ev";
  const def = LOG_TYPES[type];
  const neuf = !src;
  const d = el("div", "lg lg-fiche");
  d.style.setProperty("--c", def.c);
  d.append(bar(neuf ? "Nouveau · " + def.t.toLowerCase() : def.t, null, () => retour({ v:"log" })));

  const listes = {
    spectacles: () => [...new Set([...(typeof PATCHS !== "undefined" ? PATCHS.map(p => p.nom) : []),
                                   ...logVivants().map(x => x.type === "spec" ? x.titre : x.spectacle)].filter(Boolean))],
    materiel: () => [...new Set([...PROJECTEURS.map(p => p.marque + " " + p.nom), ...MACHINERIE.map(m => m.nom),
                                 ...HAUTEURS.map(h => h.nom),
                                 ...(typeof DIVERS !== "undefined" ? DIVERS : []).map(x => x.nom)])]
  };
  const c = el("div", "card");
  const champ = f => {
    const id = "lg-" + f.c;
    const v = src ? src[f.c] : (f.c === "debut" ? r.p || logAuj() : f.sel ? f.sel[0] : "");
    if(f.coche) return `<label class="lg-coche"><input type="checkbox" id="${id}"${src && src.fait ? " checked" : ""}>
      <span>${esc(f.l)}</span></label>`;
    /* Peu de choix : des boutons côte à côte plutôt qu'un menu à ouvrir. */
    if(f.seg) return `<div class="f"><label>${esc(f.l)}</label><div class="lg-seg" id="${id}" data-v="${esc(v || f.sel[0])}">${
      (v && !f.sel.includes(v) ? [v, ...f.sel] : f.sel).map(o => `<button type="button" data-o="${esc(o)}" aria-pressed="${
        o === (v || f.sel[0])}">${esc(o)}</button>`).join("")}</div></div>`;
    if(f.sel) return `<div class="f"><label for="${id}">${esc(f.l)}</label><select id="${id}">${
      (v && !f.sel.includes(v) ? [v, ...f.sel] : f.sel).map(o => `<option${o === v ? " selected" : ""}>${esc(o)}</option>`).join("")}</select></div>`;
    /* Les jours de la semaine, un bouton chacun ; la valeur est la suite des
       jours cochés, 1 pour lundi. */
    if(f.jours) return `<div class="f"><label>${esc(f.l)}</label><div class="lg-jours" id="${id}" data-v="${esc(v || "")}">${
      JOURS_ABR.map((n, i) => `<button type="button" data-d="${i + 1}" aria-pressed="${String(v || "").includes(String(i + 1))}">${
        n.replace(/^./, x => x.toUpperCase())}</button>`).join("")}</div></div>`;
    if(f.zone) return `<div class="f"><label for="${id}">${esc(f.l)}</label>
      <textarea id="${id}" rows="3" placeholder="${esc(f.ph || "")}">${esc(v || "")}</textarea></div>`;
    return `<div class="f"><label for="${id}">${esc(f.l)}${f.req ? " *" : ""}</label>
      <input id="${id}" type="${f.type || "text"}" value="${esc(v || "")}" placeholder="${esc(f.ph || "")}"
        autocomplete="off"${f.mode ? ` inputmode="${f.mode}"` : ""}${f.liste ? ` list="dl-${id}"` : ""}>${
      f.liste ? `<datalist id="dl-${id}">${listes[f.liste]().slice(0, 300).map(o =>
        `<option value="${esc(o)}"></option>`).join("")}</datalist>` : ""}${
      f.aide ? `<p class="lg-aide">${esc(f.aide)}</p>` : ""}${
      f.materiel ? `<div class="lg-reco" id="reco-${id}"></div>` : ""}</div>`;
  };
  /* La fiche tient sur l'écran : l'essentiel en clair, le reste (contact,
     note…) replié sous une ligne, déplié d'office s'il est déjà rempli. La
     case « fait » n'a de sens qu'une fois la fiche créée. */
  const rendu = f => f.rang ? `<div class="ff">${f.rang.map(champ).join("")}</div>` : champ(f);
  const vus = LOG_CHAMPS[type].filter(f => !(f.coche && neuf));
  vus.filter(f => !f.plus).forEach(f => c.insertAdjacentHTML("beforeend", rendu(f)));
  const plus = vus.filter(f => f.plus);
  if(plus.length){
    const rempli = src && plus.some(f => src[f.c]);
    c.insertAdjacentHTML("beforeend", `<details class="lg-plus"${rempli ? " open" : ""}><summary>${
      esc("Plus : " + plus.map(f => f.l.toLowerCase()).join(", "))}</summary>${plus.map(rendu).join("")}</details>`);
  }
  c.querySelectorAll(".lg-seg button").forEach(b => b.onclick = () => {
    toucher();
    const g = b.parentNode;
    g.querySelectorAll("button").forEach(n => n.setAttribute("aria-pressed", String(n === b)));
    g.dataset.v = b.dataset.o;
  });
  /* Ce que le parc reconnaît dans le matériel écrit, à mesure qu'on tape. */
  const mat = c.querySelector("#lg-titre");
  const reco = c.querySelector("#reco-lg-titre");
  if(mat && reco){
    const montrer = () => {
      const l = materielLu(mat.value);
      reco.innerHTML = l.map(o => `<span class="${o.nom ? "ok" : ""}"><b>${o.q} ×</b> ${esc(o.nom || o.texte)}${
        o.nom ? (o.nb != null ? `<i>${o.q > o.nb ? "plus que les " : ""}${o.nb} au parc</i>` : "")
              : "<i>pas au parc</i>"}</span>`).join("");
      reco.hidden = !l.length;
    };
    mat.addEventListener("input", montrer);
    montrer();
  }
  c.querySelectorAll(".lg-jours button").forEach(b => b.onclick = () => {
    toucher();
    b.setAttribute("aria-pressed", String(b.getAttribute("aria-pressed") !== "true"));
    const g = b.parentNode;
    g.dataset.v = [...g.querySelectorAll('button[aria-pressed="true"]')].map(n => n.dataset.d).join("");
  });
  const err = el("p", "err");
  err.hidden = true;
  c.append(err);
  d.append(c);

  const fin = el("div", "fin");
  const ann = el("button", "ann", "Annuler");
  ann.onclick = () => retour({ v:"log" });
  const val = el("button", "val", neuf ? "Ajouter" : "Enregistrer");
  val.onclick = () => {
    const o = { id:src ? src.id : logId(), type, maj:Date.now() }, manque = [];
    champsPlats(type).forEach(f => {
      const n = c.querySelector("#lg-" + f.c);
      if(!n) return;
      if(f.coche){ if(n.checked) o.fait = true; return; }
      const v = (f.jours || f.seg ? n.dataset.v : n.value).trim();
      if(v) o[f.c] = v; else if(f.req) manque.push(f.l);
    });
    /* L'heure de la première séance sert au tri et à l'accueil. */
    if(type === "spec" && o.seances){
      const r = seancesLues(o.seances);
      o.hdebut = r.defaut[0] || Object.values(r.jours).flat().sort()[0] || "";
      if(!o.hdebut){ delete o.hdebut; manque.push("Heures de jeu, comme 20:30"); }
    }
    if(o.fin && o.debut && o.fin < o.debut) manque.push(def.fin + " après " + def.debut.toLowerCase());
    if(manque.length){
      err.textContent = "À remplir : " + manque.join(", ") + ".";
      err.hidden = false;
      return;
    }
    const tout = logTout();
    const i = tout.findIndex(x => x.id === o.id);
    if(i >= 0 && tout[i].tel){ o.tel = tout[i].tel; o.telCal = tout[i].telCal; }
    pousserTel(o);                               // l'agenda du téléphone, si un genre y est relié
    if(i < 0) tout.push(o); else tout[i] = o;
    sauverLog();
    toucher();
    retour({ v:"log" });
  };
  fin.append(ann, val);
  d.append(fin);

  if(!neuf){
    /* L'agenda du téléphone, prérempli : un rendez-vous à la fois, sans
       permission à demander. */
    if(NET.natif && src.debut){
      const ag = el("button", "lg-agenda", ic("agenda") + "<span>Ajouter à l'agenda du téléphone</span>");
      ag.onclick = () => {
        const r = momentTel(src);
        if(!r || !NET.agenda(r)) ag.querySelector("span").textContent = "Aucune application d'agenda trouvée";
      };
      d.append(ag);
    }
    const sup = el("button", "sup-tot", def.sup);
    sup.onclick = () => {
      if(sup.dataset.sur !== "1"){
        sup.dataset.sur = "1";
        sup.textContent = "Toucher encore pour retirer";
        return;
      }
      const tout = logTout();
      const i = tout.findIndex(x => x.id === src.id);
      retirerTel(i >= 0 ? tout[i] : src);
      if(i >= 0) tout[i] = { id:src.id, type, supprime:true, maj:Date.now() };
      sauverLog();
      retour({ v:"log" });
    };
    d.append(sup);
  }
  return d;
}
