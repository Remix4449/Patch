/* ---------------------------------------------------------------------------
   Pont entre l'interface et la couche réseau native (classe Java Regie).
   Dans un navigateur, window.Regie n'existe pas : on rejoue les jeux de
   démonstration de common.js, avec la même forme de données et le même tempo.
   Toutes les méthodes suivent le même contrat : démarrer, interroger, arrêter.
--------------------------------------------------------------------------- */

const PONT = typeof window !== "undefined" ? window.Regie : undefined;

const NET = {
  natif: !!PONT,

  /* Réseau Wi-Fi courant. Renvoie null tant que le téléphone n'est pas associé. */
  wifi(){
    if(!PONT) return { ssid:"Démonstration", ip:"192.168.0.77", masque:"255.255.255.0",
                       passerelle:"192.168.0.1", base:"192.168.0", ok:true };
    try { return JSON.parse(PONT.wifi()); } catch(e){ return null; }
  },

  /* Balayage du sous-réseau. cb reçoit { encours, faits, total, hotes[] }. */
  scan(cb){
    if(!PONT) return simuler(cb, DEMO_RESEAU.map(r => ({
      ip:r.ip, nom:r.nom, role:r.role, ms:r.ms, ok:r.ok })), "hotes", 254);
    PONT.scanStart();
    return sonder(() => JSON.parse(PONT.scanState()), cb, 400);
  },

  /* Découverte Art-Net : ArtPoll puis collecte des ArtPollReply. */
  noeuds(cb){
    if(!PONT) return simuler(cb, DEMO_SOURCES_DMX.filter(s => s.proto === "Art-Net").map(s => ({
      ip:s.src, court:s.nom, long:s.nom, mac:"00:1F:2E:3A:4B:5C",
      ports:s.univ.map(u => ({ type:"sortie DMX", univers:u })) })), "noeuds", 1);
    PONT.artPollStart();
    return sonder(() => JSON.parse(PONT.nodesState()), cb, 600);
  },

  /* Recensement passif : tout univers Art-Net ou sACN entendu sur le réseau. */
  univers(cb){
    if(!PONT) return simuler(cb, DEMO_SOURCES_DMX.flatMap(s => s.univ.map(u => ({
      proto:s.proto, univers:u, src:s.src, nom:s.nom, prio:s.prio, hz:s.hz }))), "univers", 1);
    return sonder(() => JSON.parse(PONT.universesState()), cb, 800);
  },

  /* Écoute d'un univers. cb reçoit { proto, univers, hz, src, nom, prio, niveaux[512] }. */
  dmx(proto, univers, cb){
    if(!PONT){
      let t = 0;
      const id = setInterval(() => {
        t += 2;
        cb({ proto, univers, hz:44, src:"192.168.0.10", nom:"Pupitre lumière", prio:100,
             niveaux:demoUniverse(t), demo:true });
      }, 140);
      return () => clearInterval(id);
    }
    PONT.dmxStart(proto, univers);
    const stop = sonder(() => {
      const e = JSON.parse(PONT.dmxState());
      e.niveaux = deBase64(e.niveaux);
      return e;
    }, cb, 100);
    return () => { stop(); try { PONT.dmxStop(); } catch(e){} };
  },

  /* Sources NDI annoncées en mDNS (_ndi._tcp). */
  ndi(cb){
    if(!PONT) return simuler(cb, DEMO_NDI.map(n => ({
      nom:n.nom, machine:n.machine, ip:"192.168.0.31", port:5961,
      fmt:n.fmt, mbps:n.mbps, tally:n.tally, ok:n.ok })), "sources", 8);
    PONT.ndiStart();
    return sonder(() => JSON.parse(PONT.ndiState()), cb, 700);
  },

  /* Émission continue — télécommande gradateurs. trames : { univers: niveaux[512] }.
     Le natif répète les trames à 30 Hz tant qu'elles sont posées ; un objet vide
     relâche les univers, sinon les gradateurs resteraient au dernier niveau reçu. */
  emission(trames, proto, prio, cible){
    if(!PONT){ DEMO_EMISSION.trames = trames; DEMO_EMISSION.proto = proto; return false; }
    try {
      PONT.emitStart(proto || "sACN", prio || 100, cible || "");
      const o = {};
      Object.keys(trames).forEach(u => o[u] = enBase64(trames[u]));
      return PONT.emitSet(JSON.stringify(o));
    } catch(e){ return false; }
  },

  emissionArret(){
    if(!PONT){ DEMO_EMISSION.trames = {}; return false; }
    try { PONT.emitStop(); return true; } catch(e){ return false; }
  },

  emissionEtat(){
    if(!PONT) return { actif:Object.keys(DEMO_EMISSION.trames).length > 0,
                       proto:DEMO_EMISSION.proto, demo:true };
    try { return JSON.parse(PONT.emitState()); } catch(e){ return { actif:false }; }
  },

  /* Émission d'une trame Art-Net — testeur d'adresse de poche. */
  emettre(univers, niveaux, cible){
    if(!PONT) return false;
    try { return PONT.send(univers, enBase64(niveaux), cible || ""); }
    catch(e){ return false; }
  },

  /* Impression : boîte d'impression Android, qui sait enregistrer en PDF. */
  imprimer(html, nom){
    if(!PONT) return false;
    try { return PONT.imprimer(html, nom || "patch"); } catch(e){ return false; }
  },

  /* Import d'un fichier GDTF : ouvre le sélecteur puis rend les modes lus. */
  gdtf(cb){
    if(!PONT){ cb({ erreur:"disponible seulement dans l'application" }); return () => {}; }
    try { PONT.gdtfStart(); } catch(e){ cb({ erreur:"sélecteur indisponible" }); return () => {}; }
    let tours = 0, fini = false;
    const stop = sonder(() => JSON.parse(PONT.gdtfState()), e => {
      if(fini) return;
      if(e.pret || e.erreur){ fini = true; stop(); cb(e); }
      else if(++tours > 300){ fini = true; stop(); cb({ erreur:"délai dépassé" }); }
    }, 400);
    return stop;
  },

  arreterTout(){ if(PONT){ try { PONT.stopAll(); } catch(e){} } }
};

