/* ---------------------------------------------------------------------------
   Les pièces retenues des premières propositions, rendues réutilisables :

   - `editeurLignes()`  — le tableau d'ajout d'appareils du canevas D ;
   - `rangee()`         — la rangée de liste du canevas D, la même pour un
                          appareil et pour un gradateur ;
   - `relever()`        — le relevé plein écran du canevas B, une question à la
                          fois, qui sait aussi allonger la feuille ;
   - `barreTele()`      — la télécommande réduite à une barre.

   Les canevas E, F et G n'ajoutent que l'assemblage : c'est la seule chose
   qu'ils proposent de départager.
--------------------------------------------------------------------------- */

/* --------------------------------- état ---------------------------------- */
function etatDemo(){
  return {
    nom:"Création — série 2026",
    lignes:JSON.parse(JSON.stringify(DEMO_LIGNES)),
    grada:JSON.parse(JSON.stringify(DEMO_CIRCUITS)),   // gradateur -> {circuit, note}
    affect:{ 1:"101", 2:"101", 3:"102", 5:"103" },     // appareil -> circuit
    reperes:{ 101:"face jardin", 103:"contre 1" },     // circuit -> repère
    reg:{ ...REGLAGES_DEFAUT },
    niv:{}, sel:0
  };
}

const courtNom = cle => trouverApp(cle).nom;
const gradaFiche = (E, n) => E.grada[n] || (E.grada[n] = { circuit:"", note:"" });
const circuitGrada = (E, n) => String((E.grada[n] || {}).circuit || "").trim();
const circuitApp = (E, n) => String(E.affect[n] || "").trim();
const listeGradas = E => {
  const out = [];
  for(let n = E.reg.premier; n < E.reg.premier + E.reg.nb; n++) out.push(n);
  return out;
};
const gradasRestants = E => listeGradas(E).filter(n => !circuitGrada(E, n)).length;
const appsRestants = E => adresser(E.lignes).filter(x => !circuitApp(E, x.n)).length;

/* Ce que porte un circuit : ses gradateurs d'un côté, ses appareils de l'autre.
   Le lien entre les deux, c'est le numéro de circuit et rien d'autre. */
function parCircuit(E){
  const m = new Map();
  const c = k => m.get(k) || m.set(k, { circuit:k, gradas:[], apps:[] }).get(k);
  listeGradas(E).forEach(n => { const x = circuitGrada(E, n); if(x) c(x).gradas.push(n); });
  adresser(E.lignes).forEach(a => { const x = circuitApp(E, a.n); if(x) c(x).apps.push(a); });
  return [...m.values()].sort((a, b) =>
    (parseInt(a.circuit) || 0) - (parseInt(b.circuit) || 0) || a.circuit.localeCompare(b.circuit));
}

/* -------------------------------- émission ------------------------------- */
function poserFlux(E){ FLUX.poser(E.niv, E.reg); }
function allumerSeuls(E, ns, pct = 100){
  if(E.reg.solo) E.niv = {};
  ns.forEach(n => E.niv[n] = pct);
  E.sel = ns[0] || 0;
  poserFlux(E); vibrer(12);
}
function toutEteindre(E){ E.niv = {}; E.sel = 0; FLUX.arret(); }

/* -------------------------- rangée de liste (D) -------------------------- */
/* Un appareil et un gradateur se lisent pareil : badge, désignation, adresse
   DMX à droite, une action. C'est ce qui rend les deux listes comparables. */
