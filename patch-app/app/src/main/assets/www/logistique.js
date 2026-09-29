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
    { c:"cat", l:"Catégorie", sel:LOG_CATS },
    { rang:[{ c:"debut", l:"Date", type:"date", req:true }, { c:"hdebut", l:"Heure", type:"time" }] },
    { rang:[{ c:"fin", l:"Jusqu'au", type:"date" }, { c:"hfin", l:"Heure de fin", type:"time" }] },
    { c:"lieu", l:"Lieu", ph:"Quai de déchargement" },
    { c:"spectacle", l:"Spectacle", ph:"Nom du spectacle", liste:"spectacles" },
    { c:"contact", l:"Contact", ph:"Nom, téléphone" },
    { c:"note", l:"Note", zone:true, ph:"Ce qu'il faut savoir" },
    { c:"fait", l:"Fait", coche:true }
  ],
  pret: [
    { c:"titre", l:"Matériel", ph:"Découpe 614SX", req:true, liste:"materiel" },
    { rang:[{ c:"qte", l:"Quantité", ph:"1", mode:"numeric" },
            { c:"sens", l:"Sens", sel:["Prêté à", "Emprunté à"] }] },
    { c:"tiers", l:"Qui", ph:"Théâtre, compagnie, personne", req:true },
    { rang:[{ c:"debut", l:"Sortie", type:"date", req:true }, { c:"fin", l:"Retour prévu", type:"date" }] },
    { c:"contact", l:"Contact", ph:"Nom, téléphone" },
    { c:"spectacle", l:"Pour", ph:"Spectacle, événement", liste:"spectacles" },
    { c:"note", l:"Note", zone:true, ph:"État au départ, numéros de série…" },
    { c:"fait", l:"Rendu", coche:true }
  ],
  stag: [
    { c:"titre", l:"Nom", ph:"Prénom Nom", req:true },
    { c:"formation", l:"École ou formation", ph:"BTS, CFPTS, licence pro…" },
    { rang:[{ c:"service", l:"Service", ph:"Lumière" }, { c:"tuteur", l:"Tuteur", ph:"Qui l'encadre" }] },
    { rang:[{ c:"debut", l:"Arrivée", type:"date", req:true }, { c:"fin", l:"Départ", type:"date" }] },
    { c:"contact", l:"Contact", ph:"Téléphone, mail" },
    { c:"note", l:"Note", zone:true, ph:"Horaires, convention, objectifs…" }
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
    .map(({ x, e }) => ({ fiche:x, groupe:e.g, titre:logTitre(x), sous:logSous(x), quand:e.r, detail:e.r2 }));
}

function logSous(x){
  if(x.type === "pret")
    return [(x.sens || "Prêté à") + " " + (x.tiers || "?"), x.spectacle].filter(Boolean).join(" · ");
  if(x.type === "stag")
    return [x.formation, x.service, x.tuteur && "avec " + x.tuteur].filter(Boolean).join(" · ") || "Stagiaire";
  return [x.cat, x.lieu, x.spectacle].filter(Boolean).join(" · ") || "Événement";
}
const logTitre = x => x.type === "pret" && x.qte && x.qte !== "1" ? x.qte + " × " + x.titre : x.titre;

/* ------------------------------- la liste ------------------------------- */
const LOGV = { type:null, fini:false, msg:"" };

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

