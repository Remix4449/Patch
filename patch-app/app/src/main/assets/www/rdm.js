/* ---------------------------------------------------------------------------
   RDM : trouver les projecteurs derrière les nœuds Art-Net et les régler
   (adresse DMX, mode, nom, identification). La couche native (Rdm.java) passe
   par l'Art-Net RDM : le nœud fait la découverte sur ses lignes et relaie nos
   questions. RDMnet : seulement la découverte LLRP (Llrp.java).

   Dans un navigateur, window.Regie n'existe pas : on joue un petit parc de
   démonstration qui répond aux réglages comme le ferait un projecteur.
--------------------------------------------------------------------------- */

const DEMO_RDM = [
  { uid:"4845:1A2B0001", ip:"192.168.0.60", noeud:"GN10 cour", univers:1, fabricant:"Genetix",
    modele:"Lyre spot 250", nom:"Spot 1", logiciel:"2.04", adresse:1, canaux:16, mode:1, modes:2,
    listeModes:[{ n:1, canaux:16, nom:"Standard" }, { n:2, canaux:22, nom:"Étendu" }] },
  { uid:"4845:1A2B0002", ip:"192.168.0.60", noeud:"GN10 cour", univers:1, fabricant:"Genetix",
    modele:"Lyre spot 250", nom:"Spot 2", logiciel:"2.04", adresse:17, canaux:16, mode:1, modes:2,
    listeModes:[{ n:1, canaux:16, nom:"Standard" }, { n:2, canaux:22, nom:"Étendu" }] },
  { uid:"6574:00C31F22", ip:"192.168.0.61", noeud:"Net3 jardin", univers:2, fabricant:"ETC",
    modele:"ColorSource PAR", nom:"", logiciel:"1.3.0", adresse:101, canaux:5, mode:2, modes:3,
    listeModes:[{ n:1, canaux:4, nom:"Direct" }, { n:2, canaux:5, nom:"RGB + intensité" },
                { n:3, canaux:8, nom:"Étendu" }] },
  { uid:"6574:00C31F23", ip:"192.168.0.61", noeud:"Net3 jardin", univers:2, fabricant:"ETC",
    modele:"ColorSource PAR", nom:"Contre 2", logiciel:"1.3.0", adresse:104, canaux:5, mode:2, modes:3,
    listeModes:[{ n:1, canaux:4, nom:"Direct" }, { n:2, canaux:5, nom:"RGB + intensité" },
                { n:3, canaux:8, nom:"Étendu" }] }
].map(a => ({ ...a, identifie:false, lu:true, erreur:"" }));

const DEMO_LLRP = [
  { uid:"6574:00A10004", mac:"00:C0:16:4A:10:04", ip:"192.168.0.61", role:"appareil RDMnet" }
];

NET.rdm = {
  /* cb reçoit { encours, attente, message, noeuds, appareils[] }. */
  suivre(cb){
    if(!PONT) return sonder(() => ({ encours:false, attente:0, noeuds:2, demo:true,
      message:"Parc de démonstration", appareils:DEMO_RDM }), cb, 500);
    return sonder(() => JSON.parse(PONT.rdmEtat()), cb, 600);
  },
  decouvrir(relancer){ if(PONT) try { PONT.rdmDecouvrir(!!relancer); } catch(e){} },
  relire(uid){ if(PONT) try { PONT.rdmRelire(uid); } catch(e){} },
  regler(uid, quoi, valeur){
    if(!PONT){
      const a = DEMO_RDM.find(x => x.uid === uid);
      if(!a) return;
      setTimeout(() => {
        a.erreur = "";
        if(quoi === "adresse"){
          const v = +valeur;
          if(v < 1 || v > 512) a.erreur = "valeur hors limites"; else a.adresse = v;
        }
        if(quoi === "mode"){
          const m = a.listeModes.find(x => x.n === +valeur);
          if(m){ a.mode = m.n; a.canaux = m.canaux; }
        }
        if(quoi === "nom") a.nom = String(valeur).slice(0, 32);
        if(quoi === "identifier") a.identifie = valeur === "1";
      }, 350);
      return;
    }
    try { PONT.rdmRegler(uid, quoi, String(valeur)); } catch(e){}
  },
  /* Découverte LLRP (RDMnet). cb reçoit { encours, erreur, cibles[] }. */
  llrp(cb){
    if(!PONT) return simuler(cb, DEMO_LLRP, "cibles", 1);
    try { PONT.llrpDecouvrir(); } catch(e){}
    return sonder(() => JSON.parse(PONT.llrpEtat()), cb, 700);
  }
};