function rangee({ badge, appareil, titre, sous, dmx, dmxHaut, action, actionEtat, onAction,
                  onRangee, allume }){
  const d = el("div", "rg" + (allume ? " on" : "") + (onRangee ? " cliquable" : ""));
  d.innerHTML = `<span class="bd${appareil ? " ap" : ""}">${esc(badge)}</span>
    <span class="tx"><b>${esc(titre)}</b><span>${esc(sous || "—")}</span></span>
    <span class="dm">${esc(dmxHaut || "")}<b>${esc(dmx || "")}</b></span>`;
  if(action){
    const b = el("button", "ac" + (actionEtat === "on" ? " on" : actionEtat === "vide" ? " vide" : ""),
                 esc(action));
    b.onclick = e => { e.stopPropagation(); onAction(); };
    d.append(b);
  }
  /* Le corps de la rangée peut porter sa propre action — allumer, par exemple —
     sans voler celle de la pastille. */
  if(onRangee) d.onclick = onRangee;
  return d;
}

function pile(titre, compte, elements, rien, bouton){
  const d = el("div", "pile");
  d.insertAdjacentHTML("beforeend",
    `<h3>${esc(titre)}<span>${esc(compte || "")}</span></h3>`);
  if(bouton) d.querySelector("h3").append(bouton);
  if(!elements.length) d.insertAdjacentHTML("beforeend", `<p class="rien">${esc(rien || "Rien ici.")}</p>`);
  elements.forEach(e => d.append(e));
  return d;
}

/* ----------------------- éditeur de lignes (D) --------------------------- */
/* Une ligne par type d'appareil : l'appareil, son mode, la quantité, et
   l'étendue calculée en regard. Le départ se bascule en touchant l'étendue. */
function editeurLignes(E, maj){
  const liste = adresser(E.lignes);
  const ko = collisions(liste);
  const d = el("div", "pf");

  E.lignes.forEach(l => {
    const app = trouverApp(l.app);
    const mes = liste.filter(x => x.ligne === l.id);
    const enKo = mes.some(x => ko.has(x.n));
    const r = el("div", "lg");
    r.innerHTML = `<select class="sa">${MARQUES.map(m =>
        `<optgroup label="${esc(m)}">${APPAREILS.filter(a => a.marque === m).map(a =>
          `<option value="${esc(cleApp(a))}"${cleApp(a) === l.app ? " selected" : ""}>${esc(a.nom)}</option>`
        ).join("")}</optgroup>`).join("")}</select>
      <select class="sm mode">${app.modes.map(m =>
        `<option value="${esc(m.nom)}"${m.nom === l.mode ? " selected" : ""}>${esc(m.nom)}</option>`
      ).join("")}</select>
      <input class="qn" inputmode="numeric" value="${l.nb}" aria-label="Quantité">
      <button class="et${enKo ? " ko" : ""}" title="Basculer le départ">${
        l.suite ? "à la suite" : "départ imposé"}
        <b>${mes.length ? `U${mes[0].u}·${mes[0].adr}→${mes[mes.length - 1].fin}` : "—"}</b></button>
      <button class="x" aria-label="Retirer la ligne">✕</button>`;
    r.querySelector(".sa").onchange = e => { l.app = e.target.value;
      const a = trouverApp(l.app); l.mode = a.modes[0].nom; l.ch = a.modes[0].ch; maj(); };
    r.querySelector(".sm").onchange = e => { l.mode = e.target.value;
      const m = app.modes.find(x => x.nom === l.mode); if(m) l.ch = m.ch; maj(); };
    r.querySelector(".qn").onchange = e => {
      l.nb = Math.min(500, +e.target.value.replace(/\D/g, "") || 0); maj(); };
    r.querySelector(".et").onclick = () => {
      if(l.suite){ const p = liste.find(x => x.ligne === l.id);
                   if(p){ l.u = p.u; l.adr = p.adr; } l.suite = false; }
      else l.suite = true;
      maj();
    };
    r.querySelector(".x").onclick = () => {
      E.lignes = E.lignes.filter(x => x.id !== l.id); maj(); };
    d.append(r);
  });

  const p = el("div", "pied");
  const add = el("button", null, "+ Ligne d'appareils");
  add.onclick = () => {
    const a = APPAREILS[0];
    E.lignes.push({ id:"l" + Date.now().toString(36), app:cleApp(a), mode:a.modes[0].nom,
                    ch:a.modes[0].ch, nb:4, suite:E.lignes.length > 0, u:1, adr:1 });
    maj();
  };
  p.append(add);
  p.insertAdjacentHTML("beforeend",
    `<span class="tot"><b>${liste.length}</b> appareils · <b>${
      [...new Set(liste.map(x => x.u))].length}</b> univers · ${puissance(liste)}<br>${
      ko.size ? `<b class="ko">${ko.size} en chevauchement</b>` : "aucun chevauchement"}</span>`);
  d.append(p);
  return d;
}

