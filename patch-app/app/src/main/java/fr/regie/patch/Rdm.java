package fr.regie.patch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * RDM (E1.20) transporté par Art-Net : le nœud fait la découverte sur ses
 * lignes DMX et nous rend la liste des UID (ArtTodData), puis relaie nos
 * questions et nos réglages à chaque appareil (ArtRdm).
 *
 * Le travail passe par une seule file : une question part, on attend sa
 * réponse, puis la suivante. Une ligne DMX ne porte qu'un échange RDM à la
 * fois, et un nœud à qui l'on en envoie dix d'un coup en perd.
 */
public class Rdm {

    /* Commandes et paramètres E1.20 utilisés ici. */
    static final int GET = 0x20, SET = 0x30;
    static final int DEVICE_INFO = 0x0060, DEVICE_MODEL_DESCRIPTION = 0x0080,
            MANUFACTURER_LABEL = 0x0081, DEVICE_LABEL = 0x0082,
            SOFTWARE_VERSION_LABEL = 0x00C0, DMX_PERSONALITY = 0x00E0,
            DMX_PERSONALITY_DESCRIPTION = 0x00E1, DMX_START_ADDRESS = 0x00F0,
            IDENTIFY_DEVICE = 0x1000;

    private static final int ATTENTE_MS = 1500;

    public static class Mode {
        public int numero, canaux;
        public String nom = "";
    }

    public static class Appareil {
        public String uid = "";            // « 4845:12345678 », comme sur les pupitres
        public byte[] uidOctets = new byte[6];
        public String ip = "";             // le nœud qui voit l'appareil
        public int univers;                // adresse de port Art-Net, base 0
        public String fabricant = "", modele = "", nom = "", logiciel = "";
        public int modeleId, categorie, adresse, canaux, mode, modes;
        public boolean identifie, lu;
        public String erreur = "";
        public final Map<Integer, Mode> listeModes = new java.util.concurrent.ConcurrentSkipListMap<Integer, Mode>();
        public long vu;
    }

    public final Map<String, Appareil> appareils = new ConcurrentHashMap<String, Appareil>();
    public volatile boolean encours;
    public volatile String message = "";
    public volatile long decouverte;       // heure de la dernière demande de liste

    private final ArtNet art;
    private final byte[] source = new byte[6];
    private final LinkedBlockingQueue<Runnable> file = new LinkedBlockingQueue<Runnable>();
    private final LinkedBlockingQueue<byte[]> reponses = new LinkedBlockingQueue<byte[]>();
    private Thread ouvrier;
    private int transaction;

    public Rdm(ArtNet a) {
        art = a;
        // UID de contrôleur dans la plage réservée aux prototypes (7FF0-7FFF),
        // tiré au sort à chaque lancement.
        Random r = new Random();
        source[0] = 0x7F; source[1] = (byte) 0xF0;
        for (int i = 2; i < 6; i++) source[i] = (byte) r.nextInt(256);
        art.rdm = new ArtNet.EcouteRdm() {
            public void recu(int op, byte[] b, int len, String src) {
                if (op == 0x8100) todData(b, len, src);
                else reponses.offer(b);
            }
        };
    }

