/* ---------------------------------------------------------------------------
   RDM : trouver les projecteurs derrière les nœuds Art-Net et les régler
   (adresse DMX, mode, nom, identification), par deux chemins :
   - Art-Net RDM (Rdm.java) : le nœud fait la découverte sur ses lignes et
     relaie nos questions ;
   - RDMnet (Rdmnet.java) : connexion à un broker, ou broker servi par le
     téléphone quand le réseau n'en a pas ; les passerelles y montrent leurs
     ports et les projecteurs derrière.
   Les deux chemins donnent la même fiche. La découverte LLRP (Llrp.java)
   reste à part : elle liste les appareils RDMnet, même sans broker.

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
].map(a => ({ ...a, cle:a.uid, via:"Art-Net", identifie:false, lu:true, erreur:"" }));

/* Ce qu'une passerelle RDMnet montre une fois connectée au broker. */
const DEMO_RDMNET = [
  { cle:"rdmnet:6574:00A10004", uid:"6574:00A10004", via:"RDMnet", noeud:"RDMnet", univers:0,
    fabricant:"ETC", modele:"Response Gateway", nom:"Net3 lointain", logiciel:"3.1", adresse:65535,
    canaux:0, mode:1, modes:1, listeModes:[{ n:1, canaux:0, nom:"Passerelle" }] },
  { cle:"rdmnet:6574:00A10004/1/6574:0D110007", uid:"6574:0D110007", via:"RDMnet",
    noeud:"Net3 lointain · Port A", univers:101, fabricant:"ETC", modele:"Source Four LED Series 3",
    nom:"Face 3", logiciel:"2.0", adresse:1, canaux:6, mode:2, modes:4,
    listeModes:[{ n:1, canaux:1, nom:"Direct" }, { n:2, canaux:6, nom:"Studio" },
                { n:3, canaux:9, nom:"Studio HSI" }, { n:4, canaux:11, nom:"Étendu" }] }
].map(a => ({ ...a, identifie:false, lu:true, erreur:"" }));
const DEMO_BROKER = { actif:false };

const DEMO_LLRP = [
  { uid:"6574:00A10004", mac:"00:C0:16:4A:10:04", ip:"192.168.0.61", role:"appareil RDMnet" }
];