/* --------------------- télécommande en barre (D) ------------------------- */
/* opts = { compact, reduit, onReduire } — `compact` resserre les touches,
   `reduit` la replie sur une seule ligne (l'essentiel : le gradateur appelé,
   son niveau, et de quoi éteindre), `onReduire` ajoute le chevron qui bascule
   entre les deux. Une télécommande repliée rend trois rangées à la liste. */
function barreTele(E, maj, opts = {}){
  const d = el("div", "tl" + (opts.compact ? " compact" : "") + (opts.reduit ? " mini" : ""));
  const on = Object.keys(E.niv).filter(k => E.niv[k] > 0);
  const lv = E.sel ? (E.niv[E.sel] || 0) : 0;
  const a = E.sel ? adrGrada(E.sel, E.reg) : null;
  const cir = E.sel ? circuitGrada(E, E.sel) : "";
  const etat = a ? `U${a.u}·${a.canal}${cir ? " · circ. " + esc(cir) : ""}`
                 : on.length ? `${on.length} allumé${on.length > 1 ? "s" : ""}` : "plateau noir";
  const chevron = opts.onReduire
    ? `<button class="chev" data-a="plier" aria-expanded="${!opts.reduit}"
               aria-label="${opts.reduit ? "Déplier la télécommande" : "Réduire la télécommande"}"
       >${opts.reduit ? "⌃" : "⌄"}</button>` : "";

  d.innerHTML = opts.reduit
    ? `<span class="led${on.length ? " on" : ""}"></span>
       <span class="ap"><b>${E.sel || "—"}</b><span>${etat}</span></span>
       <span class="niv">${lv} %</span>
       <button data-a="noir">Noir</button>${chevron}`
    : `<span class="led${on.length ? " on" : ""}"></span>
       <span class="ap"><b>${E.sel || "—"}</b><span>${etat}</span></span>
       <input type="range" min="0" max="100" value="${lv}" aria-label="Niveau"${E.sel ? "" : " disabled"}>
       <span class="niv">${lv} %</span>
       <button data-a="noir">Noir</button><button data-a="full">Full</button>
       <button data-a="prec" aria-label="Gradateur précédent">◀</button>
       <button data-a="suiv" aria-label="Gradateur suivant">▶</button>
       <button data-a="zero">Zéro</button>${chevron}`;
  const niveau = pct => {
    if(!E.sel) return;
    const v = Math.max(0, Math.min(100, Math.round(pct)));
    Object.keys(E.niv).forEach(k => { if(E.niv[k] > 0 || +k === E.sel) E.niv[k] = v; });
    poserFlux(E); maj();
  };
  const gliss = d.querySelector("input");
  if(gliss) gliss.oninput = e => niveau(+e.target.value);
  const actes = {
    noir:() => niveau(0), full:() => niveau(100),
    plier:() => opts.onReduire(!opts.reduit),
    prec:() => { allumerSeuls(E, [Math.max(E.reg.premier, (E.sel || E.reg.premier + 1) - 1)]); maj(); },
    suiv:() => { allumerSeuls(E, [Math.min(E.reg.premier + E.reg.nb - 1, (E.sel || E.reg.premier - 1) + 1)]); maj(); },
    zero:() => { toutEteindre(E); maj(); }
  };
  d.querySelectorAll("button").forEach(b => {
    b.onclick = actes[b.dataset.a];
    if(b.dataset.a === "noir" && E.sel && lv === 0) b.classList.add("on");
    if(b.dataset.a === "full" && lv === 100) b.classList.add("on");
  });
  return d;
}