/* Hors application, l'émission n'a nulle part où aller : on garde l'état pour
   que l'interface se comporte pareil, sans qu'un paquet parte. */
const DEMO_EMISSION = { trames:{}, proto:"sACN" };

/* --------------------------- utilitaires ------------------------------- */

function sonder(lire, cb, ms){
  let vivant = true;
  const tour = () => {
    if(!vivant) return;
    try { cb(lire()); } catch(e){ /* le pont n'est pas prêt : on retentera */ }
    setTimeout(tour, ms);
  };
  setTimeout(tour, 0);   // jamais synchrone : l'appelant reçoit son arrêt d'abord
  return () => { vivant = false; };
}

/* Rejoue une liste par petits paquets, pour que la démo ait l'allure d'un scan. */
function simuler(cb, items, cle, total){
  let i = 0, vivant = true;
  const tour = () => {
    if(!vivant) return;
    i = Math.min(items.length, i + 2);
    const etat = { encours:i < items.length, faits:Math.round(i / items.length * total), total, demo:true };
    etat[cle] = items.slice(0, i);
    cb(etat);
    if(i < items.length) setTimeout(tour, 320);
  };
  setTimeout(tour, 200);
  return () => { vivant = false; };
}

function deBase64(b64){
  if(!b64) return new Array(512).fill(0);
  const bin = atob(b64), out = new Array(512).fill(0);
  for(let i = 0; i < Math.min(512, bin.length); i++) out[i] = bin.charCodeAt(i);
  return out;
}

function enBase64(niveaux){
  let bin = "";
  for(let i = 0; i < 512; i++) bin += String.fromCharCode(niveaux[i] || 0);
  return btoa(bin);
}