function vLog(){
  const d = el("div", "lg");
  const j = logAuj();
  const tous = logVivants();
  d.append(bar("Logistique", tous.length ? tous.length + " fiche" + (tous.length > 1 ? "s" : "") : ""));

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
  Object.entries(LOG_TYPES).forEach(([k, t]) => {
    const b = el("button", "ajout", ic("plus") + "<span>" + esc(t.t) + "</span>");
    b.style.setProperty("--c", t.c);
    b.onclick = () => { toucher(); go({ v:"logf", i:null, k }); };
    aj.append(b);
  });
  d.append(aj);

  d.append(recherche("log", "Nom, matériel, lieu, école…"));
  const p = el("div", "pills");
  [[null, "Tout", tous.length], ...Object.entries(LOG_TYPES).map(([k, t]) =>
      [k, t.pl, tous.filter(x => x.type === k).length])].forEach(([k, lab, n]) => {
    const b = el("button", "pill", esc(lab) + (n ? ` <i>${n}</i>` : ""));
    b.setAttribute("aria-pressed", String(LOGV.type === k));
    b.onclick = () => { toucher(); LOGV.type = k; render(); };
    p.append(b);
  });
  d.append(p);

  const q = plat(S.qs.log || "");
  const vus = etats.filter(({ x }) => (!LOGV.type || x.type === LOGV.type)
    && (!q || plat([x.titre, x.lieu, x.tiers, x.tuteur, x.formation, x.service, x.spectacle,
                    x.cat, x.contact, x.note].join(" ")).includes(q)));

  if(!tous.length){
    d.append(el("p", "vide", "Rien de noté pour l'instant. Ajoutez un événement, un prêt ou un stagiaire, "
      + "ou importez un fichier plus bas."));
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

  d.append(carteEchange());
  return d;
}

/* --------------------------- échange de fichiers ------------------------ */
function carteEchange(){
  const c = el("div", "card lg-ech");
  c.innerHTML = `<h4>Synchroniser</h4>
    <p class="muted">Pour Today, SceneFlow ou un autre téléphone : exportez le fichier, importez celui
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
                  "qte", "sens", "tiers", "formation", "service", "tuteur", "contact", "note", "fait", "maj"];

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
  logVivants().filter(x => x.debut).forEach(x => {
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
    else if(N === "LAST-MODIFIED") { const q = quand([], v); o.majIcs = q.j; }
    else if(N === "DTSTART"){ const q = quand(params, v); o.debut = q.j; if(q.h) o.hdebut = q.h; o.jour = q.jour; }
    else if(N === "DTEND"){ const q = quand(params, v); o.fin = q.jour ? plusJours(q.j, -1) : q.j; if(q.h) o.hfin = q.h; }
  });
  if(!liste.length) return { erreur:"aucun événement dans ce fichier .ics" };
  return { liste:liste.map(o => {
    /* Nos propres exports reviennent avec leur identifiant, leur type et le
       titre d'origine, sans le préfixe « Prêt : » mis pour l'agenda. */
    const nous = /@patch$/.test(o.uid || "");
    const type = LOG_TYPES[o.type] ? o.type : "ev";
    if(nous){
      const loc = logTout().find(y => y.id === o.uid.replace(/@patch$/, ""));
      if(loc) return null;            // l'agenda ne sait rien de plus que nous
    }
    const x = { id:nous ? o.uid.replace(/@patch$/, "") : o.uid ? "ics-" + o.uid : "", type,
                titre:o.brut || o.titre || "", debut:o.debut, fin:o.fin !== o.debut ? o.fin : "",
                hdebut:o.hdebut, hfin:o.hfin, lieu:o.lieu, note:o.note, maj:1 };
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
                                   ...logVivants().map(x => x.spectacle)].filter(Boolean))],
    materiel: () => [...new Set([...PROJECTEURS.map(p => p.marque + " " + p.nom), ...MACHINERIE.map(m => m.nom),
                                 ...HAUTEURS.map(h => h.nom)])]
  };
  const c = el("div", "card");
  const champ = f => {
    const id = "lg-" + f.c;
    const v = src ? src[f.c] : (f.c === "debut" ? logAuj() : f.sel ? f.sel[0] : "");
    if(f.coche) return `<label class="lg-coche"><input type="checkbox" id="${id}"${src && src.fait ? " checked" : ""}>
      <span>${esc(f.l)}</span></label>`;
    if(f.sel) return `<div class="f"><label for="${id}">${esc(f.l)}</label><select id="${id}">${
      f.sel.map(o => `<option${o === v ? " selected" : ""}>${esc(o)}</option>`).join("")}</select></div>`;
    if(f.zone) return `<div class="f"><label for="${id}">${esc(f.l)}</label>
      <textarea id="${id}" rows="3" placeholder="${esc(f.ph || "")}">${esc(v || "")}</textarea></div>`;
    return `<div class="f"><label for="${id}">${esc(f.l)}${f.req ? " *" : ""}</label>
      <input id="${id}" type="${f.type || "text"}" value="${esc(v || "")}" placeholder="${esc(f.ph || "")}"
        autocomplete="off"${f.mode ? ` inputmode="${f.mode}"` : ""}${f.liste ? ` list="dl-${id}"` : ""}>${
      f.liste ? `<datalist id="dl-${id}">${listes[f.liste]().slice(0, 300).map(o =>
        `<option value="${esc(o)}"></option>`).join("")}</datalist>` : ""}</div>`;
  };
  LOG_CHAMPS[type].forEach(f => c.insertAdjacentHTML("beforeend",
    f.rang ? `<div class="ff">${f.rang.map(champ).join("")}</div>` : champ(f)));
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
      if(f.coche){ if(n.checked) o.fait = true; return; }
      const v = n.value.trim();
      if(v) o[f.c] = v; else if(f.req) manque.push(f.l);
    });
    if(o.fin && o.debut && o.fin < o.debut) manque.push(def.fin + " après " + def.debut.toLowerCase());
    if(manque.length){
      err.textContent = "À remplir : " + manque.join(", ") + ".";
      err.hidden = false;
      return;
    }
    const tout = logTout();
    const i = tout.findIndex(x => x.id === o.id);
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
        const [a, m, j] = src.debut.split("-").map(Number);
        const fj = (src.fin || src.debut).split("-").map(Number);
        const journee = !(type === "ev" && src.hdebut);
        const h = (s, def) => (s || def).split(":").map(Number);
        const [h1, m1] = h(src.hdebut, "00:00"), [h2, m2] = h(src.hfin, src.hdebut ? String(Math.min(23, h1 + 1)) + ":" + String(m1) : "00:00");
        const debut = new Date(a, m - 1, j, journee ? 0 : h1, journee ? 0 : m1).getTime();
        const finT = journee ? new Date(fj[0], fj[1] - 1, fj[2] + 1).getTime()
                             : new Date(fj[0], fj[1] - 1, fj[2], h2, m2).getTime();
        const titre = type === "pret" ? "Prêt : " + logTitre(src) : type === "stag" ? "Stagiaire : " + src.titre : src.titre;
        if(!NET.agenda({ titre, lieu:src.lieu, note:[logSous(src), src.contact, src.note].filter(Boolean).join("\n"),
                         debut, fin:finT, journee })){
          ag.querySelector("span").textContent = "Aucune application d'agenda trouvée";
        }
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
      if(i >= 0) tout[i] = { id:src.id, type, supprime:true, maj:Date.now() };
      sauverLog();
      retour({ v:"log" });
    };
    d.append(sup);
  }
  return d;
}