NET.rdm = {
  /* cb reçoit { encours, attente, message, noeuds, appareils[] }. */
  suivre(cb){
    if(!PONT) return sonder(() => ({ encours:false, attente:0, noeuds:2, demo:true,
      message:"Parc de démonstration",
      appareils:DEMO_BROKER.actif ? [...DEMO_RDM, ...DEMO_RDMNET] : DEMO_RDM }), cb, 500);
    return sonder(() => JSON.parse(PONT.rdmEtat()), cb, 600);
  },
  decouvrir(relancer){ if(PONT) try { PONT.rdmDecouvrir(!!relancer); } catch(e){} },
  relire(cle){ if(PONT) try { PONT.rdmRelire(cle); } catch(e){} },
  regler(cle, quoi, valeur){
    if(!PONT){
      const a = [...DEMO_RDM, ...DEMO_RDMNET].find(x => x.cle === cle);
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
    try { PONT.rdmRegler(cle, quoi, String(valeur)); } catch(e){}
  },
  /* RDMnet : cb reçoit { mode, actif, connecte, broker, message, appareils }.
     mode : recherche, externe, interne (le téléphone sert le broker) ou manuel. */
  rdmnet(cb){
    if(!PONT) return sonder(() => DEMO_BROKER.actif
      ? { mode:"interne", actif:true, connecte:true, broker:"192.168.0.77:8888",
          message:"Broker de l'application", appareils:1, demo:true }
      : { mode:"", actif:false, connecte:false, broker:"", message:"", appareils:0, demo:true }, cb, 600);
    return sonder(() => JSON.parse(PONT.rdmnetEtat()), cb, 700);
  },
  rdmnetDemarrer(){ if(!PONT){ DEMO_BROKER.actif = true; return; } try { PONT.rdmnetDemarrer(); } catch(e){} },
  rdmnetConnecter(adr){ if(!PONT){ DEMO_BROKER.actif = true; return; } try { PONT.rdmnetConnecter(adr); } catch(e){} },
  rdmnetArreter(){ if(!PONT){ DEMO_BROKER.actif = false; return; } try { PONT.rdmnetArreter(); } catch(e){} },
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
  :root{--rdm:#8A2F7E}
  @media (prefers-color-scheme:dark){ :root:not([data-theme="light"]){--rdm:#D98BD0} }
  :root[data-theme="dark"]{--rdm:#D98BD0}
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
      if(a === b || a.via !== b.via || a.univers !== b.univers || !a.univers
         || !b.canaux || !b.adresse || b.adresse > 512) return;
      if(a.adresse <= b.adresse + b.canaux - 1 && b.adresse <= a.adresse + a.canaux - 1)
        (r[a.cle] = r[a.cle] || []).push(b);
    });
  });
  return r;
}

/* Un univers Art-Net et un univers sACN portant le même numéro ne sont pas le
   même : le groupe tient compte du chemin. */
const groupeRdm = a => (a.via === "RDMnet" ? "N" : "A") + a.univers;
const libelleGroupe = g => g[0] === "N" ? (g === "N0" ? "RDMnet" : "RDMnet U" + g.slice(1)) : "U" + g.slice(1);

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
    <p class="muted" style="margin:0 0 8px">Art-Net : le nœud doit avoir le RDM activé sur ses ports, et le
      port doit être en sortie DMX. « Relancer la découverte » demande au nœud de refaire le tour de ses
      lignes : comptez quelques secondes.</p>
    <p class="muted" style="margin:0 0 8px">RDMnet : la passerelle doit avoir le RDMnet activé et chercher
      son broker dans la portée « default ». Un même projecteur peut apparaître deux fois s'il est vu par
      les deux chemins.</p>
    <p class="muted" style="margin:0">Un réglage part vers le projecteur, puis l'écran relit ce qu'il a
      vraiment pris. Évitez de régler pendant une représentation : certains appareils coupent leur
      sortie le temps de changer de mode.</p>`;
  d.append(aide);

  let choisi = null, filtre = 0, dernier = [], sig = "", sigFiche = "";

  const peindreListe = e => {
    const tous = e.appareils || [];
    const groupes = [...new Set(tous.map(groupeRdm))]
      .sort((a, b) => a[0].localeCompare(b[0]) || +a.slice(1) - +b.slice(1));
    if(filtre && !groupes.includes(filtre)) filtre = 0;
    const vus = tous.filter(a => !filtre || groupeRdm(a) === filtre)
      .sort((a, b) => groupeRdm(a).localeCompare(groupeRdm(b)) || a.univers - b.univers
        || a.adresse - b.adresse || a.cle.localeCompare(b.cle));
    const conflits = chevauchements(tous);

    filtres.replaceChildren(...(groupes.length > 1 ? [0, ...groupes].map(u => {
      const b = el("button", "pill", u ? libelleGroupe(u) : "Tous");
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
      const b = el("button", "row" + (a.cle === choisi ? " sel" : ""));
      b.style.setProperty("--c", conflits[a.cle] ? "var(--ko)" : "var(--rdm)");
      const fin = a.canaux ? a.adresse + a.canaux - 1 : a.adresse;
      const adr = !a.lu ? "…" : a.canaux && a.adresse <= 512 ? troisChiffres(a.adresse) : "—";
      const av = a.univers ? (a.via === "RDMnet" ? "N" : "U") + a.univers : "NET";
      b.innerHTML = `<span class="av">${av}</span>
        <span class="g"><b>${esc(nomRdm(a))}</b><span>${esc([a.via === "RDMnet" ? "RDMnet" : "", a.fabricant,
          a.nom ? a.modele : "", a.uid].filter(Boolean).join(" · "))}</span>
          ${conflits[a.cle] ? `<span class="alerte">Chevauche ${esc(conflits[a.cle].map(nomRdm).join(", "))}</span>` : ""}
          ${a.erreur ? `<span class="alerte">${esc(a.erreur)}</span>` : ""}</span>
        <span class="r">${adr}<i>${a.canaux ? a.canaux + " ch → " + troisChiffres(fin) : ""}</i></span>`;
      b.onclick = () => {
        toucher();
        choisi = choisi === a.cle ? null : a.cle;
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
    const a = (e.appareils || []).find(x => x.cle === choisi);
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
      <div class="kv"><span>${a.via === "RDMnet" ? "RDMnet" : "Nœud"}</span><b>${esc(a.noeud && a.noeud !== a.ip ? a.noeud : a.ip)}${a.univers ? " · U" + a.univers : ""}</b></div>
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
    const regler = (quoi, v) => { toucher(); NET.rdm.regler(a.cle, quoi, v); };
    $("#rdm-adr-ok", c).onclick = () => regler("adresse", $("#rdm-adr", c).value);
    if($("#rdm-mode-ok", c)) $("#rdm-mode-ok", c).onclick = () => {
      const m = (a.listeModes || []).find(x => x.n === +$("#rdm-mode", c).value);
      if(m && m.n !== a.mode && !confirm(`Passer en mode ${m.n} (${m.canaux} canaux) ? `
        + "Le projecteur changera de nombre de canaux.")) return;
      regler("mode", $("#rdm-mode", c).value);
    };
    $("#rdm-nom-ok", c).onclick = () => regler("nom", $("#rdm-nom", c).value);
    $("#rdm-id", c).onclick = () => regler("identifier", a.identifie ? "0" : "1");
    $("#rdm-relire", c).onclick = () => { toucher(); NET.rdm.relire(a.cle); };
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
  /* La carte a deux parties : le broker, qui se repeint au fil de son état,
     et la découverte LLRP, qui se repeint à chaque réponse. Le champ d'adresse
     n'est jamais redessiné, pour ne pas perdre ce qu'on y tape. */
  net.innerHTML = `<h4>RDMnet</h4><div id="rn-etat"></div>
    <div class="rdm-champ" id="rn-manuel">
      <div class="f"><label for="rn-adr">Broker à la main (IP ou IP:port)</label>
        <input id="rn-adr" type="text" inputmode="decimal" placeholder="192.168.0.20:8888"
          style="font-family:var(--mono)"></div>
      <button class="pill" id="rn-adr-ok">Connecter</button>
    </div>
    <h4 style="margin-top:18px">Découverte LLRP</h4><div id="rn-llrp"></div>`;
  $("#rn-adr-ok", net).onclick = () => {
    const v = $("#rn-adr", net).value.trim();
    if(!v) return;
    toucher(); NET.rdm.rdmnetConnecter(v);
  };
  let sigNet = "";
  const peindreBroker = e => {
    const s = JSON.stringify(e);
    if(s === sigNet) return;
    sigNet = s;
    const role = e.mode === "interne" ? "Le téléphone sert le broker"
               : e.mode === "externe" ? "Broker trouvé sur le réseau"
               : e.mode === "manuel" ? "Broker donné à la main"
               : e.mode === "recherche" ? "Recherche d'un broker" : "Arrêté";
    const zone = $("#rn-etat", net);
    zone.innerHTML = `
      <div class="kv"><span>État</span><b>${esc(role)}</b></div>
      ${e.broker ? `<div class="kv"><span>Broker</span><b>${esc(e.broker)}</b></div>` : ""}
      ${e.actif ? `<div class="kv"><span>Appareils connectés</span><b>${e.appareils || 0}</b></div>` : ""}
      ${e.message ? `<p class="${e.connecte ? "muted" : "alerte"}" style="margin:8px 0 0">${esc(e.message)}</p>` : ""}
      ${!e.actif ? `<p class="muted" style="margin:8px 0 0">L'application cherche un broker (une console, un
        serveur RDMnet). S'il n'y en a pas, le téléphone en sert un : les appareils RDMnet du réseau viennent
        s'y connecter, et leurs projecteurs apparaissent dans la liste.</p>` : ""}
      <button class="pill" id="rn-go" style="padding:12px;width:100%;margin-top:10px">
        ${e.actif ? "Arrêter RDMnet" : "Activer RDMnet"}</button>`;
    $("#rn-go", zone).onclick = () => {
      toucher();
      if(e.actif){
        if(e.mode !== "interne" || confirm("Arrêter le broker du téléphone ? Les appareils RDMnet "
          + "qui y sont connectés devront en retrouver un autre.")) NET.rdm.rdmnetArreter();
      } else NET.rdm.rdmnetDemarrer();
    };
  };
  const arretBroker = NET.rdm.rdmnet(peindreBroker);

  let arretLlrp = null;
  const peindreLlrp = e => {
    const cs = (e && e.cibles) || [];
    const zone = $("#rn-llrp", net);
    zone.innerHTML = `<p class="muted" style="margin:0 0 10px">Liste les appareils RDMnet du réseau,
        même sans broker et même mal adressés.</p>
      ${cs.map(c => `<div class="kv"><span>${esc(c.role)}<br><small class="muted">${esc(c.mac)}</small></span>
        <b>${esc(c.uid)}<br><small class="muted">${esc(c.ip)}</small></b></div>`).join("")}
      ${e && !e.encours && !cs.length ? `<p class="muted">Aucune réponse LLRP.</p>` : ""}
      ${e && e.erreur ? `<p class="alerte">${esc(e.erreur)}</p>` : ""}
      <button class="pill" id="llrp-go" style="padding:12px;width:100%;margin-top:8px"${e && e.encours ? " disabled" : ""}>
        ${e && e.encours ? "Recherche…" : "Chercher en LLRP"}</button>`;
    $("#llrp-go", zone).onclick = () => {
      toucher();
      if(arretLlrp) arretLlrp();
      arretLlrp = NET.rdm.llrp(x => { peindreLlrp(x); if(!x.encours && arretLlrp){ arretLlrp(); } });
    };
  };
  peindreLlrp(null);

  /* ------------------------------ relevé ------------------------------ */
  const arret = NET.rdm.suivre(e => {
    dernier = e.appareils || [];
    const univers = new Set(dernier.filter(a => a.univers).map(groupeRdm)).size;
    tete.innerHTML = `<div class="kick">RDM · Art-Net${dernier.some(a => a.via === "RDMnet") ? " et RDMnet" : ""}</div>
      <h3>${dernier.length} appareil${dernier.length > 1 ? "s" : ""}</h3>
      <p>${esc(e.message || "")} ${marque(e.demo)}</p>
      <div class="stats"><div><b>${e.noeuds ?? 0}</b><span>Nœuds</span></div>
        <div><b>${univers}</b><span>Univers</span></div>
        <div><b>${e.encours ? (e.attente || 0) + 1 : 0}</b><span>En cours</span></div></div>`;
    const s = JSON.stringify([filtre, choisi, dernier.map(a =>
      [a.cle, a.nom, a.modele, a.fabricant, a.adresse, a.canaux, a.univers, a.erreur, a.lu])]);
    if(s !== sig){ sig = s; peindreListe(e); }
    peindreFiche(e);
  });
  NET.rdm.decouvrir(false);
  poser(() => { arret(); arretBroker(); if(arretLlrp) arretLlrp(); });
  return d;
}
