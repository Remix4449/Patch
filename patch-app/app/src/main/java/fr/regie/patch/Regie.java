package fr.regie.patch;

import android.content.Context;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pont exposé au JavaScript sous le nom « Regie ».
 *
 * Contrat : on démarre, on interroge, on arrête. Chaque méthode d'état renvoie
 * du JSON et ne bloque jamais — les protocoles tournent dans leurs propres fils.
 */
public class Regie {

    private final MainActivity act;
    private final Reseau reseau;
    private final ArtNet art = new ArtNet();
    private final Sacn sacn = new Sacn();
    private final Scanner scan = new Scanner();
    private final Emetteur emetteur = new Emetteur(art, sacn);
    private final Rdm rdm = new Rdm(art);
    private final Rdmnet rdmnet = new Rdmnet(rdm);
    private final Llrp llrp = new Llrp();
    private final Ndi ndi;
    private final Maj maj;

    private volatile String proto = "Art-Net";
    private volatile int univers = 1;

    public Regie(MainActivity a) {
        act = a;
        reseau = new Reseau(a);
        ndi = new Ndi(a);
        maj = a.maj;
        maj.demarrer();          // vérification au lancement, muette s'il n'y a rien
        // Le Wi-Fi arrive souvent après l'application, et peut changer en cours de
        // route : on repointe les deux protocoles au lieu de rester sur l'état
        // du démarrage, où le sACN n'avait aucune interface à viser.
        reseau.surAdresse(new Reseau.Ecoute() {
            public void adresse(final String ip, final String diffusion) {
                // Le rappel de ConnectivityManager arrive sur le fil principal :
                // on ne touche pas aux prises depuis là.
                new Thread(new Runnable() {
                    public void run() {
                        art.demarrer(diffusion);
                        sacn.reglerInterface(ip);
                    }
                }, "reseau-change").start();
            }
        });
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 100 && !reseau.pret(); i++) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                }
                art.demarrer(reseau.diffusion);
                sacn.demarrer(reseau.ip);
                try { Thread.sleep(300); } catch (InterruptedException ignore) { }
                art.interroger();
            }
        }, "demarrage").start();
    }

    /* ------------------------------- réseau ----------------------------- */

    @JavascriptInterface
    public String wifi() {
        try {
            JSONObject o = new JSONObject();
            o.put("ssid", "");
            o.put("ip", reseau.ip);
            o.put("masque", reseau.masque);
            o.put("diffusion", reseau.diffusion);
            o.put("base", reseau.base);
            o.put("ok", reseau.pret());
            return o.toString();
        } catch (Exception e) { return "null"; }
    }

    @JavascriptInterface
    public void scanStart() { scan.lancer(reseau.base); }

    @JavascriptInterface
    public String scanState() {
        try {
            JSONObject o = new JSONObject();
            o.put("encours", scan.encours);
            o.put("faits", scan.faits.get());
            o.put("total", scan.total);
            JSONArray a = new JSONArray();
            for (Scanner.Hote h : scan.hotes) {
                JSONObject j = new JSONObject();
                j.put("ip", h.ip);
                j.put("nom", nomConnu(h.ip));
                j.put("role", roleConnu(h.ip));
                j.put("ms", Math.round(h.ms * 10) / 10.0);
                j.put("ok", h.ok);
                a.put(j);
            }
            o.put("hotes", a);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Nom déduit des annonces Art-Net ou mDNS, à défaut l'adresse. */
    private String nomConnu(String ip) {
        ArtNet.Noeud n = art.noeuds.get(ip);
        if (n != null) return n.longNom.isEmpty() ? n.court : n.longNom;
        for (Ndi.Source s : ndi.sources) if (ip.equals(s.ip)) return s.machine.isEmpty() ? s.nom : s.machine;
        for (Sacn.Uni u : sacn.univers.values()) if (ip.equals(u.src) && !u.nom.isEmpty()) return u.nom;
        return ip;
    }

    private String roleConnu(String ip) {
        if (art.noeuds.containsKey(ip)) return "nœud Art-Net";
        for (Sacn.Uni u : sacn.univers.values()) if (ip.equals(u.src)) return "source sACN";
        for (Ndi.Source s : ndi.sources) if (ip.equals(s.ip)) return "source NDI";
        return "";
    }

    /* ------------------------------ Art-Net ----------------------------- */

    @JavascriptInterface
    public void artPollStart() { art.interroger(); }

    @JavascriptInterface
    public String nodesState() {
        try {
            JSONArray a = new JSONArray();
            for (ArtNet.Noeud n : art.noeuds.values()) {
                JSONObject j = new JSONObject();
                j.put("ip", n.ip);
                j.put("court", n.court);
                j.put("long", n.longNom);
                j.put("mac", n.mac);
                j.put("rapport", n.rapport);
                JSONArray p = new JSONArray();
                for (int u : n.univers) {
                    JSONObject x = new JSONObject();
                    x.put("univers", u + 1);          // base 1, comme sur les pupitres
                    p.put(x);
                }
                j.put("ports", p);
                a.put(j);
            }
            JSONObject o = new JSONObject();
            o.put("noeuds", a);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /* --------------------------- recensement ---------------------------- */

    @JavascriptInterface
    public String universesState() {
        try {
            JSONArray a = new JSONArray();
            for (ArtNet.Uni u : art.univers.values()) {
                JSONObject j = new JSONObject();
                j.put("proto", "Art-Net");
                j.put("univers", u.univers + 1);
                j.put("src", u.src);
                j.put("nom", nomConnu(u.src));
                j.put("hz", u.hz);
                a.put(j);
            }
            for (Sacn.Uni u : sacn.univers.values()) {
                JSONObject j = new JSONObject();
                j.put("proto", "sACN");
                j.put("univers", u.univers);
                j.put("src", u.src);
                j.put("nom", u.nom);
                j.put("prio", u.prio);
                j.put("hz", u.hz);
                a.put(j);
            }
            JSONObject o = new JSONObject();
            o.put("univers", a);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /* ------------------------- écoute d'un univers ---------------------- */

    @JavascriptInterface
    public void dmxStart(String protocole, int u) {
        proto = (protocole != null && protocole.toLowerCase().startsWith("s")) ? "sACN" : "Art-Net";
        univers = u;
        if ("sACN".equals(proto)) { art.ecoute = -1; sacn.suivre(u); }
        else { sacn.suivre(-1); art.ecoute = u - 1; }
    }

    @JavascriptInterface
    public void dmxStop() { art.ecoute = -1; sacn.suivre(-1); }

    @JavascriptInterface
    public String dmxState() {
        try {
            JSONObject o = new JSONObject();
            o.put("proto", proto);
            o.put("univers", univers);
            byte[] n = new byte[512];
            if ("sACN".equals(proto)) {
                synchronized (sacn.niveaux) { System.arraycopy(sacn.niveaux, 0, n, 0, 512); }
                o.put("src", sacn.ecouteSrc);
                o.put("nom", sacn.ecouteNom);
                o.put("prio", sacn.ecoutePrio);
                o.put("hz", sacn.ecouteHz);
            } else {
                synchronized (art.niveaux) { System.arraycopy(art.niveaux, 0, n, 0, 512); }
                o.put("src", art.ecouteSrc);
                o.put("nom", nomConnu(art.ecouteSrc));
                o.put("hz", art.ecouteHz);
            }
            o.put("niveaux", Base64.encodeToString(n, Base64.NO_WRAP));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /* --------------------------------- NDI ------------------------------ */

    @JavascriptInterface
    public void ndiStart() { ndi.lancer(); }

    @JavascriptInterface
    public String ndiState() {
        try {
            JSONArray a = new JSONArray();
            for (Ndi.Source s : ndi.sources) {
                JSONObject j = new JSONObject();
                j.put("nom", s.nom);
                j.put("machine", s.machine);
                j.put("ip", s.ip);
                j.put("port", s.port);
                a.put(j);
            }
            JSONObject o = new JSONObject();
            o.put("encours", ndi.encours);
            o.put("sources", a);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /* --------------------------------- RDM ------------------------------ */

    /** Demande la liste des appareils RDM aux nœuds ; relancer refait la découverte sur les lignes. */
    @JavascriptInterface
    public void rdmDecouvrir(boolean relancer) { rdm.decouvrir(relancer); }

    @JavascriptInterface
    public void rdmRelire(String uid) { rdm.relire(uid); }

    /** quoi : adresse, mode, nom ou identifier. */
    @JavascriptInterface
    public void rdmRegler(String uid, String quoi, String valeur) {
        rdm.regler(uid, quoi, valeur == null ? "" : valeur);
    }

    @JavascriptInterface
    public void rdmOublier() { rdm.oublier(); }

    @JavascriptInterface
    public String rdmEtat() {
        try {
            JSONArray a = new JSONArray();
            for (Rdm.Appareil x : rdm.appareils.values()) {
                JSONObject j = new JSONObject();
                j.put("cle", x.cle);
                j.put("uid", x.uid);
                j.put("via", x.via);
                j.put("endpoint", x.endpoint);
                j.put("ip", x.ip);
                j.put("noeud", x.noeud.isEmpty() ? nomConnu(x.ip) : x.noeud);
                j.put("univers", x.univers + 1);        // base 1, comme sur les pupitres
                j.put("fabricant", x.fabricant);
                j.put("modele", x.modele);
                j.put("nom", x.nom);
                j.put("logiciel", x.logiciel);
                j.put("adresse", x.adresse);
                j.put("canaux", x.canaux);
                j.put("mode", x.mode);
                j.put("modes", x.modes);
                j.put("identifie", x.identifie);
                j.put("lu", x.lu);
                j.put("erreur", x.erreur);
                JSONArray m = new JSONArray();
                for (Rdm.Mode md : x.listeModes.values()) {
                    JSONObject k = new JSONObject();
                    k.put("n", md.numero);
                    k.put("canaux", md.canaux);
                    k.put("nom", md.nom);
                    m.put(k);
                }
                j.put("listeModes", m);
                a.put(j);
            }
            JSONObject o = new JSONObject();
            o.put("encours", rdm.encours);
            o.put("attente", rdm.enAttente());
            o.put("message", rdm.message);
            o.put("noeuds", art.noeuds.size());
            o.put("appareils", a);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Cherche un broker RDMnet ; à défaut, le téléphone en sert un. */
    @JavascriptInterface
    public void rdmnetDemarrer() { rdmnet.demarrerAuto(reseau.ip); }

    /** Broker donné à la main : « 192.168.1.20 » ou « 192.168.1.20:8888 ». */
    @JavascriptInterface
    public void rdmnetConnecter(String adresse) {
        if (adresse == null || adresse.trim().isEmpty()) return;
        String a = adresse.trim();
        int port = 8888;
        int d = a.lastIndexOf(':');
        if (d > 0) {
            try { port = Integer.parseInt(a.substring(d + 1)); } catch (NumberFormatException ignore) { }
            a = a.substring(0, d);
        }
        rdmnet.connecter(a, port, "manuel");
    }

    @JavascriptInterface
    public void rdmnetArreter() { rdmnet.toutArreter(); }

    @JavascriptInterface
    public String rdmnetEtat() {
        try {
            JSONObject o = new JSONObject();
            o.put("mode", rdmnet.mode);
            o.put("actif", rdmnet.actif || !rdmnet.mode.isEmpty());
            o.put("connecte", rdmnet.connecte);
            o.put("broker", rdmnet.broker);
            o.put("message", rdmnet.message);
            int n = 0;
            for (Rdmnet.Client c : rdmnet.clients.values()) if (c.type == Rdmnet.RPT_CLIENT_DEVICE) n++;
            o.put("appareils", n);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public void llrpDecouvrir() { llrp.decouvrir(reseau.ip); }

    @JavascriptInterface
    public String llrpEtat() {
        try {
            JSONArray a = new JSONArray();
            for (Llrp.Cible c : llrp.cibles.values()) {
                JSONObject j = new JSONObject();
                j.put("uid", c.uid);
                j.put("mac", c.mac);
                j.put("ip", c.ip);
                j.put("role", c.role);
                j.put("cid", c.cid);
                a.put(j);
            }
            JSONObject o = new JSONObject();
            o.put("encours", llrp.encours);
            o.put("erreur", llrp.erreur);
            o.put("cibles", a);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /* ------------------------------ émission ---------------------------- */

    @JavascriptInterface
    public boolean send(int universBase1, String niveauxB64, String cible) {
        try {
            byte[] d = Base64.decode(niveauxB64, Base64.DEFAULT);
            return art.emettre(universBase1 - 1, d, cible);
        } catch (Exception e) { return false; }
    }

    /* ------------------------- émission continue ------------------------ */

    /** Protocole, priorité et destination du flux de la télécommande. */
    @JavascriptInterface
    public void emitStart(String protocole, int priorite, String cible) {
        emetteur.regler(protocole, priorite, cible);
        emetteur.demarrer();
    }

    /**
     * Remplace les univers émis. Le JSON porte, par univers en base 1, les
     * 512 niveaux en base 64 : {"1":"AAA…"}. Un objet vide relâche tout.
     */
    @JavascriptInterface
    public boolean emitSet(String json) {
        try {
            JSONObject o = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
            java.util.Map<Integer, byte[]> t = new java.util.HashMap<Integer, byte[]>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                byte[] d = Base64.decode(o.getString(k), Base64.DEFAULT);
                byte[] n = new byte[512];
                System.arraycopy(d, 0, n, 0, Math.min(512, d.length));
                t.put(Integer.parseInt(k), n);
            }
            emetteur.poser(t);
            return true;
        } catch (Exception e) { return false; }
    }

    @JavascriptInterface
    public void emitStop() { emetteur.arreter(); }

    @JavascriptInterface
    public String emitState() {
        try {
            JSONObject o = new JSONObject();
            o.put("actif", emetteur.actif);
            o.put("proto", emetteur.proto);
            o.put("prio", emetteur.prio);
            o.put("cible", emetteur.cible);
            o.put("envois", emetteur.envois);
            o.put("echecs", emetteur.echecs);
            o.put("iface", sacn.interfaceEmission());
            o.put("erreur", sacn.erreur);
            o.put("ip", reseau.ip);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /* ----------------------------- mise à jour -------------------------- */

    @JavascriptInterface
    public void majVerifier() { maj.verifier(); }

    @JavascriptInterface
    public void majTelecharger() { maj.telecharger(); }

    @JavascriptInterface
    public void majInstaller() { maj.installer(); }

    @JavascriptInterface
    public String majEtat() { return maj.etat(); }

    /* ------------------------- impression et GDTF ----------------------- */

    /** Ouvre la boîte d'impression du système : « Enregistrer au format PDF ». */
    @JavascriptInterface
    public boolean imprimer(String html, String nom) {
        try {
            act.imprimerHtml(html, (nom == null || nom.isEmpty()) ? "patch" : nom);
            return true;
        } catch (Exception e) { return false; }
    }

    @JavascriptInterface
    public void gdtfStart() { act.choisirGdtf(); }

    @JavascriptInterface
    public String gdtfState() { return act.gdtfJson; }

    @JavascriptInterface
    public void stopAll() {
        emetteur.arreter();
        rdmnet.toutArreter();
        scan.arreter();
        ndi.arreter();
        art.arreter();
        sacn.arreter();
        reseau.liberer();
    }
}
