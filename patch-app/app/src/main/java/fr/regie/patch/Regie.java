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

    private final Reseau reseau;
    private final ArtNet art = new ArtNet();
    private final Sacn sacn = new Sacn();
    private final Scanner scan = new Scanner();
    private final Ndi ndi;

    private volatile String proto = "Art-Net";
    private volatile int univers = 1;

    public Regie(Context c) {
        reseau = new Reseau(c);
        ndi = new Ndi(c);
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

    /* ------------------------------ émission ---------------------------- */

    @JavascriptInterface
    public boolean send(int universBase1, String niveauxB64, String cible) {
        try {
            byte[] d = Base64.decode(niveauxB64, Base64.DEFAULT);
            return art.emettre(universBase1 - 1, d, cible);
        } catch (Exception e) { return false; }
    }

    @JavascriptInterface
    public void stopAll() {
        scan.arreter();
        ndi.arreter();
        art.arreter();
        sacn.arreter();
        reseau.liberer();
    }
}