    private synchronized void lancerOuvrier() {
        if (ouvrier != null && ouvrier.isAlive()) return;
        ouvrier = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        Runnable t = file.poll(30, TimeUnit.SECONDS);
                        if (t == null) { encours = false; continue; }
                        encours = true;
                        try { t.run(); } catch (Exception ignore) { }
                        if (file.isEmpty()) encours = false;
                    } catch (InterruptedException e) { return; }
                }
            }
        }, "rdm");
        ouvrier.setDaemon(true);
        ouvrier.start();
    }

    private void planifier(Runnable r) { lancerOuvrier(); encours = true; file.offer(r); }

    public int enAttente() { return file.size(); }

    /* ------------------------------ découverte --------------------------- */

    /**
     * Demande à chaque nœud connu sa liste d'appareils RDM. Avec relancer, on
     * lui demande d'abord de refaire la découverte sur ses lignes (ArtTodControl,
     * AtcFlush) : plus long, mais nécessaire après avoir rebranché des projecteurs.
     */
    public void decouvrir(boolean relancer) {
        decouverte = System.currentTimeMillis();
        art.interroger();                               // rafraîchit la liste des nœuds
        Map<Integer, List<Integer>> parNet = new TreeMap<Integer, List<Integer>>();
        Map<String, List<Integer>> parNoeud = new TreeMap<String, List<Integer>>();
        for (ArtNet.Noeud n : art.noeuds.values()) {
            List<Integer> l = new ArrayList<Integer>();
            for (int u : n.univers) {
                l.add(u);
                List<Integer> p = parNet.get(u >> 8);
                if (p == null) { p = new ArrayList<Integer>(); parNet.put(u >> 8, p); }
                if (!p.contains(u & 0xFF)) p.add(u & 0xFF);
            }
            parNoeud.put(n.ip, l);
        }
        if (relancer) {
            for (Map.Entry<String, List<Integer>> e : parNoeud.entrySet())
                for (int u : e.getValue()) art.envoyer(todControl(u), e.getKey());
            message = "Découverte relancée sur " + parNoeud.size() + " nœud(s)";
            // Le nœud envoie sa liste quand il a fini ; on la redemande quand
            // même un peu plus tard, pour ceux qui attendent qu'on la réclame.
            final Map<Integer, List<Integer>> redemande = parNet;
            new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                    for (Map.Entry<Integer, List<Integer>> e : redemande.entrySet())
                        art.envoyer(todRequest(e.getKey(), e.getValue()), art.diffusion());
                }
            }, "rdm-redemande").start();
        } else {
            message = parNoeud.isEmpty() ? "Aucun nœud Art-Net n'a encore répondu"
                                         : "Liste demandée à " + parNoeud.size() + " nœud(s)";
        }
        // Sans nœud connu, on demande l'univers 0 : certains nœuds répondent
        // à une demande générale même avant l'ArtPoll.
        if (parNet.isEmpty()) { List<Integer> l = new ArrayList<Integer>(); l.add(0); parNet.put(0, l); }
        for (Map.Entry<Integer, List<Integer>> e : parNet.entrySet())
            art.envoyer(todRequest(e.getKey(), e.getValue()), art.diffusion());
    }

    static byte[] entete(int op, int taille) {
        byte[] p = new byte[taille];
        byte[] id = { 'A', 'r', 't', '-', 'N', 'e', 't', 0 };
        System.arraycopy(id, 0, p, 0, 8);
        p[8] = (byte) (op & 0xFF); p[9] = (byte) (op >> 8);
        p[10] = 0; p[11] = 14;
        return p;
    }

    static byte[] todRequest(int net, List<Integer> adresses) {
        int n = Math.min(32, adresses.size());
        byte[] p = entete(0x8000, 24 + 32);        // taille fixe : certains nœuds l'exigent
        p[21] = (byte) net;
        p[22] = 0;                                 // TodFull
        p[23] = (byte) n;
        for (int i = 0; i < n; i++) p[24 + i] = (byte) (int) adresses.get(i);
        return p;
    }

    static byte[] todControl(int portAddress) {
        byte[] p = entete(0x8200, 24);
        p[21] = (byte) (portAddress >> 8);
        p[22] = 0x01;                              // AtcFlush
        p[23] = (byte) (portAddress & 0xFF);
        return p;
    }

    private void todData(byte[] b, int len, String src) {
        if (len < 28) return;
        if ((b[22] & 255) == 0xFF) return;          // TodNak
        int univers = ((b[21] & 0x7F) << 8) | (b[23] & 255);
        int nb = b[27] & 255;
        int total = ((b[24] & 255) << 8) | (b[25] & 255);
        int bloc = b[26] & 255;
        long t = System.currentTimeMillis();
        java.util.Set<String> vus = new java.util.HashSet<String>();
        for (int i = 0; i < nb && 28 + i * 6 + 6 <= len; i++) {
            byte[] u = new byte[6];
            System.arraycopy(b, 28 + i * 6, u, 0, 6);
            String cle = uidTexte(u);
            Appareil a = appareils.get(cle);
            boolean neuf = a == null;
            if (neuf) { a = new Appareil(); a.uid = cle; a.uidOctets = u; appareils.put(cle, a); }
            a.ip = src; a.univers = univers; a.vu = t;
            vus.add(cle);
            if (neuf) relire(cle);
        }
        // Liste complète en un seul bloc : ce qui n'y est plus a été débranché.
        if (bloc == 0 && nb == total)
            for (Appareil a : appareils.values())
                if (a.ip.equals(src) && a.univers == univers && !vus.contains(a.uid))
                    appareils.remove(a.uid);
    }

    static String uidTexte(byte[] u) {
        return String.format("%02X%02X:%02X%02X%02X%02X",
                u[0] & 255, u[1] & 255, u[2] & 255, u[3] & 255, u[4] & 255, u[5] & 255);
    }

    /* ------------------------------- lecture ----------------------------- */

    /** Relit tout ce que l'écran affiche d'un appareil. */
    public void relire(final String uid) {
        planifier(new Runnable() { public void run() { lireTout(uid); } });
    }

    private void lireTout(String uid) {
        Appareil a = appareils.get(uid);
        if (a == null) return;
        byte[] r = demander(a, GET, DEVICE_INFO, new byte[0]);
        if (r == null) { a.lu = true; return; }
        if (r.length >= 19) {
            a.modeleId = u16(r, 2);
            a.categorie = u16(r, 4);
            a.canaux = u16(r, 10);
            a.mode = r[12] & 255;
            a.modes = r[13] & 255;
            a.adresse = u16(r, 14);
        }
        byte[] t;
        if ((t = demander(a, GET, MANUFACTURER_LABEL, new byte[0])) != null) a.fabricant = texte(t);
        if ((t = demander(a, GET, DEVICE_MODEL_DESCRIPTION, new byte[0])) != null) a.modele = texte(t);
        if ((t = demander(a, GET, DEVICE_LABEL, new byte[0])) != null) a.nom = texte(t);
        if ((t = demander(a, GET, SOFTWARE_VERSION_LABEL, new byte[0])) != null) a.logiciel = texte(t);
        if ((t = demander(a, GET, IDENTIFY_DEVICE, new byte[0])) != null && t.length >= 1) a.identifie = t[0] != 0;
        for (int m = 1; m <= a.modes && m <= 64; m++) {
            if (a.listeModes.containsKey(m)) continue;
            byte[] d = demander(a, GET, DMX_PERSONALITY_DESCRIPTION, new byte[] { (byte) m });
            if (d == null || d.length < 3) continue;
            Mode md = new Mode();
            md.numero = d[0] & 255;
            md.canaux = u16(d, 1);
            md.nom = d.length > 3 ? new String(d, 3, d.length - 3, java.nio.charset.StandardCharsets.US_ASCII).trim() : "";
            a.listeModes.put(md.numero, md);
        }
        a.lu = true;
    }

    /* ------------------------------ réglages ----------------------------- */

    /**
     * Un réglage, puis la relecture de l'appareil : l'écran montre ce que le
     * projecteur a vraiment pris, pas ce qu'on lui a demandé.
     */
    public void regler(final String uid, final String quoi, final String valeur) {
        planifier(new Runnable() {
            public void run() {
                Appareil a = appareils.get(uid);
                if (a == null) return;
                byte[] pd;
                int pid;
                try {
                    if ("adresse".equals(quoi)) {
                        int v = Integer.parseInt(valeur.trim());
                        if (v < 1 || v > 512) { a.erreur = "adresse hors de 1-512"; return; }
                        pid = DMX_START_ADDRESS; pd = new byte[] { (byte) (v >> 8), (byte) v };
                    } else if ("mode".equals(quoi)) {
                        pid = DMX_PERSONALITY; pd = new byte[] { (byte) Integer.parseInt(valeur.trim()) };
                    } else if ("nom".equals(quoi)) {
                        byte[] s = valeur.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                        pid = DEVICE_LABEL; pd = java.util.Arrays.copyOf(s, Math.min(32, s.length));
                    } else if ("identifier".equals(quoi)) {
                        pid = IDENTIFY_DEVICE; pd = new byte[] { (byte) ("1".equals(valeur) ? 1 : 0) };
                    } else return;
                } catch (NumberFormatException e) { a.erreur = "valeur illisible"; return; }
                a.erreur = "";
                byte[] r = demander(a, SET, pid, pd);
                if (r == null) return;                  // l'erreur est déjà posée
                if (pid == IDENTIFY_DEVICE) { a.identifie = pd[0] != 0; return; }
                lireTout(uid);
            }
        });
    }

    /* ------------------------------ échange ------------------------------ */

    /**
     * Envoie une commande et attend la réponse de cet appareil à ce paramètre.
     * Rend les données de la réponse, ou null en posant a.erreur.
     */
    private byte[] demander(Appareil a, int cc, int pid, byte[] pd) {
        for (int essai = 0; essai < 3; essai++) {
            int tn = (transaction++) & 0xFF;
            reponses.clear();
            if (!art.envoyer(artRdm(a.univers, a.uidOctets, source, tn, cc, pid, pd), a.ip)) {
                a.erreur = "envoi impossible"; return null;
            }
            long fin = System.currentTimeMillis() + ATTENTE_MS;
            while (true) {
                long reste = fin - System.currentTimeMillis();
                if (reste <= 0) break;
                byte[] b;
                try { b = reponses.poll(reste, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { return null; }
                if (b == null) break;
                Reponse r = lire(b);
                if (r == null || !r.de(a.uidOctets) || r.pid != pid || r.cc != cc + 1) continue;
                if (r.type == 0x00) return r.donnees;                          // ACK
                if (r.type == 0x02) {                                          // NACK
                    a.erreur = nack(r.donnees.length >= 2 ? u16(r.donnees, 0) : -1, pid);
                    return null;
                }
                if (r.type == 0x01) {                                          // ACK_TIMER
                    int dixiemes = r.donnees.length >= 2 ? u16(r.donnees, 0) : 5;
                    try { Thread.sleep(Math.min(3000, dixiemes * 100L)); } catch (InterruptedException e) { return null; }
                    if (cc == SET) { a.erreur = ""; return new byte[0]; }     // pris en compte plus tard
                    break;                                                     // on redemande
                }
                a.erreur = "réponse en plusieurs morceaux non gérée";
                return null;
            }
        }
        a.erreur = "pas de réponse";
        return null;
    }

    /** Paquet ArtRdm : le message RDM sans son code de départ, somme comprise. */
    static byte[] artRdm(int portAddress, byte[] dest, byte[] src, int tn, int cc, int pid, byte[] pd) {
        byte[] m = messageRdm(dest, src, tn, cc, pid, pd);
        byte[] p = entete(0x8300, 24 + m.length - 1);
        p[12] = 0x01;                                // RdmVer
        p[21] = (byte) ((portAddress >> 8) & 0x7F);
        p[22] = 0x00;                                // ArProcess
        p[23] = (byte) (portAddress & 0xFF);
        System.arraycopy(m, 1, p, 24, m.length - 1);
        return p;
    }

    /** Message RDM complet, code de départ 0xCC compris et somme de contrôle à la fin. */
    static byte[] messageRdm(byte[] dest, byte[] src, int tn, int cc, int pid, byte[] pd) {
        int len = 24 + pd.length;
        byte[] m = new byte[len + 2];
        m[0] = (byte) 0xCC; m[1] = 0x01; m[2] = (byte) len;
        System.arraycopy(dest, 0, m, 3, 6);
        System.arraycopy(src, 0, m, 9, 6);
        m[15] = (byte) tn;
        m[16] = 0x01;                                // port 1
        m[17] = 0;                                   // message count
        m[18] = 0; m[19] = 0;                        // sous-appareil racine
        m[20] = (byte) cc;
        m[21] = (byte) (pid >> 8); m[22] = (byte) pid;
        m[23] = (byte) pd.length;
        System.arraycopy(pd, 0, m, 24, pd.length);
        int s = 0;
        for (int i = 0; i < len; i++) s += m[i] & 255;
        m[len] = (byte) (s >> 8); m[len + 1] = (byte) s;
        return m;
    }

    static class Reponse {
        byte[] src = new byte[6];
        int type, cc, pid;
        byte[] donnees = new byte[0];
        boolean de(byte[] uid) { return java.util.Arrays.equals(src, uid); }
    }

    /**
     * Lit un ArtRdm reçu. Certains nœuds renvoient le code de départ 0xCC,
     * d'autres non : on accepte les deux.
     */
    static Reponse lire(byte[] b) {
        if (b.length < 24 + 23) return null;
        int o = 24;
        if ((b[o] & 255) == 0xCC) o++;
        if ((b[o] & 255) != 0x01) return null;       // sous-code de départ RDM
        int pdl = b[o + 22] & 255;
        if (o + 23 + pdl > b.length) return null;
        Reponse r = new Reponse();
        System.arraycopy(b, o + 8, r.src, 0, 6);
        r.type = b[o + 15] & 255;
        r.cc = b[o + 19] & 255;
        r.pid = ((b[o + 20] & 255) << 8) | (b[o + 21] & 255);
        r.donnees = java.util.Arrays.copyOfRange(b, o + 23, o + 23 + pdl);
        return r;
    }

    static String nack(int raison, int pid) {
        switch (raison) {
            case 0x0000: return "refusé par l'appareil";
            case 0x0001: return "paramètre non pris en charge";
            case 0x0002: return "message mal formé";
            case 0x0003: return "données mal formées";
            case 0x0004: return "file de l'appareil pleine";
            case 0x0005: return "données trop longues";
            case 0x0006: return "valeur hors limites";
            case 0x0007: return "appareil occupé";
            case 0x0008: return "écriture protégée";
            case 0x0009: return "sous-appareil hors limites";
            default: return "refusé (" + String.format("%04X", pid) + ")";
        }
    }

    static int u16(byte[] b, int i) { return ((b[i] & 255) << 8) | (b[i + 1] & 255); }

    static String texte(byte[] b) {
        int n = 0;
        while (n < b.length && b[n] != 0) n++;
        return new String(b, 0, n, java.nio.charset.StandardCharsets.US_ASCII).trim();
    }

    public void oublier() { appareils.clear(); file.clear(); message = ""; }
}