/* Couleur et mise en page de l'écran, posées ici pour que tout l'outil tienne
   dans ce fichier. Les trois blocs suivent ceux de index.html : clair, sombre
   du système, sombre choisi. */
(() => {
  const st = document.createElement("style");
  st.textContent = `
  :root{--rdm:#8A5A00}
  @media (prefers-color-scheme:dark){ :root:not([data-theme="light"]){--rdm:#B87A1E} }
  :root[data-theme="dark"]{--rdm:#B87A1E}
  .rdm .row.sel{border-color:var(--rdm)}
  .rdm .row .av{font-size:11px}
  .rdm .alerte{display:block;color:var(--ko);font:600 12px/1.3 var(--body);margin-top:3px}
  .rdm .rdm-champ{display:grid;grid-template-columns:1fr auto;gap:10px;align-items:end;margin-top:12px}
  .rdm .rdm-champ .f{margin-bottom:0}
  .rdm .rdm-champ .pill{padding:13px 14px}
  .rdm .fiche-rdm .ff{margin-top:14px}
  .rdm .card small{font-size:11.5px}`;
  document.head.append(st);
})();

const troisChiffres = n => String(n).padStart(3, "0");

/* Deux appareils du même univers qui se marchent dessus : le cas qu'on vient
   justement régler en RDM. */
function chevauchements(appareils){
  const r = {};
  appareils.forEach(a => {
    if(!a.canaux || !a.adresse || a.adresse > 512) return;
    appareils.forEach(b => {
      if(a === b || a.univers !== b.univers || !b.canaux || !b.adresse || b.adresse > 512) return;
      if(a.adresse <= b.adresse + b.canaux - 1 && b.adresse <= a.adresse + a.canaux - 1)
        (r[a.uid] = r[a.uid] || []).push(b);
    });
  });
  return r;
}

function nomRdm(a){ return a.nom || a.modele || (a.lu ? "Appareil sans nom" : "Lecture…"); }