/* ---------------------- relevé plein écran (B) --------------------------- */
/* Une question à la fois : l'élément s'allume, on regarde la scène, on écrit,
   et valider passe au suivant *non relevé* en l'allumant. Le même écran sert
   aux gradateurs et aux appareils — seule la source des éléments change.

   opts = {
     titre, couleur, question, marque,   // habillage
     items(),                            // () -> [ { id, geant, sous, get, set,
                                         //          note:{get,set}|null, allume|null } ]
     ajouter:{ texte, faire }|null,      // allonger la feuille depuis le relevé
     onChange, onFin
   } */
function relever(opts){
  let i = 0, auto = false, minuterie = null, niveau = 100;
  const hote = el("div", "fs");
  if(opts.couleur) hote.style.setProperty("--c", opts.couleur);
  hote.innerHTML = `<div class="prog"><div class="b"><i></i></div>
      <div class="t"><span class="g"></span><span class="d"></span></div></div>
    <div class="scene"></div><div class="pied"></div>`;
  const scene = hote.querySelector(".scene"), pied = hote.querySelector(".pied");

  const items = () => opts.items();
  const vide = it => !String(it.get() || "").trim();
  const suivantVide = depuis => {
    const l = items();
    for(let k = depuis; k < l.length; k++) if(vide(l[k])) return k;
    return null;
  };

  function allerA(k, rendreApres = true){
    const l = items();
    if(k == null || k < 0 || k >= l.length) return;
    i = k;
    const it = l[i];
    if(it.allume) it.allume(niveau);
    if(rendreApres) rendre();
    relancer();
  }
  function relancer(){
    clearTimeout(minuterie);
    if(auto) minuterie = setTimeout(() => allerA(Math.min(items().length - 1, i + 1)), 6000);
  }
  function valider(){
    const k = suivantVide(i + 1);
    if(k != null){ allerA(k); return; }
    if(i + 1 < items().length){ allerA(i + 1); return; }
    fermer();                       /* dernier élément relevé : le travail est fini */
  }
  function fermer(){
    clearTimeout(minuterie);
    hote.remove();
    if(opts.onFin) opts.onFin();
  }

  function rendre(){
    const l = items();
    const it = l[i];
    const faits = l.filter(x => !vide(x)).length;
    hote.querySelector(".prog .b i").style.width = Math.round(faits / Math.max(1, l.length) * 100) + "%";
    hote.querySelector(".prog .g").textContent = opts.titre;
    hote.querySelector(".prog .d").textContent = `${faits} / ${l.length}`;

    scene.replaceChildren();
    if(!it){
      scene.innerHTML = `<div class="kick">${esc(opts.titre)}</div>
        <div class="sous">Rien à relever pour l'instant.</div>`;
    } else {
      scene.innerHTML = `<div class="kick">${esc(it.kick || opts.question)}</div>
        <div class="geant">${esc(it.geant)}</div>
        <div class="sous">${it.sous}</div>`;
      const ch = el("input", "champ");
      Object.assign(ch, { inputMode:"numeric", placeholder:opts.marque || "n° de circuit",
                          value:it.get() || "" });
      ch.setAttribute("aria-label", opts.marque || "Circuit");
      ch.oninput = () => { it.set(ch.value); if(opts.onChange) opts.onChange(); majProg(); };
      ch.onkeydown = e => { if(e.key === "Enter") valider(); };
      scene.append(ch);

      if(it.note){
        const no = el("input", "note");
        Object.assign(no, { placeholder:"repère — face jardin, contre 2…", value:it.note.get() || "" });
        no.setAttribute("aria-label", "Repère");
        no.oninput = () => { it.note.set(no.value); if(opts.onChange) opts.onChange(); };
        scene.append(no);
      }
      if(it.allume){
        const j = el("div", "jauge");
        j.innerHTML = `<input type="range" min="0" max="100" value="${niveau}" aria-label="Niveau"><b>${niveau} %</b>`;
        j.querySelector("input").oninput = e => {
          niveau = +e.target.value; it.allume(niveau);
          j.querySelector("b").textContent = niveau + " %";
          if(opts.onChange) opts.onChange();
        };
        scene.append(j);
      }
      scene.append(el("p", "gliss", "Glisser l'écran à gauche ou à droite pour changer d'élément."));
      setTimeout(() => ch.focus({ preventScroll:true }));
    }

    pied.replaceChildren();
    const ok = el("button", "gros", "Noté — suivant ▶");
    ok.onclick = valider;
    pied.append(ok);

    const r1 = el("div", "rang trois");
    const prec = el("button", "mini", "◀ précédent");
    const saut = el("button", "mini", "Sauter");
    const bAuto = el("button", "mini" + (auto ? " on" : ""), auto ? "Auto 6 s" : "Balayage auto");
    prec.onclick = () => allerA(i - 1);
    saut.onclick = () => allerA(suivantVide(i + 1) ?? Math.min(items().length - 1, i + 1));
    bAuto.onclick = () => { auto = !auto; rendre(); relancer(); };
    r1.append(prec, saut, bAuto);
    pied.append(r1);

    const r2 = el("div", "rang");
    if(opts.ajouter){
      const plus = el("button", "mini", opts.ajouter.texte);
      plus.onclick = () => {
        const k = opts.ajouter.faire();
        if(opts.onChange) opts.onChange();
        allerA(k != null ? k : items().length - 1);
      };
      r2.append(plus);
    }
    const fin = el("button", "mini", "Terminer");
    fin.onclick = fermer;
    r2.append(fin);
    pied.append(r2);
  }

  function majProg(){
    const l = items();
    const faits = l.filter(x => !vide(x)).length;
    hote.querySelector(".prog .b i").style.width = Math.round(faits / Math.max(1, l.length) * 100) + "%";
    hote.querySelector(".prog .d").textContent = `${faits} / ${l.length}`;
  }

  /* Glissement horizontal : changer d'élément sans viser un bouton. */
  let x0 = null;
  scene.addEventListener("touchstart", e => { x0 = e.touches[0].clientX; }, { passive:true });
  scene.addEventListener("touchend", e => {
    if(x0 == null) return;
    const dx = e.changedTouches[0].clientX - x0; x0 = null;
    if(Math.abs(dx) > 60) allerA(i + (dx < 0 ? 1 : -1));
  }, { passive:true });

  document.body.append(hote);
  const depart = opts.depart != null ? opts.depart : (suivantVide(0) ?? 0);
  allerA(depart);
  return { fermer };
}

/* Les éléments à relever, dans les deux cas — c'est le même contrat. */
function itemsGradas(E){
  return listeGradas(E).map(n => {
    const a = adrGrada(n, E.reg) || { u:"—", canal:"—" };
    return {
      id:"g" + n,
      kick:"Gradateur allumé — que voit-on sur scène ?",
      geant:String(n),
      sous:`${esc(E.reg.proto)} · U${a.u} · canal ${a.canal}`,
      get:() => circuitGrada(E, n),
      set:v => { gradaFiche(E, n).circuit = v; },
      note:{ get:() => gradaFiche(E, n).note, set:v => { gradaFiche(E, n).note = v; } },
      allume:pct => { allumerSeuls(E, [n], pct); }
    };
  });
}
function itemsApps(E){
  return adresser(E.lignes).map(x => ({
    id:"a" + x.n,
    kick:"Sur quel circuit est branché cet appareil ?",
    geant:String(x.adr),
    sous:`${esc(courtNom(x.app))} · ${esc(x.mode)} · U${x.u} ${x.adr} → ${x.fin}`,
    get:() => circuitApp(E, x.n),
    set:v => { E.affect[x.n] = v; },
    note:null, allume:null
  }));
}