function vRdm(){
  const d = el("div", "rdm");
  d.append(bar("RDM"));

  const tete = el("div", "hero");
  tete.style.setProperty("--c", "var(--rdm)");
  d.append(tete);

  const actions = el("div", "ff");
  const bCherche = el("button", "pill", "Rechercher");
  const bRelance = el("button", "pill", "Relancer la découverte");
  [bCherche, bRelance].forEach(b => b.style.padding = "12px");
  actions.style.marginBottom = "12px";
  actions.append(bCherche, bRelance);
  d.append(actions);
  bCherche.onclick = () => { toucher(); NET.rdm.decouvrir(false); };
  bRelance.onclick = () => { toucher(); NET.rdm.decouvrir(true); };

  const filtres = el("div", "pills");
  const fiche = el("div");
  const liste = el("div", "rowlist");
  d.append(filtres, fiche, liste);

  const net = el("div", "card");
  d.append(net);

  const aide = el("div", "card");
  aide.innerHTML = `<h4>À savoir</h4>
    <p class="muted" style="margin:0 0 8px">Le nœud doit avoir le RDM activé sur ses ports, et le port
      doit être en sortie DMX. « Relancer la découverte » demande au nœud de refaire le tour de ses lignes :
      comptez quelques secondes.</p>
    <p class="muted" style="margin:0">Un réglage part vers le projecteur, puis l'écran relit ce qu'il a
      vraiment pris. Évitez de régler pendant une représentation : certains appareils coupent leur
      sortie le temps de changer de mode.</p>`;
  d.append(aide);

  let choisi = null, filtre = 0, dernier = [], sig = "", sigFiche = "";

  const peindreListe = e => {
    const tous = e.appareils || [];
    const univers = [...new Set(tous.map(a => a.univers))].sort((a, b) => a - b);
    if(filtre && !univers.includes(filtre)) filtre = 0;
    const vus = tous.filter(a => !filtre || a.univers === filtre)
      .sort((a, b) => a.univers - b.univers || a.adresse - b.adresse || a.uid.localeCompare(b.uid));
    const conflits = chevauchements(tous);

    filtres.replaceChildren(...(univers.length > 1 ? [0, ...univers].map(u => {
      const b = el("button", "pill", u ? "U" + u : "Tous");
      b.setAttribute("aria-pressed", u === filtre ? "true" : "false");
      b.onclick = () => { filtre = u; sig = ""; peindreListe(e); };
      return b;
    }) : []));

    if(!vus.length){
      liste.replaceChildren(el("p", "muted", e.noeuds
        ? "Aucun appareil RDM rendu par les nœuds. Essayez « Relancer la découverte »."
        : "Aucun nœud Art-Net n'a répondu. Vérifiez que le téléphone est sur le Wi-Fi du plateau."));
      return;
    }
    liste.replaceChildren(...vus.map(a => {
      const b = el("button", "row" + (a.uid === choisi ? " sel" : ""));
      b.style.setProperty("--c", conflits[a.uid] ? "var(--ko)" : "var(--rdm)");
      const fin = a.canaux ? a.adresse + a.canaux - 1 : a.adresse;
      const adr = !a.lu ? "…" : a.canaux && a.adresse <= 512 ? troisChiffres(a.adresse) : "—";
      b.innerHTML = `<span class="av">U${a.univers}</span>
        <span class="g"><b>${esc(nomRdm(a))}</b><span>${esc([a.fabricant, a.nom ? a.modele : "", a.uid]
          .filter(Boolean).join(" · "))}</span>
          ${conflits[a.uid] ? `<span class="alerte">Chevauche ${esc(conflits[a.uid].map(nomRdm).join(", "))}</span>` : ""}
          ${a.erreur ? `<span class="alerte">${esc(a.erreur)}</span>` : ""}</span>
        <span class="r">${adr}<i>${a.canaux ? a.canaux + " ch → " + troisChiffres(fin) : ""}</i></span>`;
      b.onclick = () => {
        toucher();
        choisi = choisi === a.uid ? null : a.uid;
        sig = ""; sigFiche = "";
        peindreListe(e); peindreFiche(e);
        if(choisi) fiche.scrollIntoView({ block:"nearest", behavior:"smooth" });
      };
      return b;
    }));
  };

  /* La fiche ne se redessine que si l'appareil a changé : sinon un champ en
     cours de saisie serait effacé à chaque relevé. */
  const peindreFiche = e => {
    const a = (e.appareils || []).find(x => x.uid === choisi);
    if(!a){ fiche.replaceChildren(); return; }
    const s = JSON.stringify([a.adresse, a.mode, a.nom, a.canaux, a.identifie, a.erreur, a.lu, a.listeModes]);
    if(s === sigFiche) return;
    const f = document.activeElement;
    const saisie = f && fiche.contains(f) && (f.tagName === "INPUT" || f.tagName === "SELECT");
    sigFiche = s;
    if(saisie && fiche.firstChild) {             // on garde les champs, on rafraîchit le reste
      const i = $("#rdm-etat", fiche); if(i) i.innerHTML = etatFiche(a);
      return;
    }
    const c = el("div", "card fiche-rdm");
    const modes = (a.listeModes || []).map(m =>
      `<option value="${m.n}"${m.n === a.mode ? " selected" : ""}>${m.n} · ${esc(m.nom || "Mode " + m.n)} (${m.canaux} ch)</option>`).join("");
    c.innerHTML = `<h4>${esc(nomRdm(a))}</h4>
      <div class="kv"><span>Fabricant</span><b>${esc(a.fabricant || "—")}</b></div>
      <div class="kv"><span>Modèle</span><b>${esc(a.modele || "—")}</b></div>
      <div class="kv"><span>UID</span><b>${esc(a.uid)}</b></div>
      <div class="kv"><span>Nœud</span><b>${esc(a.noeud && a.noeud !== a.ip ? a.noeud : a.ip)} · U${a.univers}</b></div>
      <div class="kv"><span>Logiciel</span><b>${esc(a.logiciel || "—")}</b></div>
      <div id="rdm-etat">${etatFiche(a)}</div>
      <div class="rdm-champ">
        <div class="f"><label for="rdm-adr">Adresse DMX</label>
          <input id="rdm-adr" type="number" inputmode="numeric" min="1" max="512" value="${a.adresse > 512 ? "" : a.adresse}"></div>
        <button class="pill" id="rdm-adr-ok">Appliquer</button>
      </div>
      ${modes ? `<div class="rdm-champ">
        <div class="f"><label for="rdm-mode">Mode</label><select id="rdm-mode">${modes}</select></div>
        <button class="pill" id="rdm-mode-ok">Appliquer</button>
      </div>` : a.modes ? `<p class="muted">${a.modes} modes, noms non lus.</p>` : ""}
      <div class="rdm-champ">
        <div class="f"><label for="rdm-nom">Nom (32 caractères)</label>
          <input id="rdm-nom" type="text" maxlength="32" value="${esc(a.nom)}" style="font-family:var(--body)"></div>
        <button class="pill" id="rdm-nom-ok">Appliquer</button>
      </div>
      <div class="ff">
        <button class="pill" id="rdm-id" aria-pressed="${a.identifie ? "true" : "false"}" style="padding:12px">
          ${a.identifie ? "Arrêter l'identification" : "Identifier"}</button>
        <button class="pill" id="rdm-relire" style="padding:12px">Relire</button>
      </div>`;
    fiche.replaceChildren(c);
    const regler = (quoi, v) => { toucher(); NET.rdm.regler(a.uid, quoi, v); };
    $("#rdm-adr-ok", c).onclick = () => regler("adresse", $("#rdm-adr", c).value);
    if($("#rdm-mode-ok", c)) $("#rdm-mode-ok", c).onclick = () => {
      const m = (a.listeModes || []).find(x => x.n === +$("#rdm-mode", c).value);
      if(m && m.n !== a.mode && !confirm(`Passer en mode ${m.n} (${m.canaux} canaux) ? `
        + "Le projecteur changera de nombre de canaux.")) return;
      regler("mode", $("#rdm-mode", c).value);
    };
    $("#rdm-nom-ok", c).onclick = () => regler("nom", $("#rdm-nom", c).value);
    $("#rdm-id", c).onclick = () => regler("identifier", a.identifie ? "0" : "1");
    $("#rdm-relire", c).onclick = () => { toucher(); NET.rdm.relire(a.uid); };
  };

  const etatFiche = a => {
    const fin = a.canaux ? a.adresse + a.canaux - 1 : a.adresse;
    const m = (a.listeModes || []).find(x => x.n === a.mode);
    return `<div class="kv"><span>Occupe</span><b>${a.canaux && a.adresse <= 512
        ? troisChiffres(a.adresse) + " → " + troisChiffres(fin) + " · " + a.canaux + " ch" : "aucun canal"}</b></div>
      <div class="kv"><span>Mode</span><b>${a.modes ? a.mode + "/" + a.modes + (m && m.nom ? " · " + esc(m.nom) : "") : "—"}</b></div>
      ${a.erreur ? `<p class="alerte" style="margin:8px 0 0">${esc(a.erreur)}</p>` : ""}`;
  };

  /* ------------------------------ RDMnet ------------------------------ */
  let arretLlrp = null;
  const peindreNet = e => {
    const cs = (e && e.cibles) || [];
    net.innerHTML = `<h4>RDMnet · découverte LLRP</h4>
      <p class="muted" style="margin:0 0 10px">Liste les appareils RDMnet du réseau, même mal adressés.
        Le réglage des projecteurs par RDMnet passe par un broker, que l'application ne gère pas encore :
        réglez-les par l'Art-Net RDM ci-dessus.</p>
      ${cs.map(c => `<div class="kv"><span>${esc(c.role)}<br><small class="muted">${esc(c.mac)}</small></span>
        <b>${esc(c.uid)}<br><small class="muted">${esc(c.ip)}</small></b></div>`).join("")}
      ${e && !e.encours && !cs.length ? `<p class="muted">Aucune réponse LLRP.</p>` : ""}
      ${e && e.erreur ? `<p class="alerte">${esc(e.erreur)}</p>` : ""}
      <button class="pill" id="llrp-go" style="padding:12px;width:100%;margin-top:8px"${e && e.encours ? " disabled" : ""}>
        ${e && e.encours ? "Recherche…" : "Chercher en LLRP"}</button>`;
    $("#llrp-go", net).onclick = () => {
      toucher();
      if(arretLlrp) arretLlrp();
      arretLlrp = NET.rdm.llrp(x => { peindreNet(x); if(!x.encours && arretLlrp){ arretLlrp(); } });
    };
  };
  peindreNet(null);

  /* ------------------------------ relevé ------------------------------ */
  const arret = NET.rdm.suivre(e => {
    dernier = e.appareils || [];
    const univers = new Set(dernier.map(a => a.univers)).size;
    tete.innerHTML = `<div class="kick">Art-Net RDM</div>
      <h3>${dernier.length} appareil${dernier.length > 1 ? "s" : ""}</h3>
      <p>${esc(e.message || "")} ${marque(e.demo)}</p>
      <div class="stats"><div><b>${e.noeuds ?? 0}</b><span>Nœuds</span></div>
        <div><b>${univers}</b><span>Univers</span></div>
        <div><b>${e.encours ? (e.attente || 0) + 1 : 0}</b><span>En cours</span></div></div>`;
    const s = JSON.stringify([filtre, choisi, dernier.map(a =>
      [a.uid, a.nom, a.modele, a.fabricant, a.adresse, a.canaux, a.univers, a.erreur, a.lu])]);
    if(s !== sig){ sig = s; peindreListe(e); }
    peindreFiche(e);
  });
  NET.rdm.decouvrir(false);
  poser(() => { arret(); if(arretLlrp) arretLlrp(); });
  return d;
}
